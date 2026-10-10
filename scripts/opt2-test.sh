#!/usr/bin/env bash
# 酒店管理系统 —— 第二批优化（前台交互）验收测试
#
#  1. 订单列表分页：每页条数生效、翻页内容不同、筛选条件在翻页时保留
#  2. 订单列表筛选：状态 / 入住日期区间 / 楼层
#  3. 订单列表排序：按入住日期升序
#  4. 账单列表：分页 + 只看待结账 + 结账日期区间
#  5. 客户列表：分页
#  6. 客户选择器接口：远程搜索 + 手机号前缀优先命中
#  7. 内联快速建档：成功 / 证件号重复 / 手机号格式非法
#  8. 预订页：不再全量渲染客户下拉，改为搜索选择器 + 内联新建弹窗
#
# 前提：应用已启动（默认 http://127.0.0.1:8080，可用 HMS_BASE 覆盖）
# 说明：脚本会新建 1 位测试客户用于验证，结束时自动清理。
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8080}"
MYSQL_BIN="${HMS_MYSQL:-mysql}"
DB="${HMS_DB:-hotel_management}"
TMP="/tmp/hms-opt2"
mkdir -p "$TMP"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
no()  { FAIL=$((FAIL+1)); printf "  \033[31m[FAIL]\033[0m %s\n" "$1"; }
chk() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else no "$1 期望[$3] 实际[$2]"; fi; }
ge()  { if [ "${2:-0}" -ge "${3:-0}" ] 2>/dev/null; then ok "$1 ($2)"; else no "$1 期望>=[$3] 实际[$2]"; fi; }
le()  { if [ "${2:-0}" -le "${3:-0}" ] 2>/dev/null; then ok "$1 ($2)"; else no "$1 期望<=[$3] 实际[$2]"; fi; }
has() { if printf '%s' "$2" | grep -q -- "$3"; then ok "$1"; else no "$1（未匹配 [$3]）"; fi; }
hasnt(){ if printf '%s' "$2" | grep -q -- "$3"; then no "$1（不应出现 [$3]）"; else ok "$1"; fi; }

csrf_of() { curl -s -b "$2" -c "$2" "$1" -o "$3"; grep -o 'name="_csrf"[^>]*value="[^"]*"' "$3" | head -1 | sed 's/.*value="//;s/"//'; }
csrf_in() { grep -o 'name="_csrf"[^>]*value="[^"]*"' "$1" | head -1 | sed 's/.*value="//;s/"//'; }
fetch()   { curl -s -b "$1" "$BASE$2"; }

login() { # $1=user $2=pass $3=cookiejar
  local cj="$3"; rm -f "$cj"
  local t; t=$(csrf_of "$BASE/login" "$cj" "$TMP/login.html")
  curl -s -o /dev/null -w '%{http_code}' -b "$cj" -c "$cj" -X POST \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    --data-urlencode "_csrf=$t" "$BASE/login"
}

# --default-character-set=utf8mb4：否则 Windows 下 mysql 客户端按 GBK 回传，
# 中文结果会被 grep 判定为二进制流（Binary file matches）导致比对失败
q() {
  "$MYSQL_BIN" --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p123456 -N -B -e "$1" 2>/dev/null \
    | grep -av 'Using a password'
}

# 分页条上的「共 N 条/单/张」；结果为空时分页条整体不渲染，此时按 0 计
pager_total() {
  local v
  v=$(fetch "$1" "$2" | grep -o '共 <b>[0-9]*</b>' | head -1 | grep -o '[0-9]*')
  printf '%s' "${v:-0}"
}
# 列表实际渲染的行数（按明细链接去重）
res_rows() { fetch "$1" "$2" | grep -o '/reservations/[0-9]*"' | sort -u | wc -l | tr -d ' '; }
bill_rows(){ fetch "$1" "$2" | grep -o '/bills/[0-9]*"' | sort -u | wc -l | tr -d ' '; }
cust_rows(){ fetch "$1" "$2" | grep -o 'customerId=[0-9]*' | sort -u | wc -l | tr -d ' '; }
# 订单列表里的入住日期（按行序，奇偶成对：入住/离店）
checkin_dates() { fetch "$1" "$2" | grep -o '<span>20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]</span>' \
                    | sed 's/<span>//;s/<\/span>//' | sed -n '1~2p'; }
room_nos() { fetch "$1" "$2" | grep -o '<span class="mono">[^<]*</span>' | sed 's/<span class="mono">//;s/<\/span>//'; }

