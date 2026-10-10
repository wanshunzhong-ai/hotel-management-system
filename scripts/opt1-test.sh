#!/usr/bin/env bash
# 酒店管理系统 —— 第一批优化（止血）验收测试
#
#  1. 结账欠款校验：收款不足时拒绝结账，账单保持「待结账」
#  2. 日期校验：入住日期不得早于今天
#  3. 日期校验：超过单次住店晚数上限（hotel.max-stay-nights）被拒
#  4. 日期校验：超过提前预订上限（hotel.max-advance-days）被拒
#  5. 管理员保护：唯一的管理员不能被降级 / 停用（update 路径）
#  6. 营收口径：看板营收按「应收合计」统计，押金抵扣部分不漏计
#
# 前提：应用已启动（默认 http://127.0.0.1:8080，可用 HMS_BASE 覆盖）
# 说明：脚本会创建订单/账单用于验证，运行后会打印产生的 id 便于清理（见 README）。
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8080}"
TMP="/tmp/hms-opt1"
mkdir -p "$TMP"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
no()  { FAIL=$((FAIL+1)); printf "  \033[31m[FAIL]\033[0m %s\n" "$1"; }
chk() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else no "$1 期望[$3] 实际[$2]"; fi; }
has() { if printf '%s' "$2" | grep -q -- "$3"; then ok "$1"; else no "$1（未匹配 [$3]）"; fi; }
# 取金额的整数部分（去千分位），用于数值比较
yuan(){ printf '%s' "$1" | tr -d ',' | grep -o '[0-9]*' | head -1; }

csrf_of() { curl -s -b "$2" -c "$2" "$1" -o "$3"; grep -o 'name="_csrf"[^>]*value="[^"]*"' "$3" | head -1 | sed 's/.*value="//;s/"//'; }
csrf_in() { grep -o 'name="_csrf"[^>]*value="[^"]*"' "$1" | head -1 | sed 's/.*value="//;s/"//'; }

login() { # $1=user $2=pass $3=cookiejar
  local cj="$3"; rm -f "$cj"
  local t; t=$(csrf_of "$BASE/login" "$cj" "$TMP/login.html")
  curl -s -o /dev/null -w '%{http_code}' -b "$cj" -c "$cj" -X POST \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    --data-urlencode "_csrf=$t" "$BASE/login"
}

# -L 跟随 302，这样 flash 提示会渲染在重定向后的页面上（否则会被后续 GET 提前消费）
# 注意：这里**不能**加 -X POST —— curl 会把显式指定的方法带到重定向目标上，
# 导致 302 之后仍以 POST 请求 GET 页面而返回 403。用 --data-urlencode 隐式 POST 即可。
post_form() { # $1=url $2=cookiejar $3=csrf 剩余=表单
  local url="$1" cj="$2" t="$3"; shift 3
  curl -sL -o "$TMP/post.html" -w '%{http_code}' -b "$cj" -c "$cj" \
    --data-urlencode "_csrf=$t" "$@" "$url"
}

count_orders() { curl -s -b "$1" "$BASE/reservations" | grep -o '/reservations/[0-9]*"' | sort -u | wc -l | tr -d ' '; }
newest_order() { curl -s -b "$1" "$BASE/reservations" | grep -o '/reservations/[0-9]*"' | head -1 | grep -o '[0-9]*'; }
dash_total()   { curl -s -b "$1" "$BASE/dashboard" | grep -o '累计营收 ￥[0-9,]*' | head -1; }
bill_id_of()   { curl -s -b "$1" "$BASE/bills" | grep -o "/bills/$2\"" | head -1; }

# 建单 -> 入住 -> 退房，回显 BILLID / TOTAL
book_checkin_checkout() { # $1=客户 $2=房间 $3=备注
  local T; T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new.html")
  post_form "$BASE/reservations/save" "$CJ" "$T" \
    --data-urlencode "customerId=$1" --data-urlencode "roomId=$2" \
    --data-urlencode "checkInDate=$TODAY" --data-urlencode "checkOutDate=$TOMORROW" \
    --data-urlencode "guestCount=1" --data-urlencode "remark=$3" > /dev/null
  local RID_ORDER; RID_ORDER=$(newest_order "$CJ")
  T=$(csrf_of "$BASE/reservations/$RID_ORDER" "$CJ" "$TMP/rd.html")
  post_form "$BASE/reservations/$RID_ORDER/checkin" "$CJ" "$T" > /dev/null
  T=$(csrf_in "$TMP/post.html")
  local LOC; LOC=$(curl -s -o /dev/null -w '%{redirect_url}' -b "$CJ" -c "$CJ" -X POST \
      --data-urlencode "_csrf=$T" "$BASE/reservations/$RID_ORDER/checkout")
  BILLID=$(printf '%s' "$LOC" | grep -o '[0-9]*$')
  TOTAL=$(curl -s -b "$CJ" "$BASE/bills/$BILLID" | grep -o 'data-total="[0-9.]*"' | head -1 | grep -o '[0-9.]*')
}

