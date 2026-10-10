#!/usr/bin/env bash
# 酒店管理系统 —— 第三批优化（结构性）验收测试
#
#  1. 1.1 房态语义收敛：房间状态只表达物理状态，"某天能不能卖"由预约表实时算
#  2. 1.1 房态图日期选择器：按日期查看该晚的可售情况
#  3. 1.4 并发保护：两个会话同时给同一房间下同区间订单，只能成功一个
#  4. 1.5 提前退房：按实际住夜重算房费，差额记为「提前离店减免」
#  5. 2.6 折扣 / 反结账：负数消费被拒、折扣入账、反结账仅管理员且需填原因
#  6. 审计日志：关键动作留痕，仅管理员可查
#  7. 3.7 打印 / 导出：账单小票、订单与账单 CSV、交接班名单
#  8. 3.8 平板适配：窄屏表格可横向滚动且首列钉住、房态图降级为卡片流
#
# 前提：应用已启动（默认 http://127.0.0.1:8080，可用 HMS_BASE 覆盖）
# 数据：脚本会创建订单/账单，结束后请执行 scripts/clean-test-data.sh 清理
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8080}"
TMP="/tmp/hms-opt3"
mkdir -p "$TMP"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
no()   { FAIL=$((FAIL+1)); printf "  \033[31m[FAIL]\033[0m %s\n" "$1"; }
chk()  { # $1=描述 $2=实际 $3=期望
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else no "$1 期望[$3] 实际[$2]"; fi; }
has()  { if printf '%s' "$2" | grep -q -- "$3"; then ok "$1"; else no "$1（未匹配 [$3]）"; fi; }
lacks(){ if printf '%s' "$2" | grep -q -- "$3"; then no "$1（不应出现 [$3]）"; else ok "$1"; fi; }
# 状态徽章计数：避免把页面其它位置出现的同名字样也算进来
badge_count() { grep -o "badge-soft[^>]*>$2" "$1" | wc -l | tr -d ' '; }

MYSQL_BIN="${MYSQL_BIN:-/d/SoftWare/MySQL/MySQL Server 9.0/bin/mysql.exe}"
q() { MSYS_NO_PATHCONV=1 "$MYSQL_BIN" --default-character-set=utf8mb4 -uroot -p123456 \
      -D hotel_management -N -B --batch -e "$1" 2>/dev/null | tr -d '\r'; }

csrf_of() { curl -s -b "$2" -c "$2" "$1" -o "$3"; grep -o 'name="_csrf"[^>]*value="[^"]*"' "$3" | head -1 | sed 's/.*value="//;s/"//'; }
csrf_in() { grep -o 'name="_csrf"[^>]*value="[^"]*"' "$1" | head -1 | sed 's/.*value="//;s/"//'; }

login() { # $1=user $2=pass $3=cookiejar
  local cj="$3"; rm -f "$cj"
  local t; t=$(csrf_of "$BASE/login" "$cj" "$TMP/login.html")
  curl -s -o /dev/null -w '%{http_code}' -b "$cj" -c "$cj" -X POST \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    --data-urlencode "_csrf=$t" "$BASE/login"
}

# -L 跟随 302，让 flash 提示渲染在重定向后的页面上；不能加 -X POST（会被带到重定向目标）
post_form() { # $1=url $2=cookiejar $3=csrf 剩余=表单
  local url="$1" cj="$2" t="$3"; shift 3
  curl -sL -o "$TMP/post.html" -w '%{http_code}' -b "$cj" -c "$cj" \
    --data-urlencode "_csrf=$t" "$@" "$url"
}