echo "=============================================="
echo " 0. 准备：admin 登录"
echo "=============================================="
CJ="$TMP/admin.cookies"
chk "登录 admin/admin123 返回 302" "$(login admin admin123 "$CJ")" "302"

TOTAL_ORDERS=$(pager_total "$CJ" "/reservations")
TOTAL_BILLS=$(pager_total "$CJ" "/bills")
TOTAL_CUSTS=$(pager_total "$CJ" "/customers")
echo "   数据量：订单 $TOTAL_ORDERS 单 / 账单 $TOTAL_BILLS 张 / 客户 $TOTAL_CUSTS 位"

echo
echo "=============================================="
echo " 1. 订单列表分页"
echo "=============================================="
chk "默认每页 20 条：行数 = min(总数,20)" "$(res_rows "$CJ" "/reservations")" "$( [ "$TOTAL_ORDERS" -gt 20 ] && echo 20 || echo "$TOTAL_ORDERS" )"
le "size=5 时每页不超过 5 行" "$(res_rows "$CJ" "/reservations?size=5")" "5"
ge "size=5 时每页至少 1 行" "$(res_rows "$CJ" "/reservations?size=5")" "1"
chk "size=10 时行数 = min(总数,10)" "$(res_rows "$CJ" "/reservations?size=10")" "$( [ "$TOTAL_ORDERS" -gt 10 ] && echo 10 || echo "$TOTAL_ORDERS" )"
chk "分页条显示的总数与默认页一致" "$(pager_total "$CJ" "/reservations?size=5")" "$TOTAL_ORDERS"

P0=$(res_rows "$CJ" "/reservations?size=5&page=0")
P1=$(curl -s -b "$CJ" "$BASE/reservations?size=5&page=1" | grep -o '/reservations/[0-9]*"' | sort -u | md5sum | cut -c1-8)
P0H=$(curl -s -b "$CJ" "$BASE/reservations?size=5&page=0" | grep -o '/reservations/[0-9]*"' | sort -u | md5sum | cut -c1-8)
if [ "$TOTAL_ORDERS" -gt 5 ]; then
  if [ "$P0H" != "$P1" ]; then ok "第 2 页内容与第 1 页不同"; else no "第 2 页内容与第 1 页相同（分页未生效）"; fi
else
  echo "  [SKIP] 数据不足 6 单，跳过翻页差异校验"
fi

BODY_FILTER=$(fetch "$CJ" "/reservations?status=BOOKED&sort=latest")
has "翻页链接保留 status 筛选条件" "$BODY_FILTER" 'status=BOOKED'
has "翻页链接保留 sort 参数" "$BODY_FILTER" 'sort=latest'
has "每页条数链接保留筛选条件" "$BODY_FILTER" 'status=BOOKED&amp;sort=latest&amp;size=10'

# 页码越界自动夹回最后一页
LASTPAGE=$(fetch "$CJ" "/reservations?size=5" | grep -o 'page=[0-9]*' | grep -o '[0-9]*' | sort -n | tail -1)
ge "page=999 越界时仍返回数据（夹回末页）" "$(res_rows "$CJ" "/reservations?size=5&page=999")" "1"

echo
echo "=============================================="
echo " 2. 订单筛选：状态 / 入住日期区间 / 楼层"
echo "=============================================="
BOOKED_TOTAL=$(pager_total "$CJ" "/reservations?status=BOOKED")
ALL_TOTAL=$(pager_total "$CJ" "/reservations")
le "status=BOOKED 的结果数不超过全量" "$BOOKED_TOTAL" "$ALL_TOTAL"
BADGE_COUNT=$(fetch "$CJ" "/reservations?status=BOOKED" | grep -o 'badge-soft[^>]*>已预订' | wc -l | tr -d ' ')
chk "status=BOOKED 每行状态徽标都是「已预订」" "$BADGE_COUNT" "$BOOKED_TOTAL"
if [ "$BOOKED_TOTAL" != "$ALL_TOTAL" ]; then
  ok "状态筛选确实收窄了结果（$BOOKED_TOTAL < $ALL_TOTAL）"
else
  no "状态筛选未收窄结果（$BOOKED_TOTAL == $ALL_TOTAL）"
fi