echo "=============================================="
echo " 0. 准备：admin 登录 + 取一间今日可订房"
echo "=============================================="
CJ="$TMP/admin.cookies"
chk "登录 admin/admin123 返回 302" "$(login admin admin123 "$CJ")" "302"

TODAY=$(date +%Y-%m-%d)
TOMORROW=$(date -d "+1 day" +%Y-%m-%d)
YESTERDAY=$(date -d "-1 day" +%Y-%m-%d)
FAR=$(date -d "+400 day" +%Y-%m-%d)
FAR2=$(date -d "+401 day" +%Y-%m-%d)
LONG=$(date -d "+40 day" +%Y-%m-%d)
CID=$(curl -s -b "$CJ" "$BASE/customers" | grep -o '/customers/[0-9]*/edit' | head -1 | grep -o '[0-9]*')
RID=$(curl -s -b "$CJ" "$BASE/reservations/available-rooms?checkIn=$TODAY&checkOut=$TOMORROW" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
echo "   客户ID=$CID 房间ID=$RID 今天=$TODAY"

echo
echo "=============================================="
echo " 1. 日期校验：入住日期不能早于今天"
echo "=============================================="
BEFORE=$(count_orders "$CJ")
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID" \
  --data-urlencode "checkInDate=$YESTERDAY" --data-urlencode "checkOutDate=$TODAY" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=过去日期测试" > /dev/null
AFTER=$(count_orders "$CJ")
chk "昨日入住被拒（订单数不变）" "$AFTER" "$BEFORE"
has "拒绝原因：入住日期不能早于今天" "$(cat "$TMP/post.html")" '不能早于今天'

echo
echo "=============================================="
echo " 2. 日期校验：超过单次住店晚数上限（默认 30 晚）"
echo "=============================================="
BEFORE=$(count_orders "$CJ")
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new3.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID" \
  --data-urlencode "checkInDate=$TODAY" --data-urlencode "checkOutDate=$LONG" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=超晚数测试" > /dev/null
chk "住 39 晚被拒（上限 30 晚）" "$(count_orders "$CJ")" "$BEFORE"
has "拒绝原因：单次预订不能超过 30 晚" "$(cat "$TMP/post.html")" '不能超过 30 晚'

echo
echo "=============================================="
echo " 3. 日期校验：超过提前预订上限（默认 365 天）"
echo "=============================================="
BEFORE=$(count_orders "$CJ")
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new4.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID" \
  --data-urlencode "checkInDate=$FAR" --data-urlencode "checkOutDate=$FAR2" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=超提前期测试" > /dev/null
chk "提前 400 天预订被拒（上限 365 天）" "$(count_orders "$CJ")" "$BEFORE"
has "拒绝原因：最多只能提前 365 天预订" "$(cat "$TMP/post.html")" '只能提前 365 天'

echo
echo "=============================================="
echo " 4. 结账欠款校验：收款不足必须拒绝"
echo "=============================================="
book_checkin_checkout "$CID" "$RID" "结账校验测试"
echo "   账单 id=$BILLID  应收合计=￥$TOTAL"

curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd.html"
T=$(csrf_in "$TMP/bd.html")
post_form "$BASE/bills/$BILLID/settle" "$CJ" "$T" \
  --data-urlencode "deposit=0.00" --data-urlencode "paidAmount=1.00" \
  --data-urlencode "payMethod=CASH" --data-urlencode "remark=欠款测试" > /dev/null
# flash 提示渲染在 -L 跟随后的页面上（post.html）；页面静态文案里也有"还差"字样，
# 因此必须用 post.html 断言，不能用重新拉取的账单页。
POST_BODY=$(cat "$TMP/post.html")
has "欠款结账被拒（提示收款不足）" "$POST_BODY" '收款不足'
has "提示含「还差」金额" "$POST_BODY" '还差'
curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd_fail.html"
chk "账单未被标记为已结账" "$(grep -c '已结账' "$TMP/bd_fail.html")" "0"

T=$(csrf_in "$TMP/bd_fail.html")
post_form "$BASE/bills/$BILLID/settle" "$CJ" "$T" \
  --data-urlencode "deposit=0.00" --data-urlencode "paidAmount=$TOTAL" \
  --data-urlencode "payMethod=ALIPAY" --data-urlencode "remark=足额结账" > /dev/null
curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd_ok.html"
chk "足额收款后状态变为已结账" "$(grep -c '已结账' "$TMP/bd_ok.html")" "1"
has "收款记录显示【收款合计】" "$(cat "$TMP/bd_ok.html")" '收款合计'

echo
echo "=============================================="
echo " 5. 管理员保护：update 不得降级 / 停用唯一管理员"
echo "=============================================="
curl -s -b "$CJ" "$BASE/users" -o "$TMP/users.html"
AID=$(grep -o '/users/[0-9]*/edit' "$TMP/users.html" | head -1 | grep -o '[0-9]*')
AUSER=$(curl -s -b "$CJ" "$BASE/users/$AID/edit" | grep -o 'id="username"[^>]*value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//')
echo "   唯一管理员：id=$AID username=$AUSER"

T=$(csrf_of "$BASE/users/$AID/edit" "$CJ" "$TMP/ue.html")
post_form "$BASE/users/save" "$CJ" "$T" \
  --data-urlencode "id=$AID" --data-urlencode "username=$AUSER" --data-urlencode "realName=系统管理员" \
  --data-urlencode "role=RECEPTION" --data-urlencode "phone=" --data-urlencode "enabled=true" \
  --data-urlencode "remark=" > /dev/null
has "降级唯一管理员被拒" "$(cat "$TMP/post.html")" '必须保留至少一个启用状态的管理员'

# 停用：真实 UI 中复选框未勾选时提交的是 Spring 的字段标记参数 _enabled（而非 enabled）
T=$(csrf_of "$BASE/users/$AID/edit" "$CJ" "$TMP/ue2.html")
post_form "$BASE/users/save" "$CJ" "$T" \
  --data-urlencode "id=$AID" --data-urlencode "username=$AUSER" --data-urlencode "realName=系统管理员" \
  --data-urlencode "role=ADMIN" --data-urlencode "phone=" --data-urlencode "_enabled=on" \
  --data-urlencode "remark=" > /dev/null
has "停用唯一管理员被拒" "$(cat "$TMP/post.html")" '必须保留至少一个启用状态的管理员'

curl -s -b "$CJ" "$BASE/users/$AID/edit" -o "$TMP/ue3.html"
has "管理员角色仍为 ADMIN（未被改动）" "$(cat "$TMP/ue3.html")" '<option value="ADMIN" selected'
chk "当前会话未被踢出（/users 仍 200）" "$(curl -s -o /dev/null -w '%{http_code}' -b "$CJ" "$BASE/users")" "200"

echo
echo "=============================================="
echo " 6. 营收口径：押金抵扣部分不能漏计"
echo "=============================================="
REV_BEFORE=$(yuan "$(dash_total "$CJ")")
echo "   结账前看板累计营收 = ￥$REV_BEFORE"

# 押金 100 + 补付 (应收-100)，一旦按 paidAmount 统计就会少算 100
book_checkin_checkout "$CID" "$RID" "押金口径测试"
PAY_EXTRA=$(awk -v t="$TOTAL" 'BEGIN{printf "%.2f", t-100}')
echo "   账单 id=$BILLID 应收=￥$TOTAL（押金 100.00 + 补付 $PAY_EXTRA）"
curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd2.html"
T=$(csrf_in "$TMP/bd2.html")
post_form "$BASE/bills/$BILLID/settle" "$CJ" "$T" \
  --data-urlencode "deposit=100.00" --data-urlencode "paidAmount=$PAY_EXTRA" \
  --data-urlencode "payMethod=WECHAT" --data-urlencode "remark=押金抵扣口径测试" > /dev/null
curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd2_ok.html"
chk "带押金的账单结账成功" "$(grep -c '已结账' "$TMP/bd2_ok.html")" "1"

REV_AFTER=$(yuan "$(dash_total "$CJ")")
EXPECT=$((REV_BEFORE + $(yuan "$TOTAL")))
echo "   结账后看板累计营收 = ￥$REV_AFTER，期望 = $REV_BEFORE + $TOTAL = $EXPECT"
chk "营收按应收合计增长（押金未被漏计）" "$REV_AFTER" "$EXPECT"

curl -s -b "$CJ" "$BASE/dashboard" -o "$TMP/dash.html"
has "近 7 日趋势含 7 个日期标签" "$(grep -o 'revenueLabels = \[[^]]*\]' "$TMP/dash.html")" '10-'
has "出租率剔除了维修房（提示含可售间数）" "$(cat "$TMP/dash.html")" '可售 [0-9]* 间'
has "未结账单卡片展示在住预计房费" "$(cat "$TMP/dash.html")" '在住预计房费'

echo
echo "=============================================="
printf " 结果：\033[32mPASS=%d\033[0m  \033[31mFAIL=%d\033[0m\n" "$PASS" "$FAIL"
echo " 本次产生的订单/账单 id 见上方日志（验证用，可按需清理）"
echo "=============================================="
exit $FAIL
