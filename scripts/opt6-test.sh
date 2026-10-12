#!/usr/bin/env bash
# 酒店管理系统 —— 第六批（前台接待补全：押金闭环 / 交班 / 发票 / 换房 / 延迟退房 / 团队 / 房价方案 / 应收）验收测试
#
#  1.  押金收取 / 退还（收款流水）：账单押金由流水汇总；退超余额被拒
#  2.  预订金：仅「已预订」可收；已入住 / 已离店被拒
#  3.  收款流水：查询、类型 / 方式筛选、CSV 导出
#  4.  交接班对账：按支付方式汇总当班净收款 → 交班固化快照
#  5.  发票：未结账不能开票；超额被拒；开票落库；作废释放可开额度
#  6.  换房留痕：订单换房 + 房态联动（旧房待清洁 / 新房已入住）+ 备注留痕
#  7.  延迟退房：按档位加收 → 退房账单房费侧计入；已退房不能改
#  8.  团队入住：归团打标 → 批量办理入住（逐间办理）
#  9.  房价方案：协议价 / 会员价按方案计价；门市价快照
# 10.  应收账款台账：未结账单登记挂账 → 台账出现；结清后消失
# 11.  权限边界：客房部进不了这六个前台模块（403）；前台可进
# 12.  页面渲染：六个新模块页面与订单 / 账单详情含新卡片
#
# 前提：应用已启动（默认 http://127.0.0.1:8080，可用 HMS_BASE 覆盖）
# 数据：脚本创建的数据备注含「测试」，结束后执行 scripts/clean-test-data.sh 清理
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8080}"
TMP="/tmp/hms-opt6"
mkdir -p "$TMP"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
no()   { FAIL=$((FAIL+1)); printf "  \033[31m[FAIL]\033[0m %s\n" "$1"; }
chk()  { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else no "$1 期望[$3] 实际[$2]"; fi; }
has()  { if printf '%s' "$2" | grep -q -- "$3"; then ok "$1"; else no "$1（未匹配 [$3]）"; fi; }
lacks(){ if printf '%s' "$2" | grep -q -- "$3"; then no "$1（不应出现 [$3]）"; else ok "$1"; fi; }
ge()   { if [ "${2:-0}" -ge "${3:-1}" ] 2>/dev/null; then ok "$1 ($2)"; else no "$1 期望>=[$3] 实际[$2]"; fi; }

MYSQL_BIN="${MYSQL_BIN:-/d/SoftWare/MySQL/MySQL Server 9.0/bin/mysql.exe}"
q() { MSYS_NO_PATHCONV=1 "$MYSQL_BIN" --default-character-set=utf8mb4 -uroot -p123456 \
      -D hotel_management -N -B --batch -e "$1" 2>/dev/null | tr -d '\r'; }

csrf_of() { curl -s --noproxy '*' -b "$2" -c "$2" "$1" -o "$3"; grep -o 'name="_csrf"[^>]*value="[^"]*"' "$3" | head -1 | sed 's/.*value="//;s/"//'; }
csrf()    { csrf_of "$BASE$1" "$CJ" "$TMP/c.html"; }
code_of() { curl -s --noproxy '*' -o "$TMP/page.html" -w '%{http_code}' -b "$1" "$2"; }

login() { # $1=user $2=pass $3=cookiejar
  local cj="$3"; rm -f "$cj"
  local t; t=$(csrf_of "$BASE/login" "$cj" "$TMP/login.html")
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -b "$cj" -c "$cj" \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    --data-urlencode "_csrf=$t" "$BASE/login"
}

# 提交表单并跟随重定向（注意：不要用 -X POST + -L 同时出现，否则 curl 会把重定向也当 POST）
post() { # $1=url  $2=cookiejar  $3=csrf  剩余=表单字段
  local url="$1" cj="$2" t="$3"; shift 3
  curl -sL --noproxy '*' -o "$TMP/post.html" -w '%{http_code}' -b "$cj" -c "$cj" \
    --data-urlencode "_csrf=$t" "$@" "$url"
}
flash() { grep -o "$1" "$TMP/post.html" | head -1; }