# 入住日期区间：取一个已知订单的入住日，用 [该日, 该日] 过滤，结果应全部等于该日
SAMPLE_DATE=$(q "select min(check_in_date) from $DB.reservation where status='BOOKED'")
if [ -n "$SAMPLE_DATE" ]; then
  RANGE_TOTAL=$(pager_total "$CJ" "/reservations?checkInFrom=$SAMPLE_DATE&checkInTo=$SAMPLE_DATE")
  ge "入住日期区间过滤有结果" "$RANGE_TOTAL" "1"
  UNIQ=$(checkin_dates "$CJ" "/reservations?checkInFrom=$SAMPLE_DATE&checkInTo=$SAMPLE_DATE" | sort -u | wc -l | tr -d ' ')
  chk "区间 [X,X] 的结果入住日期全部等于 X" "$UNIQ" "1"
  chk "区间 [X,X] 结果数 = 库中该日订单数" "$RANGE_TOTAL" \
      "$(q "select count(*) from $DB.reservation where check_in_date='$SAMPLE_DATE'")"
else
  echo "  [SKIP] 没有已预订订单，跳过日期区间校验"
fi

# 楼层筛选：取「订单最多的楼层」，保证有结果可查；再逐行核对房号归属
FLOOR=$(q "select r.floor from $DB.reservation v join $DB.room r on r.id=v.room_id
           group by r.floor order by count(*) desc limit 1")
if [ -n "$FLOOR" ]; then
  FLOOR_ROOMS=$(q "select room_no from $DB.room where floor=$FLOOR" | tr '\n' ' ')
  ROWS=$(room_nos "$CJ" "/reservations?floor=$FLOOR&size=50" | grep -c .)
  ge "floor=$FLOOR 筛选有结果" "$ROWS" "1"
  chk "floor=$FLOOR 结果数 = 库中该楼层订单数" "$ROWS" \
      "$(q "select count(*) from $DB.reservation v join $DB.room r on r.id=v.room_id where r.floor=$FLOOR")"
  BAD=0
  while read -r rn; do
    [ -z "$rn" ] && continue
    case " $FLOOR_ROOMS " in *" $rn "*) ;; *) BAD=$((BAD+1));; esac
  done < <(room_nos "$CJ" "/reservations?floor=$FLOOR&size=50")
  chk "floor=$FLOOR 的结果房号全部属于该楼层" "$BAD" "0"
else
  echo "  [SKIP] 库里没有带订单的房间"
fi

echo
echo "=============================================="
echo " 3. 订单排序：入住日期升序"
echo "=============================================="
DATES=$(checkin_dates "$CJ" "/reservations?sort=checkInAsc&size=50")
CNT=$(printf '%s\n' "$DATES" | grep -c .)
if [ "$CNT" -gt 1 ]; then
  FIRST=$(printf '%s\n' "$DATES" | head -1)
  LAST=$(printf '%s\n' "$DATES" | tail -1)
  SORTED=$(printf '%s\n' "$DATES" | sort | tr '\n' ' ')
  ORIG=$(printf '%s\n' "$DATES" | tr '\n' ' ')
  if [ "$SORTED" = "$ORIG" ]; then ok "入住日期升序（$FIRST → $LAST）"; else no "入住日期未按升序排列（$ORIG）"; fi
else
  echo "  [SKIP] 结果不足 2 行，跳过排序校验"
fi
DESC_FIRST=$(checkin_dates "$CJ" "/reservations?sort=checkInDesc&size=50" | head -1)
ASC_LAST=$(printf '%s\n' "$DATES" | tail -1)
if [ -n "$DESC_FIRST" ] && [ -n "$ASC_LAST" ]; then
  chk "降序首位 = 升序末位" "$DESC_FIRST" "$ASC_LAST"
fi

echo
echo "=============================================="
echo " 4. 账单列表：分页 / 只看待结账 / 结账日期区间"
echo "=============================================="
chk "账单分页条总数与默认页一致" "$(pager_total "$CJ" "/bills?size=5")" "$TOTAL_BILLS"
le "账单 size=5 每页不超过 5 行" "$(bill_rows "$CJ" "/bills?size=5")" "5"
SETTLED_TOTAL=$(q "select count(*) from $DB.bill where status='SETTLED'")
UNPAID_TOTAL=$(q "select count(*) from $DB.bill where status='UNPAID'")
chk "只看已结账：结果数 = 库中已结账单数" "$(pager_total "$CJ" "/bills?status=SETTLED")" "$SETTLED_TOTAL"
chk "只看已结账：每行状态徽标都是「已结账」" \
    "$(fetch "$CJ" "/bills?status=SETTLED" | grep -o 'badge-soft[^>]*>已结账' | wc -l | tr -d ' ')" "$SETTLED_TOTAL"
if [ "$UNPAID_TOTAL" = "0" ]; then
  has "无待结账账单时展示空态而非空白" "$(fetch "$CJ" "/bills?status=UNPAID")" '没有符合条件的账单'