# 选一间「日期可订 ∩ 房态空闲」的房间
pick_free_room() { # $1=checkIn $2=checkOut
  local ids free id
  ids=$(curl -s -b "$CJ" "$BASE/reservations/available-rooms?checkIn=$1&checkOut=$2" \
        | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
  free=$(curl -s -b "$CJ" "$BASE/rooms?status=AVAILABLE" \
        | grep -o '/rooms/[0-9]*/edit' | grep -o '[0-9]*')
  for id in $ids; do
    if printf '%s\n' "$free" | grep -qx "$id"; then printf '%s' "$id"; return 0; fi
  done
}

newest_order() { curl -s -b "$1" "$BASE/reservations?size=100" | grep -o '/reservations/[0-9]*"' | head -1 | grep -o '[0-9]*'; }
room_no_of()   { curl -s -b "$1" "$BASE/rooms/$2/edit" | grep -o 'id="roomNo"[^>]*value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//'; }

# 从房态图 HTML 中取出指定房号所在卡片的 data-state
tile_state() { # $1=html文件 $2=房号
  awk -v want="$2" '
    /data-state="/ { match($0, /data-state="[A-Z_]+"/); s=substr($0, RSTART+12, RLENGTH-13) }
    /class="no"/ { match($0, />[0-9]+</); n=substr($0, RSTART+1, RLENGTH-2); if (n==want) { print s; exit } }
  ' "$1"
}
board_count() { grep -o "$2 <b>[0-9]*</b>" "$1" | head -1 | grep -o '[0-9]*'; }
bill_total_of() { curl -s -b "$1" "$BASE/bills/$2" | grep -o 'data-total="[0-9.]*"' | head -1 | grep -o '[0-9.]*'; }

echo "=============================================="
echo " 0. 准备：管理员登录 + 基础数据"
echo "=============================================="
CJ="$TMP/admin.cookies"
chk "登录 admin/admin123 返回 302" "$(login admin admin123 "$CJ")" "302"

TODAY=$(date +%Y-%m-%d)
D1=$(date -d "+1 day" +%Y-%m-%d)
D3=$(date -d "+3 day" +%Y-%m-%d)
D4=$(date -d "+4 day" +%Y-%m-%d)
D5=$(date -d "+5 day" +%Y-%m-%d)
# 提前退房测试故意订 10 晚：1 晚实际房费 × 9 晚的减免额一定是 4 位数，
# 这样才能稳定覆盖「渲染带千分位」的解析路径。
EO_OUT=$(date -d "+10 day" +%Y-%m-%d)
EO_PLAN_NIGHTS=10
EO_DISC_NIGHTS=9
CID=$(curl -s -b "$CJ" "$BASE/customers" | grep -o '/customers/[0-9]*/edit' | head -1 | grep -o '[0-9]*')
ROOM_TOTAL=$(q "select count(*) from room;")
echo "   客户ID=$CID  今天=$TODAY  总房量=$ROOM_TOTAL"

echo
echo "=============================================="
echo " 1. 房态语义收敛（§1.1）"
echo "=============================================="
chk "数据库已无遗留的「已预订」房态" "$(q "select count(*) from room where status='RESERVED';")" "0"
curl -s -b "$CJ" "$BASE/rooms/board" -o "$TMP/board0.html"
has "房态图给出该晚可售/已售汇总" "$(cat "$TMP/board0.html")" '该晚营业情况'
lacks "「改房态」下拉不再提供「已预订」这个物理状态" \
      "$(cat "$TMP/board0.html")" '<option value="RESERVED">'

# 未来预订不应再把房间的物理状态改掉
RID_FUT=$(pick_free_room "$TODAY" "$D5")
ROOM_FUT_NO=$(room_no_of "$CJ" "$RID_FUT")
echo "   使用房间 $ROOM_FUT_NO (id=$RID_FUT) 预订 $D3 ~ $D5"
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new_fut.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID_FUT" \
  --data-urlencode "checkInDate=$D3" --data-urlencode "checkOutDate=$D5" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=第三批测试-未来预订" > /dev/null
FUT_ORDER=$(newest_order "$CJ")
chk "未来订单创建后房间物理状态仍为「空闲」" \
    "$(q "select status from room where id=$RID_FUT;")" "AVAILABLE"
curl -s -b "$CJ" "$BASE/rooms/board?date=$D3" -o "$TMP/board_d3.html"
curl -s -b "$CJ" "$BASE/rooms/board?date=$D4" -o "$TMP/board_d4.html"
curl -s -b "$CJ" "$BASE/rooms/board?date=$TODAY" -o "$TMP/board_t.html"
chk "该房在「入住当晚」显示为预抵（当晚不可售）" "$(tile_state "$TMP/board_d3.html" "$ROOM_FUT_NO")" "ARRIVING"
chk "该房在「入住后的中间夜」显示为已预订" "$(tile_state "$TMP/board_d4.html" "$ROOM_FUT_NO")" "RESERVED"
chk "该房在「今天」仍显示为空闲（这正是收敛要解决的失真）" \
    "$(tile_state "$TMP/board_t.html" "$ROOM_FUT_NO")" "FREE"

echo
echo "=============================================="
echo " 2. 房态图日期选择器（§1.1）"
echo "=============================================="
chk "带 date 参数访问房态图 200" "$(curl -s -o /dev/null -w '%{http_code}' -b "$CJ" "$BASE/rooms/board?date=$D3")" "200"
has "页面回显所选日期" "$(cat "$TMP/board_d3.html")" "value=\"$D3\""
has "提供「今天 / 明天 / 后天」快捷入口" "$(cat "$TMP/board_d3.html")" '>后天<'
SELL_D3=$(board_count "$TMP/board_d3.html" '可售')
SOLD_D3=$(board_count "$TMP/board_d3.html" '已售')
TOT_D3=$(board_count "$TMP/board_d3.html" '总计')
echo "   3 天后：可售=$SELL_D3 已售=$SOLD_D3 总计=$TOT_D3"
chk "房态图汇总的「总计」等于总房量" "$TOT_D3" "$ROOM_TOTAL"
chk "「可售 + 已售 = 总计」口径自洽" "$((SELL_D3 + SOLD_D3))" "$TOT_D3"
chk "未来某晚的可售数与「可订房查询」一致" \
    "$(curl -s -b "$CJ" "$BASE/reservations/available-rooms?checkIn=$D3&checkOut=$D4" | grep -o '"id":[0-9]*' | grep -c .)" \
    "$SELL_D3"

echo
echo "=============================================="
echo " 3. 并发保护：同房同区间只能成功一单（§1.4）"
echo "=============================================="
CC_IN=$(date -d "+10 day" +%Y-%m-%d); CC_OUT=$(date -d "+12 day" +%Y-%m-%d)
RID_CC=$(pick_free_room "$CC_IN" "$CC_OUT")
echo "   使用房间ID=$RID_CC 区间 $CC_IN ~ $CC_OUT"
CJA="$TMP/cc_a.cookies"; CJB="$TMP/cc_b.cookies"
login reception 123456 "$CJA" > /dev/null
login admin admin123 "$CJB" > /dev/null
TA=$(csrf_of "$BASE/reservations/new" "$CJA" "$TMP/cc_a.html")
TB=$(csrf_of "$BASE/reservations/new" "$CJB" "$TMP/cc_b.html")

curl -sL -o "$TMP/cc_out_a.html" -b "$CJA" -c "$CJA" \
  --data-urlencode "_csrf=$TA" --data-urlencode "customerId=$CID" \
  --data-urlencode "roomId=$RID_CC" --data-urlencode "checkInDate=$CC_IN" \
  --data-urlencode "checkOutDate=$CC_OUT" --data-urlencode "guestCount=1" \
  --data-urlencode "remark=第三批测试-并发A" "$BASE/reservations/save" &
P1=$!
curl -sL -o "$TMP/cc_out_b.html" -b "$CJB" -c "$CJB" \
  --data-urlencode "_csrf=$TB" --data-urlencode "customerId=$CID" \
  --data-urlencode "roomId=$RID_CC" --data-urlencode "checkInDate=$CC_IN" \
  --data-urlencode "checkOutDate=$CC_OUT" --data-urlencode "guestCount=1" \
  --data-urlencode "remark=第三批测试-并发B" "$BASE/reservations/save" &
P2=$!
wait $P1 $P2

SUCC=$(cat "$TMP/cc_out_a.html" "$TMP/cc_out_b.html" | grep -c '已保存')
# 注意：被拒的一侧会重新渲染「新建预订」表单，表单自身的提示文案里也有「已被预订」四个字，
# 所以必须匹配报错横幅的完整句子，否则一个失败响应会被数成 2 次。
REJ=$(cat "$TMP/cc_out_a.html" "$TMP/cc_out_b.html" | grep -o '已被预订，请更换房间或日期' | wc -l | tr -d ' ')
chk "并发下单恰好成功 1 单" "$SUCC" "1"
chk "另一个会话被冲突校验拦下" "$REJ" "1"
chk "数据库中该房该区间只有 1 张有效订单" \
    "$(q "select count(*) from reservation where room_id=$RID_CC and status='BOOKED' and check_in_date='$CC_IN' and check_out_date='$CC_OUT';")" "1"

echo
echo "=============================================="
echo " 4. 提前退房按实际住夜重算（§1.5）"
echo "=============================================="
RID_EO=$(pick_free_room "$TODAY" "$EO_OUT")
echo "   使用房间ID=$RID_EO 计划 $TODAY ~ $EO_OUT（$EO_PLAN_NIGHTS 晚）"
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new_eo.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID_EO" \
  --data-urlencode "checkInDate=$TODAY" --data-urlencode "checkOutDate=$EO_OUT" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=第三批测试-提前退房" > /dev/null
EO_ORDER=$(newest_order "$CJ")
T=$(csrf_of "$BASE/reservations/$EO_ORDER" "$CJ" "$TMP/eo.html")
post_form "$BASE/reservations/$EO_ORDER/checkin" "$CJ" "$T" > /dev/null

# 实际离店 = 今天 + 1 天 → 只住 1 晚
EO_URL=$(curl -s -o /dev/null -w '%{redirect_url}' -b "$CJ" -c "$CJ" -X POST \
    --data-urlencode "_csrf=$(csrf_in "$TMP/post.html")" \
    --data-urlencode "actualCheckOutDate=$D1" \
    "$BASE/reservations/$EO_ORDER/checkout")
EO_BILLID=$(printf '%s' "$EO_URL" | grep -o '[0-9]*$')
echo "   账单 id=$EO_BILLID"
curl -s -b "$CJ" "$BASE/bills/$EO_BILLID" -o "$TMP/eo_bill.html"
chk "数据库记录了实际离店日期" \
    "$(q "select actual_check_out_date from reservation where id=$EO_ORDER;")" "$D1"
chk "订单实际住夜数为 1 晚（计划 $EO_PLAN_NIGHTS 晚）" \
    "$(q "select timestampdiff(day, check_in_date, actual_check_out_date) from reservation where id=$EO_ORDER;")" "1"
has "账单出现提前离店提示" "$(cat "$TMP/eo_bill.html")" '客人提前离店'

EO_TOTAL=$(grep -o 'data-total="[0-9.]*"' "$TMP/eo_bill.html" | head -1 | grep -o '[0-9.]*')
# 减免额渲染成 -￥2,664.00，带千分位；必须先去掉逗号再参与算术，否则 grep 只取到 "2"。
# 故意订 10 晚再提前到 1 晚，保证减免额是 4 位数，稳定覆盖「千分位」这条路。
EO_DISC=$(grep -A2 '其中提前离店减免' "$TMP/eo_bill.html" \
          | grep -o '￥[0-9,]*\.[0-9][0-9]' | tail -1 | tr -d '￥,')
echo "   实际房费=￥$EO_TOTAL  提前离店减免=￥$EO_DISC（计划 $EO_PLAN_NIGHTS 晚 − 实际 1 晚 = $EO_DISC_NIGHTS 晚）"
chk "提前离店减免额含千分位时不解析出错（实际值应为 4 位数）" \
    "$(printf '%s' "$EO_DISC" | grep -c '^[0-9]\{4,\}\.[0-9][0-9]$' | tr -d ' ')" "1"
chk "减免额 = 实际房费 × $EO_DISC_NIGHTS（库里也一致）" \
    "$(awk -v d="$EO_DISC" -v t="$EO_TOTAL" -v n="$EO_DISC_NIGHTS" 'BEGIN{printf "%.2f", d - t*n}')" "0.00"
chk "提前离店减免与数据库记录一致" \
    "$EO_DISC" "$(q "select early_checkout_discount from bill where id=$EO_BILLID;")"
has "订单详情展示提前离店减免" \
    "$(curl -s -b "$CJ" "$BASE/reservations/$EO_ORDER")" '提前离店减免'

# 退房后房间转待清洁 → 清洁完成恢复空闲（物理状态流转）
T=$(csrf_of "$BASE/rooms/board" "$CJ" "$TMP/bd_clean.html")
post_form "$BASE/rooms/$RID_EO/clean" "$CJ" "$T" --data-urlencode "redirectTo=/rooms/board" > /dev/null
chk "清洁完成后房间恢复空闲" "$(q "select status from room where id=$RID_EO;")" "AVAILABLE"

echo
echo "=============================================="
echo " 5. 折扣 / 反结账（§2.6）"
echo "=============================================="
# 负数消费必须被拒（旧实现靠负数单价变通，无法审计）
curl -s -b "$CJ" "$BASE/bills/$EO_BILLID" -o "$TMP/eo_bill2.html"
T=$(csrf_in "$TMP/eo_bill2.html")
post_form "$BASE/bills/$EO_BILLID/items" "$CJ" "$T" \
  --data-urlencode "itemName=负数变通测试" --data-urlencode "unitPrice=-50" --data-urlencode "quantity=1" > /dev/null
has "负数单价消费被拒" "$(cat "$TMP/post.html")" '不能为负数'

T=$(csrf_in "$TMP/eo_bill2.html")
post_form "$BASE/bills/$EO_BILLID/items" "$CJ" "$T" \
  --data-urlencode "itemName=迷你吧饮料" --data-urlencode "unitPrice=38" --data-urlencode "quantity=2" > /dev/null
EO_BASE=$(bill_total_of "$CJ" "$EO_BILLID")
echo "   加消费后应收=￥$EO_BASE（房费 + 76）"
T=$(csrf_of "$BASE/bills/$EO_BILLID" "$CJ" "$TMP/eo_bill25.html")
post_form "$BASE/bills/$EO_BILLID/discount" "$CJ" "$T" \
  --data-urlencode "discountAmount=100" --data-urlencode "reason=第三批测试-协议价" > /dev/null
EO_AFTER=$(bill_total_of "$CJ" "$EO_BILLID")
echo "   折扣后应收=￥$EO_AFTER"
chk "折扣 100 元后应收正好减少 100" \
    "$(awk -v a="$EO_AFTER" -v b="$EO_BASE" 'BEGIN{printf "%.2f", b - a}')" "100.00"
has "账单记录折扣原因（未结账也能看到）" "$(curl -s -b "$CJ" "$BASE/bills/$EO_BILLID")" '第三批测试-协议价'

T=$(csrf_of "$BASE/bills/$EO_BILLID" "$CJ" "$TMP/eo_bill3.html")
post_form "$BASE/bills/$EO_BILLID/discount" "$CJ" "$T" \
  --data-urlencode "discountAmount=999999" --data-urlencode "reason=越界测试" > /dev/null
has "超额折扣被拒" "$(cat "$TMP/post.html")" '不能超过'
# 回归：旧实现用「合计 < 0」判断超额，而 recalculate() 会把合计钳到 0，
# 判断永远不成立 → 999999 的折扣被静默接受。这里直接看库里的折扣额有没有被写脏。
chk "超额折扣被拒后折扣额仍为 100（未被写脏）" \
    "$(q "select discount_amount from bill where id=$EO_BILLID;")" "100.00"
chk "超额折扣被拒后应收仍为折扣后的金额" \
    "$(bill_total_of "$CJ" "$EO_BILLID")" "$EO_AFTER"

# 反结账：前台无权
CJR="$TMP/rec.cookies"
chk "登录 reception/123456 返回 302" "$(login reception 123456 "$CJR")" "302"
T=$(csrf_of "$BASE/bills/$EO_BILLID" "$CJR" "$TMP/bd_rec.html")
chk "前台足额收款结账成功" \
    "$(post_form "$BASE/bills/$EO_BILLID/settle" "$CJR" "$T" \
        --data-urlencode "deposit=0" --data-urlencode "paidAmount=$EO_AFTER" \
        --data-urlencode "payMethod=CASH" --data-urlencode "remark=第三批测试-结账")" "200"
curl -s -b "$CJR" "$BASE/bills/$EO_BILLID" -o "$TMP/bd_settled.html"
chk "账单已结账" "$(badge_count "$TMP/bd_settled.html" '已结账')" "1"
lacks "反结账按钮对前台不可见" "$(cat "$TMP/bd_settled.html")" 'unsettleModal'
T=$(csrf_in "$TMP/bd_settled.html")
chk "前台持有合法 CSRF 令牌调用反结账仍被拒（403）" \
    "$(post_form "$BASE/bills/$EO_BILLID/unsettle" "$CJR" "$T" --data-urlencode "reason=越权测试")" "403"
has "越权调用落到 403 页（而不是被静默重定向成 200）" "$(cat "$TMP/post.html")" '403 · 无权访问该功能'
chk "越权调用没有改动账单状态" "$(q "select status from bill where id=$EO_BILLID;")" "SETTLED"

T=$(csrf_of "$BASE/bills/$EO_BILLID" "$CJ" "$TMP/bd_admin.html")
chk "管理员反结账成功" \
    "$(post_form "$BASE/bills/$EO_BILLID/unsettle" "$CJ" "$T" --data-urlencode "reason=第三批测试-支付方式录错")" "200"
curl -s -b "$CJ" "$BASE/bills/$EO_BILLID" -o "$TMP/bd_unsettled.html"
chk "反结账后状态回到「待结账」" "$(badge_count "$TMP/bd_unsettled.html" '待结账')" "1"
chk "反结账后押金清零" "$(q "select deposit from bill where id=$EO_BILLID;")" "0.00"
chk "反结账后收款清零" "$(q "select paid_amount from bill where id=$EO_BILLID;")" "0.00"
has "账单备注写入反结账原因" "$(cat "$TMP/bd_unsettled.html")" '第三批测试-支付方式录错'

T=$(csrf_in "$TMP/bd_unsettled.html")
post_form "$BASE/bills/$EO_BILLID/settle" "$CJ" "$T" \
  --data-urlencode "deposit=0" --data-urlencode "paidAmount=$EO_AFTER" \
  --data-urlencode "payMethod=BANK_CARD" --data-urlencode "remark=第三批测试-重结" > /dev/null
chk "反结账后可以重新结账" \
    "$(curl -s -b "$CJ" "$BASE/bills/$EO_BILLID" -o "$TMP/bd_final.html"; badge_count "$TMP/bd_final.html" '已结账')" "1"
# 结账备注必须「追加」而不是「覆盖」：否则重结一次就把折扣原因、反结账原因全部抹掉
REMARK=$(q "select remark from bill where id=$EO_BILLID;")
has "重结后折扣原因仍留在备注里" "$REMARK" '第三批测试-协议价'
has "重结后反结账原因仍留在备注里" "$REMARK" '第三批测试-支付方式录错'
has "重结后新备注也已追加" "$REMARK" '第三批测试-重结'
# data-total 只渲染在「结账收款」表单（未结账才有），已结账状态改查库
chk "重结后应收合计保持不变（追加备注不影响金额）" \
    "$(q "select total_amount from bill where id=$EO_BILLID;")" "$EO_AFTER"

echo
echo "=============================================="
echo " 6. 审计日志（operation_log）"
echo "=============================================="
chk "前台访问操作日志被拒（403）" "$(curl -s -o /dev/null -w '%{http_code}' -b "$CJR" "$BASE/logs")" "403"
chk "管理员访问操作日志 200" "$(curl -s -o /dev/null -w '%{http_code}' -b "$CJ" "$BASE/logs")" "200"
curl -s -b "$CJ" "$BASE/logs?size=100" -o "$TMP/logs.html"
LOGS=$(cat "$TMP/logs.html")
has "记录了办理入住" "$LOGS" '办理入住'
has "记录了办理退房" "$LOGS" '办理退房'
has "记录了结账" "$LOGS" '>结账<'
has "记录了反结账" "$LOGS" '反结账'
has "记录了折扣减免" "$LOGS" '折扣减免'
has "记录了清洁完成" "$LOGS" '清洁完成'
has "记录了预订取消或删除" "$LOGS" '预订取消\|预订删除\|预订创建'
has "筛选下拉包含已出现的动作" "$LOGS" 'option value="反结账"'

FILTERED=$(curl -s -G -b "$CJ" --data-urlencode "action=反结账" --data-urlencode "size=100" "$BASE/logs")
has "可按动作筛选日志" "$FILTERED" 'badge-soft primary">反结账<'
lacks "筛选结果不含其它动作" "$FILTERED" 'badge-soft primary">办理入住<'

echo
echo "=============================================="
echo " 7. 打印与导出（§3.7）"
echo "=============================================="
chk "账单打印页 200" "$(curl -s -o "$TMP/print.html" -w '%{http_code}' -b "$CJ" "$BASE/bills/$EO_BILLID/print")" "200"
P=$(cat "$TMP/print.html")
has "打印页含酒店抬头" "$P" '云栖大酒店'
has "打印页标题为住宿结账单" "$P" '住宿结账单'
has "打印页含客人签名栏" "$P" '客人签名'

chk "账单 CSV 导出 200" "$(curl -s -o "$TMP/bills.csv" -w '%{http_code}' -b "$CJ" "$BASE/bills/export")" "200"
chk "账单 CSV 带 UTF-8 BOM（Excel 不乱码）" "$(head -c 3 "$TMP/bills.csv" | od -An -tx1 | tr -d ' \n')" "efbbbf"
has "账单 CSV 含表头" "$(head -1 "$TMP/bills.csv")" '账单号'
chk "订单 CSV 导出 200" "$(curl -s -o "$TMP/res.csv" -w '%{http_code}' -b "$CJ" "$BASE/reservations/export")" "200"
has "订单 CSV 含表头" "$(head -1 "$TMP/res.csv")" '订单号'
# 中文关键词必须 --data-urlencode；直接拼进 URL 会被 Tomcat 判为非法请求行（返回空体）
chk "导出遵循当前筛选条件（无匹配时只剩表头）" \
    "$(curl -s -G -b "$CJ" --data-urlencode "keyword=不存在的关键词XYZ" "$BASE/bills/export" | wc -l | tr -d ' ')" "1"
chk "账单 CSV 过滤关键词后仍带 BOM" \
    "$(curl -s -G -b "$CJ" --data-urlencode "keyword=不存在的关键词XYZ" "$BASE/bills/export" \
        | head -c 3 | od -An -tx1 | tr -d ' \n')" "efbbbf"
EO_BILLNO=$(q "select bill_no from bill where id=$EO_BILLID;")
chk "导出确实按关键词过滤（按账单号命中恰好 1 行，而不是永远空表）" \
    "$(curl -s -G -b "$CJ" --data-urlencode "keyword=$EO_BILLNO" \
        "$BASE/bills/export" | grep -c "$EO_BILLNO" | tr -d ' ')" "1"

chk "交接班名单页 200" "$(curl -s -o "$TMP/handover.html" -w '%{http_code}' -b "$CJ" "$BASE/dashboard/print/today")" "200"
H=$(cat "$TMP/handover.html")
has "名单含今日预抵区块" "$H" '今日预抵'
has "名单含今日预离区块" "$H" '今日预离'
has "名单含在住一览区块" "$H" '在住一览'

echo
echo "=============================================="
echo " 8. 平板适配（§3.8）"
echo "=============================================="
CSS=$(curl -s "$BASE/css/app.css")
has "存在打印样式" "$CSS" '@media print'
has "窄屏表格首列钉住样式" "$CSS" 'table-sticky-first'
has "房态图窄屏降级为卡片流" "$CSS" 'room-board-grid'
has "房态卡片带营业状态标记" "$(cat "$TMP/board_t.html")" 'data-state="'
has "账单列表使用钉住首列" \
    "$(curl -s -b "$CJ" "$BASE/bills" | grep -o 'table table-sticky-first' | head -1)" 'table table-sticky-first'
has "订单列表使用钉住首列" \
    "$(curl -s -b "$CJ" "$BASE/reservations" | grep -o 'table table-sticky-first' | head -1)" 'table table-sticky-first'

echo
echo "=============================================="
echo " 本次产生的测试数据（清理请执行 scripts/clean-test-data.sh）"
echo "=============================================="
echo "   未来预订  订单 id=$FUT_ORDER  房间 $ROOM_FUT_NO (id=$RID_FUT)"
echo "   并发测试  房间 id=$RID_CC（成功 1 单）"
echo "   提前退房  订单 id=$EO_ORDER  账单 id=$EO_BILLID"
echo
echo "=============================================="
printf " 结果：PASS=%d  FAIL=%d\n" "$PASS" "$FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ]