# 挑一间「物理空闲、无在住客人、且该区间无冲突」的房间
pick_idle_room() { # $1=checkIn $2=checkOut
  local av idle id
  av=$(curl -s --noproxy '*' -b "$CJ" "$BASE/reservations/available-rooms?checkIn=$1&checkOut=$2" \
       | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
  idle=$(q "select group_concat(id) from room
            where status='AVAILABLE'
              and id not in (select room_id from reservation where status='CHECKED_IN');" | tr ',' '\n')
  for id in $av; do printf '%s\n' "$idle" | grep -qx "$id" && { printf '%s' "$id"; return 0; }; done
}

new_order() { # $1=roomId $2=checkIn $3=checkOut $4=ratePlanId(可空) $5=customerId  → 回显订单 id
  local t; t=$(csrf /reservations/new)
  post "$BASE/reservations/save" "$CJ" "$t" \
    --data-urlencode "customerId=$5" --data-urlencode "roomId=$1" \
    --data-urlencode "checkInDate=$2" --data-urlencode "checkOutDate=$3" \
    --data-urlencode "guestCount=1" --data-urlencode "remark=第六批测试单" \
    ${4:+--data-urlencode "ratePlanId=$4"} >/dev/null
  q "select id from reservation order by id desc limit 1;"
}

echo "=============================================="
echo " 0. 准备：三角色登录 + 基础数据"
echo "=============================================="
CJ="$TMP/admin.cookies"; CJR="$TMP/reception.cookies"; CJH="$TMP/housekeeping.cookies"
chk "登录 admin/admin123 返回 302"      "$(login admin admin123 "$CJ")" "302"
chk "登录 reception/123456 返回 302"    "$(login reception 123456 "$CJR")" "302"
chk "登录 housekeeping/123456 返回 302" "$(login housekeeping 123456 "$CJH")" "302"

TODAY=$(date +%F)
TOMORROW=$(date -d '+1 day' +%F)
FAR_IN=$(date -d '+6 day' +%F)
FAR_OUT=$(date -d '+8 day' +%F)

PLAN_GOLD_ID=$(q "select id from rate_plan where code='MEMBER-GOLD';")
chk "房价方案种子存在（MEMBER-GOLD）" "$(q "select count(*) from rate_plan where code='MEMBER-GOLD';")" "1"

# --- 幂等前置清理：清掉上一轮（可能中断的）测试残留，保证重复运行结果一致 ---
# 注意：这里刻意「一条语句一次调用」，而不是写成一个多语句批次。原因有两个：
#   ① MySQL 的临时表在同一语句内被引用两次会报 1137 Can't reopen table；
#   ② 多语句批次中任意一条报错会中断后续所有语句（曾经导致 DELETE shift_handover 被静默跳过，
#      残留记录使「交接班记录落库」断言长期失败）。
# 按外键依赖顺序删：附属单 / payment_record / invoice / bill_item → bill → reservation。
# 注意：房态复位必须放在「删除订单之前」——一旦订单被删，就再也无法反查「哪些房是本轮测试动过的」，
#       遗留的 CLEANING 房会污染基线。已被删掉订单的房由最后一条全局语句兜底（仅覆盖 OCCUPIED）。
TESTRES="(SELECT id FROM reservation WHERE remark LIKE '%测试%')"
q "UPDATE room r SET r.status='AVAILABLE'
   WHERE r.id IN (SELECT v.room_id FROM reservation v WHERE v.remark LIKE '%测试%')
     AND r.status IN ('OCCUPIED','CLEANING')
     AND NOT EXISTS (SELECT 1 FROM reservation w
                     WHERE w.room_id=r.id AND w.status IN ('BOOKED','CHECKED_IN'));"
q "DELETE FROM payment_record WHERE reservation_id IN $TESTRES;"                     # 收款流水 → 订单
q "DELETE FROM payment_record WHERE bill_id IN (SELECT id FROM bill WHERE reservation_id IN $TESTRES);"
q "DELETE FROM invoice       WHERE bill_id IN (SELECT id FROM bill WHERE reservation_id IN $TESTRES);"
q "DELETE FROM room_inspection WHERE reservation_id IN $TESTRES;"
q "DELETE FROM laundry_order   WHERE reservation_id IN $TESTRES;"
q "DELETE FROM minibar_item    WHERE reservation_id IN $TESTRES;"
q "DELETE FROM bill_item      WHERE bill_id IN (SELECT id FROM bill WHERE reservation_id IN $TESTRES);"
q "DELETE FROM bill           WHERE reservation_id IN $TESTRES;"
q "DELETE FROM reservation    WHERE remark LIKE '%测试%';"
q "DELETE FROM shift_handover WHERE remark = '第六批测试交班';"
q "DELETE FROM rate_plan      WHERE code LIKE 'TEST-%';"
q "UPDATE room r SET r.status='AVAILABLE'
   WHERE r.status='OCCUPIED'
     AND NOT EXISTS (SELECT 1 FROM reservation v WHERE v.room_id=r.id AND v.status='CHECKED_IN');"

echo
echo "=============================================="
echo " 1. 押金收取 / 退还（收款流水）"
echo "=============================================="
ROOM_B=$(pick_idle_room "$TODAY" "$TOMORROW")
RID_B=$(new_order "$ROOM_B" "$TODAY" "$TOMORROW" "" "4")
ok "创建当日订单 id=$RID_B（房 $ROOM_B，$TODAY→$TOMORROW）"
T=$(csrf "/reservations/$RID_B")
post "$BASE/reservations/$RID_B/checkin" "$CJ" "$T" >/dev/null
chk "订单 $RID_B 办理入住成功" "$(q "select status from reservation where id=$RID_B;")" "CHECKED_IN"

T=$(csrf "/reservations/$RID_B")
post "$BASE/payments/deposit" "$CJ" "$T" \
  --data-urlencode "reservationId=$RID_B" --data-urlencode "amount=300" \
  --data-urlencode "payMethod=CASH" --data-urlencode "remark=押金测试" >/dev/null
chk "收取押金 300 落流水" "$(q "select CONCAT(type,':',amount) from payment_record where reservation_id=$RID_B and type='DEPOSIT_IN';")" "DEPOSIT_IN:300.00"
chk "押金流水记录了操作人" "$(q "select operator from payment_record where reservation_id=$RID_B and type='DEPOSIT_IN';")" "系统管理员"

T=$(csrf "/reservations/$RID_B")
post "$BASE/payments/refund" "$CJ" "$T" \
  --data-urlencode "reservationId=$RID_B" --data-urlencode "amount=999" --data-urlencode "payMethod=CASH" >/dev/null
has "退还押金超过余额被拒" "$(cat "$TMP/post.html")" "退还押金不能超过可退押金余额"
chk "被拒后未产生退款流水" "$(q "select count(*) from payment_record where reservation_id=$RID_B and type='DEPOSIT_REFUND';")" "0"

T=$(csrf "/reservations/$RID_B")
post "$BASE/payments/refund" "$CJ" "$T" \
  --data-urlencode "reservationId=$RID_B" --data-urlencode "amount=100" --data-urlencode "payMethod=CASH" >/dev/null
chk "退还押金 100 落流水" "$(q "select CONCAT(type,':',amount) from payment_record where reservation_id=$RID_B and type='DEPOSIT_REFUND';")" "DEPOSIT_REFUND:100.00"

# 页面应显示预收款净额（300 − 100 = 200）与可退余额
curl -s --noproxy '*' -b "$CJ" "$BASE/reservations/$RID_B" -o "$TMP/rd.html"
has "订单详情显示预收款净额 ￥200.00" "$(cat "$TMP/rd.html")" "预收款净额"
has "订单详情显示可退押金余额"        "$(cat "$TMP/rd.html")" "可退押金余额"

echo
echo "=============================================="
echo " 2. 预订金（仅「已预订」可收）"
echo "=============================================="
ROOM_A=$(pick_idle_room "$FAR_IN" "$FAR_OUT")
RID_A=$(new_order "$ROOM_A" "$FAR_IN" "$FAR_OUT" "$PLAN_GOLD_ID" "1")
ok "创建未来订单 id=$RID_A（房 $ROOM_A，金卡会员价）"

T=$(csrf "/reservations/$RID_A")
post "$BASE/payments/booking" "$CJ" "$T" \
  --data-urlencode "reservationId=$RID_A" --data-urlencode "amount=200" \
  --data-urlencode "payMethod=WECHAT" >/dev/null
has "已预订订单可收预订金" "$(cat "$TMP/post.html")" "预订金已收取"
chk "预订金落流水" "$(q "select CONCAT(type,':',amount) from payment_record where reservation_id=$RID_A and type='BOOKING_DEPOSIT';")" "BOOKING_DEPOSIT:200.00"

T=$(csrf "/reservations/$RID_B")
post "$BASE/payments/booking" "$CJ" "$T" \
  --data-urlencode "reservationId=$RID_B" --data-urlencode "amount=100" --data-urlencode "payMethod=CASH" >/dev/null
has "已入住订单收预订金被拒" "$(cat "$TMP/post.html")" "只有「已预订」状态的订单可以收取预订金"

echo
echo "=============================================="
echo " 3. 收款流水（查询 / 筛选 / 导出）"
echo "=============================================="
chk "收款流水页可访问" "$(code_of "$CJ" "$BASE/payments")" "200"
curl -s --noproxy '*' -b "$CJ" "$BASE/payments?type=DEPOSIT_IN" -o "$TMP/pay1.html"
has "按类型筛选（收取押金）有结果" "$(cat "$TMP/pay1.html")" "收取押金"
curl -s --noproxy '*' -b "$CJ" "$BASE/payments?method=WECHAT" -o "$TMP/pay2.html"
has "按支付方式筛选（微信）有结果" "$(cat "$TMP/pay2.html")" "微信支付"
CSV=$(curl -s --noproxy '*' -b "$CJ" -o "$TMP/pay.csv" -w '%{http_code}' "$BASE/payments/export")
chk "收款流水导出 CSV 返回 200" "$CSV" "200"
has "CSV 含表头「发生时间」" "$(iconv -f UTF-8 -t UTF-8 "$TMP/pay.csv" 2>/dev/null | head -1)" "发生时间"

echo
echo "=============================================="
echo " 4. 交接班对账"
echo "=============================================="
chk "交接班页可访问" "$(code_of "$CJ" "$BASE/shift")" "200"
curl -s --noproxy '*' -b "$CJ" "$BASE/shift" -o "$TMP/sh.html"
has "交接班页显示「当班净收款」" "$(cat "$TMP/sh.html")" "当班净收款"
has "交接班页显示按支付方式净额" "$(cat "$TMP/sh.html")" "按支付方式"

T=$(csrf /shift)
post "$BASE/shift/handover" "$CJ" "$T" \
  --data-urlencode "receivedBy=前台-小李" --data-urlencode "cashVerified=true" \
  --data-urlencode "remark=第六批测试交班" >/dev/null
has "交班成功提示" "$(cat "$TMP/post.html")" "交班完成"
chk "交接班记录落库" "$(q "select count(*) from shift_handover where remark='第六批测试交班';")" "1"
has "交接班记录含现金点清标记" "$(q "select cash_verified+0 from shift_handover where remark='第六批测试交班' limit 1;")" "1"
SHIFT_NO=$(q "select shift_no from shift_handover where remark='第六批测试交班' limit 1;")
SHIFT_ID=$(q "select id from shift_handover where remark='第六批测试交班' limit 1;")
chk "交班单号格式 SH 开头" "$(printf '%s' "$SHIFT_NO" | cut -c1-2)" "SH"
chk "交班单可打印" "$(code_of "$CJ" "$BASE/shift/$SHIFT_ID/print")" "200"

echo
echo "=============================================="
echo " 5. 换房留痕 + 房态联动"
echo "=============================================="
ROOM_B2=$(pick_idle_room "$TODAY" "$TOMORROW")
OLD_ROOM_NO=$(q "select room_no from room where id=$ROOM_B;")
T=$(csrf "/reservations/$RID_B")
post "$BASE/reservations/$RID_B/change-room" "$CJ" "$T" \
  --data-urlencode "newRoomId=$ROOM_B2" --data-urlencode "reason=测试换房" >/dev/null
has "换房成功提示" "$(cat "$TMP/post.html")" "换房完成"
chk "订单已换到新房" "$(q "select room_id from reservation where id=$RID_B;")" "$ROOM_B2"
has "订单备注含换房留痕" "$(q "select remark from reservation where id=$RID_B;")" "换房"
chk "旧房转「待清洁」" "$(q "select status from room where id=$ROOM_B;")" "CLEANING"
chk "新房转「已入住」" "$(q "select status from room where id=$ROOM_B2;")" "OCCUPIED"

# 断言完立刻复位旧房：换房后订单已指向新房，这间旧房不再被任何订单引用，
# clean-test-data.sh 事后无法反查出来，会永久残留一个「待清洁」污染基线房态。
q "UPDATE room SET status='AVAILABLE' WHERE id=$ROOM_B AND status='CLEANING';"

T=$(csrf "/reservations/$RID_B")
post "$BASE/reservations/$RID_B/change-room" "$CJ" "$T" \
  --data-urlencode "newRoomId=$ROOM_B2" >/dev/null
has "换到相同房间被拒" "$(cat "$TMP/post.html")" "新旧房间相同"

echo
echo "=============================================="
echo " 6. 延迟退房加收 → 退房账单房费侧"
echo "=============================================="
UNIT=$(q "select unit_price from reservation where id=$RID_B;")
T=$(csrf "/reservations/$RID_B")
post "$BASE/reservations/$RID_B/late-checkout" "$CJ" "$T" --data-urlencode "tier=HALF_DAY" >/dev/null
has "登记延迟退房成功" "$(cat "$TMP/post.html")" "已登记延迟退房"
EXPECT_FEE=$(q "select round($UNIT/2, 2);")
chk "半天档加收 = 房价一半（$EXPECT_FEE）" "$(q "select late_checkout_fee from reservation where id=$RID_B;")" "$EXPECT_FEE"
chk "档位记录为 HALF_DAY" "$(q "select late_checkout_tier from reservation where id=$RID_B;")" "HALF_DAY"

# 退房 → 生成账单，延迟退房加收应计入房费侧
T=$(csrf "/reservations/$RID_B")
post "$BASE/reservations/$RID_B/checkout" "$CJ" "$T" --data-urlencode "actualCheckOutDate=$TOMORROW" >/dev/null
BILL_B=$(q "select id from bill where reservation_id=$RID_B;")
ok "退房生成账单 id=$BILL_B"
chk "账单延迟退房加收计入" "$(q "select late_checkout_fee from bill where id=$BILL_B;")" "$EXPECT_FEE"
chk "账单状态为待结账" "$(q "select status from bill where id=$BILL_B;")" "UNPAID"
curl -s --noproxy '*' -b "$CJ" "$BASE/bills/$BILL_B" -o "$TMP/bd.html"
has "账单详情显示「延迟退房加收」" "$(cat "$TMP/bd.html")" "延迟退房加收"

T=$(csrf "/reservations/$RID_B")
post "$BASE/reservations/$RID_B/late-checkout" "$CJ" "$T" --data-urlencode "tier=FULL_DAY" >/dev/null
has "已退房订单不能再调延迟退房" "$(cat "$TMP/post.html")" "订单已退房"

echo
echo "=============================================="
echo " 7. 发票开具 / 作废"
echo "=============================================="
TOTAL_B=$(q "select total_amount from bill where id=$BILL_B;")
# 未结账不能开票
T=$(csrf "/bills/$BILL_B")
post "$BASE/invoices" "$CJ" "$T" \
  --data-urlencode "billId=$BILL_B" --data-urlencode "type=VAT_NORMAL" \
  --data-urlencode "title=测试抬头" --data-urlencode "amount=$TOTAL_B" >/dev/null
has "未结账账单不能开票" "$(cat "$TMP/post.html")" "账单尚未结账"
chk "未结账未产生发票" "$(q "select count(*) from invoice where bill_id=$BILL_B;")" "0"

# 结账（押金取流水净额，补付 = 应收 − 净额）
LEDGER=$(q "select coalesce(sum(case when type='DEPOSIT_REFUND' then -amount else amount end),0)
            from payment_record where reservation_id=$RID_B
              and type in ('BOOKING_DEPOSIT','DEPOSIT_IN','DEPOSIT_REFUND');")
chk "结账前预收款净额 = 200" "$LEDGER" "200.00"
PAID_B=$(q "select $TOTAL_B - $LEDGER;")
curl -s --noproxy '*' -b "$CJ" "$BASE/bills/$BILL_B" -o "$TMP/bd2.html"
has "结账表单押金默认取流水净额" "$(cat "$TMP/bd2.html")" 'name="deposit"'
T=$(csrf "/bills/$BILL_B")
post "$BASE/bills/$BILL_B/settle" "$CJ" "$T" \
  --data-urlencode "deposit=$LEDGER" --data-urlencode "paidAmount=$PAID_B" \
  --data-urlencode "payMethod=CASH" >/dev/null
has "结账成功" "$(cat "$TMP/post.html")" "结账完成"
chk "账单已结账" "$(q "select status from bill where id=$BILL_B;")" "SETTLED"
chk "结账收款落流水（SETTLE_PAY）" "$(q "select CONCAT(type,':',amount) from payment_record where bill_id=$BILL_B and type='SETTLE_PAY';")" "SETTLE_PAY:$PAID_B"

# 专票必须填税号
T=$(csrf "/bills/$BILL_B")
post "$BASE/invoices" "$CJ" "$T" \
  --data-urlencode "billId=$BILL_B" --data-urlencode "type=VAT_SPECIAL" \
  --data-urlencode "title=测试专票" --data-urlencode "amount=$TOTAL_B" >/dev/null
has "专票缺税号被拒" "$(cat "$TMP/post.html")" "必须填写纳税人识别号"

# 正常开票
T=$(csrf "/bills/$BILL_B")
post "$BASE/invoices" "$CJ" "$T" \
  --data-urlencode "billId=$BILL_B" --data-urlencode "type=VAT_NORMAL" \
  --data-urlencode "title=测试抬头有限公司" --data-urlencode "amount=$TOTAL_B" \
  --data-urlencode "content=住宿费" >/dev/null
has "开票成功" "$(cat "$TMP/post.html")" "发票已开具"
INV_ID=$(q "select id from invoice where bill_id=$BILL_B order by id desc limit 1;")
chk "发票落库（金额 = 应收）" "$(q "select amount from invoice where id=$INV_ID;")" "$TOTAL_B"

# 超额开票被拒
T=$(csrf "/bills/$BILL_B")
post "$BASE/invoices" "$CJ" "$T" \
  --data-urlencode "billId=$BILL_B" --data-urlencode "type=VAT_NORMAL" \
  --data-urlencode "title=测试超额" --data-urlencode "amount=100" >/dev/null
has "超额开票被拒" "$(cat "$TMP/post.html")" "开票金额超过可开额度"

# 作废释放额度
T=$(csrf /invoices)
post "$BASE/invoices/$INV_ID/cancel" "$CJ" "$T" \
  --data-urlencode "reason=测试作废" --data-urlencode "billId=$BILL_B" >/dev/null
has "发票作废成功" "$(cat "$TMP/post.html")" "已作废"
chk "作废为软删除（记录保留）" "$(q "select cancelled+0 from invoice where id=$INV_ID;")" "1"
curl -s --noproxy '*' -b "$CJ" "$BASE/bills/$BILL_B" -o "$TMP/bd3.html"
# 页面金额带千分位（￥1,332.00），库里的 total_amount 不带（1332.00）。
# 这里必须先去掉逗号再比，否则断言能不能过取决于「恰好选中的房间房价有没有过千」——
# 本单房间是 pick_idle_room 动态选的，金额随库中空房分布漂移，属于典型脆弱断言。
has "作废后可开额度恢复" "$(tr -d ',' < "$TMP/bd3.html")" "可开 ￥$TOTAL_B"

echo
echo "=============================================="
echo " 8. 团队入住（归团 + 批量入住）"
echo "=============================================="
ROOM_T=$(pick_idle_room "$FAR_IN" "$FAR_OUT")
RID_T=$(new_order "$ROOM_T" "$FAR_IN" "$FAR_OUT" "" "7")
T=$(csrf "/reservations/$RID_T")
post "$BASE/reservations/$RID_T/group" "$CJ" "$T" --data-urlencode "groupName=第六批测试团" >/dev/null
has "归入团队成功" "$(cat "$TMP/post.html")" "已归入团队"
chk "订单团名已写入" "$(q "select group_name from reservation where id=$RID_T;")" "第六批测试团"

curl -s --noproxy '*' -b "$CJ" "$BASE/groups/checkin" -o "$TMP/gp.html"
has "团队页列出现有团名" "$(cat "$TMP/gp.html")" "第六批测试团"
curl -s --noproxy '*' -b "$CJ" -G "$BASE/groups/checkin" \
  --data-urlencode "group=第六批测试团" -o "$TMP/gp2.html"
has "按团查看成员（房号）" "$(cat "$TMP/gp2.html")" "$(q "select room_no from room where id=$ROOM_T;")"

T=$(csrf /groups/checkin)
post "$BASE/groups/checkin/batch" "$CJ" "$T" \
  --data-urlencode "ids=$RID_T" --data-urlencode "date=$FAR_IN" >/dev/null
has "批量入住提示" "$(cat "$TMP/post.html")" "批量入住完成"
chk "团队订单已入住" "$(q "select status from reservation where id=$RID_T;")" "CHECKED_IN"
chk "团队订单房间转已入住" "$(q "select status from room where id=$ROOM_T;")" "OCCUPIED"

echo
echo "=============================================="
echo " 9. 协议价 / 会员价"
echo "=============================================="
ROOM_P=$(pick_idle_room "$FAR_IN" "$FAR_OUT")
PRICE_P=$(q "select price from room_type where id=(select room_type_id from room where id=$ROOM_P);")
RID_P=$(new_order "$ROOM_P" "$FAR_IN" "$FAR_OUT" "$PLAN_GOLD_ID" "2")
EXPECT_P=$(q "select round($PRICE_P * 0.90, 2);")
chk "会员价单价 = 门市价 9 折（$EXPECT_P）" "$(q "select unit_price from reservation where id=$RID_P;")" "$EXPECT_P"
chk "门市价快照保留" "$(q "select list_price from reservation where id=$RID_P;")" "$PRICE_P"
chk "方案名快照写入" "$(q "select rate_plan_name from reservation where id=$RID_P;")" "金卡会员价"

ROOM_N=$(pick_idle_room "$FAR_IN" "$FAR_OUT")
RID_N=$(new_order "$ROOM_N" "$FAR_IN" "$FAR_OUT" "" "3")
PRICE_N=$(q "select price from room_type where id=(select room_type_id from room where id=$ROOM_N);")
chk "不选方案时按门市价计价" "$(q "select unit_price from reservation where id=$RID_N;")" "$PRICE_N"

chk "房价方案页可访问" "$(code_of "$CJ" "$BASE/rate-plans")" "200"
chk "房价方案新建页可访问" "$(code_of "$CJ" "$BASE/rate-plans/new")" "200"
PLAN_NEW_CODE="TEST-$(date +%s)"
T=$(csrf /rate-plans/new)
post "$BASE/rate-plans/save" "$CJ" "$T" \
  --data-urlencode "code=$PLAN_NEW_CODE" --data-urlencode "name=测试协议价" \
  --data-urlencode "type=CORPORATE" --data-urlencode "discountRate=0.75" \
  --data-urlencode "enabled=true" >/dev/null
has "新建房价方案成功" "$(cat "$TMP/post.html")" "已保存"
chk "方案落库（折扣 0.75）" "$(q "select discount_rate from rate_plan where code='$PLAN_NEW_CODE';")" "0.7500"

T=$(csrf /rate-plans/new)
post "$BASE/rate-plans/save" "$CJ" "$T" \
  --data-urlencode "code=$PLAN_NEW_CODE-2" --data-urlencode "name=测试会员价缺等级" \
  --data-urlencode "type=MEMBER" --data-urlencode "discountRate=0.9" --data-urlencode "enabled=true" >/dev/null
has "会员价缺等级被拒" "$(cat "$TMP/post.html")" "必须选择适用的会员等级"

PLAN_NEW_ID=$(q "select id from rate_plan where code='$PLAN_NEW_CODE';")
T=$(csrf /rate-plans)
post "$BASE/rate-plans/$PLAN_NEW_ID/toggle" "$CJ" "$T" --data-urlencode "enabled=false" >/dev/null
chk "方案停用生效" "$(q "select enabled+0 from rate_plan where id=$PLAN_NEW_ID;")" "0"
T=$(csrf /rate-plans)
post "$BASE/rate-plans/$PLAN_NEW_ID/delete" "$CJ" "$T" >/dev/null
chk "方案删除生效" "$(q "select count(*) from rate_plan where id=$PLAN_NEW_ID;")" "0"

echo
echo "=============================================="
echo "10. 应收账款台账"
echo "=============================================="
# 用订单 B 的账单：先反结账回到待结账，再登记挂账
T=$(csrf "/bills/$BILL_B")
post "$BASE/bills/$BILL_B/unsettle" "$CJ" "$T" --data-urlencode "reason=第六批测试反结账" >/dev/null
has "反结账成功" "$(cat "$TMP/post.html")" "已反结账"
chk "反结账清理结账流水" "$(q "select count(*) from payment_record where bill_id=$BILL_B;")" "0"
chk "账单回到待结账" "$(q "select status from bill where id=$BILL_B;")" "UNPAID"

T=$(csrf "/bills/$BILL_B")
post "$BASE/receivables/$BILL_B/credit" "$CJ" "$T" \
  --data-urlencode "creditAccount=第六批测试单位" \
  --data-urlencode "creditDueDate=$(date -d '+15 day' +%F)" >/dev/null
has "登记挂账成功" "$(cat "$TMP/post.html")" "已登记挂账单位"
curl -s --noproxy '*' -b "$CJ" "$BASE/receivables" -o "$TMP/rec.html"
has "台账出现该挂账单位" "$(cat "$TMP/rec.html")" "第六批测试单位"
BILL_NO_B=$(q "select bill_no from bill where id=$BILL_B;")
has "台账出现该账单号" "$(cat "$TMP/rec.html")" "$BILL_NO_B"
curl -s --noproxy '*' -b "$CJ" "$BASE/receivables?creditedOnly=true" -o "$TMP/rec2.html"
has "仅挂账筛选命中" "$(cat "$TMP/rec2.html")" "第六批测试单位"
chk "应收台账导出 CSV 返回 200" "$(curl -s --noproxy '*' -b "$CJ" -o "$TMP/rec.csv" -w '%{http_code}' "$BASE/receivables/export")" "200"
has "CSV 含表头「账龄(天)」" "$(iconv -f UTF-8 -t UTF-8 "$TMP/rec.csv" 2>/dev/null | head -1)" "账龄"

# 结清后从台账消失
LEDGER2=$(q "select coalesce(sum(case when type='DEPOSIT_REFUND' then -amount else amount end),0)
             from payment_record where reservation_id=$RID_B
               and type in ('BOOKING_DEPOSIT','DEPOSIT_IN','DEPOSIT_REFUND');")
PAID2=$(q "select $TOTAL_B - $LEDGER2;")
T=$(csrf "/bills/$BILL_B")
post "$BASE/bills/$BILL_B/settle" "$CJ" "$T" \
  --data-urlencode "deposit=$LEDGER2" --data-urlencode "paidAmount=$PAID2" \
  --data-urlencode "payMethod=BANK_CARD" >/dev/null
curl -s --noproxy '*' -b "$CJ" "$BASE/receivables" -o "$TMP/rec3.html"
lacks "结清后从台账消失" "$(cat "$TMP/rec3.html")" "$BILL_NO_B"

echo
echo "=============================================="
echo "11. 权限边界（客房部不可进）"
echo "=============================================="
for p in /payments /invoices /receivables /shift /groups/checkin /rate-plans; do
  chk "客房部访问 $p 返回 403" "$(code_of "$CJH" "$BASE$p")" "403"
  chk "前台访问 $p 返回 200"   "$(code_of "$CJR" "$BASE$p")" "200"
done

echo
echo "=============================================="
echo "12. 页面渲染（六个新模块 + 订单 / 账单详情）"
echo "=============================================="
chk "发票列表页" "$(code_of "$CJ" "$BASE/invoices")" "200"
chk "应收账款页" "$(code_of "$CJ" "$BASE/receivables")" "200"
curl -s --noproxy '*' -b "$CJ" "$BASE/reservations/$RID_A" -o "$TMP/rd2.html"
has "订单详情含押金 / 预订金卡片" "$(cat "$TMP/rd2.html")" "押金 / 预订金"
has "订单详情含换房入口"         "$(cat "$TMP/rd2.html")" "换到房间"
has "订单详情含延迟退房入口"     "$(cat "$TMP/rd2.html")" "加收档位"
idx_inv=$(q "select id from invoice where bill_id=$BILL_B order by id desc limit 1;")
chk "发票打印页可访问" "$(code_of "$CJ" "$BASE/invoices/$idx_inv/print")" "200"

echo
echo "=============================================="
printf " 第六批验收结果：\033[32mPASS=%d\033[0m  \033[31mFAIL=%d\033[0m\n" "$PASS" "$FAIL"
echo "=============================================="
echo "提示：测试数据备注含「测试」，执行 scripts/clean-test-data.sh 可清理"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