else
  chk "只看待结账：结果数 = 库中未结账单数" "$(pager_total "$CJ" "/bills?status=UNPAID")" "$UNPAID_TOTAL"
fi

SETTLED_DAY=$(q "select date(settled_at) from $DB.bill where settled_at is not null order by settled_at limit 1")
if [ -n "$SETTLED_DAY" ] && [ "$SETTLED_DAY" != "NULL" ]; then
  EXPECT_DAY=$(q "select count(*) from $DB.bill where date(settled_at)='$SETTLED_DAY'")
  chk "结账日期区间 [X,X] 结果数 = 库中该日结账数" \
      "$(pager_total "$CJ" "/bills?settledFrom=$SETTLED_DAY&settledTo=$SETTLED_DAY")" "$EXPECT_DAY"
else
  echo "  [SKIP] 没有已结账账单，跳过结账日期区间校验"
fi

AMOUNT_TOP=$(fetch "$CJ" "/bills?sort=amountDesc&size=10" | grep -o 'money fw-bold">￥[0-9,\.]*' | head -1 | grep -o '[0-9,\.]*' | tr -d ',')
MAX_AMOUNT=$(q "select max(total_amount) from $DB.bill")
if [ -n "$AMOUNT_TOP" ] && [ -n "$MAX_AMOUNT" ]; then
  chk "按应收金额降序时首行 = 库中最大值" "$(printf '%.2f' "$AMOUNT_TOP")" "$(printf '%.2f' "$MAX_AMOUNT")"
fi

echo
echo "=============================================="
echo " 5. 客户列表分页"
echo "=============================================="
chk "客户分页条总数与默认页一致" "$(pager_total "$CJ" "/customers?size=5")" "$TOTAL_CUSTS"
le "客户 size=5 每页不超过 5 行" "$(cust_rows "$CJ" "/customers?size=5")" "5"

echo
echo "=============================================="
echo " 6. 客户选择器接口：远程搜索"
echo "=============================================="
PICKER_BODY=$(curl -s -b "$CJ" "$BASE/customers/picker?kw=")
has "无关键词时返回最近建档客户" "$PICKER_BODY" '"phone"'
hasnt "返回结构不含证件号（不泄露敏感字段）" "$PICKER_BODY" 'idNo'

PHONE=$(q "select phone from $DB.customer where phone is not null order by id limit 1")
if [ -n "$PHONE" ]; then
  PREFIX="${PHONE:0:4}"
  FIRST_PHONE=$(curl -s -b "$CJ" "$BASE/customers/picker?kw=$PREFIX" | grep -o '"phone":"[0-9]*"' | head -1 | sed 's/.*:"//;s/"//')
  case "$FIRST_PHONE" in
    "$PREFIX"*) ok "手机号前缀 $PREFIX 命中时本人排第一位（$FIRST_PHONE）" ;;
    *) no "手机号前缀 $PREFIX 未把本人排在首位（首位 $FIRST_PHONE）" ;;
  esac
  FULL_HIT=$(curl -s -b "$CJ" "$BASE/customers/picker?kw=$PHONE" | grep -c "\"phone\":\"$PHONE\"")
  ge "完整手机号能精确命中" "$FULL_HIT" "1"
else
  echo "  [SKIP] 库里没有客户手机号"
fi

echo
echo "=============================================="
echo " 7. 内联快速建档：/customers/quick"
echo "=============================================="
STAMP=$(date +%H%M%S)
RAND=$(awk 'BEGIN{srand(); printf "%03d", rand()*1000}')
# 手机号必须满足 ^1[3-9]\d{9}$：139 + 6 位时分秒 + 2 位 = 11 位
TEST_PHONE="139${STAMP}00"
# 证件号加随机后缀，避免连续两次运行落在同一秒时互相干扰
TEST_IDNO="11010119900101${STAMP}${RAND}"
CSRF_QUICK=$(csrf_of "$BASE/customers/new" "$CJ" "$TMP/cnew.html")

RESP_OK=$(curl -s -b "$CJ" -H 'Accept: application/json' \
  --data-urlencode "_csrf=$CSRF_QUICK" \
  --data-urlencode "name=验收测试客户" --data-urlencode "gender=MALE" \
  --data-urlencode "idType=ID_CARD" --data-urlencode "idNo=$TEST_IDNO" \
  --data-urlencode "phone=$TEST_PHONE" --data-urlencode "memberLevel=金卡" \
  "$BASE/customers/quick")
case "$RESP_OK" in *'"ok":true'*) ;; *) echo "   [DEBUG] /customers/quick 响应：$RESP_OK" ;; esac
has "建档成功返回 ok:true" "$RESP_OK" '"ok":true'
has "返回体带新客户 id" "$RESP_OK" '"id":'
has "返回体带会员等级（供选择器展示）" "$RESP_OK" '"memberLevel":"金卡"'

NEW_ID=$(q "select id from $DB.customer where phone='$TEST_PHONE' limit 1")
NEW_NAME=$(q "select name from $DB.customer where phone='$TEST_PHONE' limit 1")
chk "客户已落库" "$NEW_NAME" "验收测试客户"
has "新建后 picker 能立刻搜到" "$(curl -s -b "$CJ" "$BASE/customers/picker?kw=$TEST_PHONE")" "\"id\":$NEW_ID"

RESP_DUP=$(curl -s -b "$CJ" -H 'Accept: application/json' \
  --data-urlencode "_csrf=$CSRF_QUICK" \
  --data-urlencode "name=重复证件号" --data-urlencode "gender=FEMALE" \
  --data-urlencode "idType=ID_CARD" --data-urlencode "idNo=$TEST_IDNO" \
  --data-urlencode "phone=13900000001" --data-urlencode "memberLevel=普通" \
  "$BASE/customers/quick")
has "证件号重复时 ok:false" "$RESP_DUP" '"ok":false'
has "证件号重复时给出可读原因" "$RESP_DUP" '已登记过客户'

RESP_BAD=$(curl -s -b "$CJ" -H 'Accept: application/json' \
  --data-urlencode "_csrf=$CSRF_QUICK" \
  --data-urlencode "name=" --data-urlencode "gender=MALE" \
  --data-urlencode "idType=ID_CARD" --data-urlencode "idNo=" \
  --data-urlencode "phone=123" --data-urlencode "memberLevel=普通" \
  "$BASE/customers/quick")
has "表单校验失败时 ok:false" "$RESP_BAD" '"ok":false'
has "返回逐字段错误：phone" "$RESP_BAD" '"phone"'
has "返回逐字段错误：name" "$RESP_BAD" '"name"'
has "返回逐字段错误：idNo" "$RESP_BAD" '"idNo"'

echo
echo "=============================================="
echo " 8. 预订页：客户选择器改造"
echo "=============================================="
NEWPAGE=$(fetch "$CJ" "/reservations/new")
hasnt "不再全量渲染客户下拉 <select>" "$NEWPAGE" 'select[^>]*id="customerId"'
has "存在搜索选择器容器" "$NEWPAGE" 'id="customerPicker"'
has "存在选择器输入框" "$NEWPAGE" 'id="customerSearch"'
has "存在客户 id 隐藏域" "$NEWPAGE" 'id="customerId"'
has "存在内联新建弹窗" "$NEWPAGE" 'id="customerModal"'
has "弹窗提交地址为 /customers/quick" "$NEWPAGE" '/customers/quick'
has "弹窗表单跳过全局防重复点击（自带锁定）" "$NEWPAGE" 'data-no-lock'
hasnt "不再跳转到 /customers/new 建客户" "$NEWPAGE" 'href="/customers/new"'

# 编辑态回填：/customers/{id} 的「订房」入口带 customerId
if [ -n "$NEW_ID" ]; then
  EDITPAGE=$(fetch "$CJ" "/reservations/new?customerId=$NEW_ID")
  has "带 customerId 进入时回填已选客户" "$EDITPAGE" 'is-selected'
  has "回填内容包含该客户手机号" "$EDITPAGE" "$TEST_PHONE"
fi

echo
echo "=============================================="
echo " 清理测试数据"
echo "=============================================="
if [ -n "$NEW_ID" ]; then
  T=$(csrf_in "$TMP/cnew.html")
  DEL_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$CJ" -c "$CJ" \
      --data-urlencode "_csrf=$T" "$BASE/customers/$NEW_ID/delete")
  LEFT=$(q "select count(*) from $DB.customer where id=$NEW_ID")
  chk "测试客户已清理（http=$DEL_CODE，剩余 $LEFT 行）" "$LEFT" "0"
else
  echo "  [WARN] 未取到测试客户 id，请手工清理 phone=$TEST_PHONE"
fi

echo
echo "=============================================="
printf " 结果：\033[32mPASS=%d\033[0m  \033[31mFAIL=%d\033[0m\n" "$PASS" "$FAIL"
echo "=============================================="
exit $FAIL
