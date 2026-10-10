#!/usr/bin/env bash
# 酒店管理系统 —— 业务全流程 + 权限隔离 端到端测试
# 前提：应用已启动在 http://127.0.0.1:8080
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8080}"
TMP="/tmp/hms-func"
mkdir -p "$TMP"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
no()   { FAIL=$((FAIL+1)); printf "  \033[31m[FAIL]\033[0m %s\n" "$1"; }
chk()  { # $1=描述 $2=实际 $3=期望
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else no "$1 期望[$3] 实际[$2]"; fi; }

csrf_of() { # $1=url $2=cookiejar $3=outfile
  curl -s -b "$2" -c "$2" "$1" -o "$3"
  grep -o 'name="_csrf"[^>]*value="[^"]*"' "$3" | head -1 | sed 's/.*value="//;s/"//'
}

login() { # $1=user $2=pass $3=cookiejar
  local cj="$3"; rm -f "$cj"
  local t; t=$(csrf_of "$BASE/login" "$cj" "$TMP/login.html")
  curl -s -o /dev/null -w '%{http_code}' -b "$cj" -c "$cj" -X POST \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    --data-urlencode "_csrf=$t" "$BASE/login"
}

post_form() { # $1=url $2=cookiejar $3=csrf 剩余=表单
  local url="$1" cj="$2" t="$3"; shift 3
  curl -s -o "$TMP/post.html" -w '%{http_code}' -b "$cj" -c "$cj" -X POST \
    --data-urlencode "_csrf=$t" "$@" "$url"
}

echo "=============================================="
echo " 1. 前台接待登录 + 新建预订"
echo "=============================================="
CJ="$TMP/rec.cookies"
chk "登录 reception/123456 返回 302" "$(login reception 123456 "$CJ")" "302"

# 用「今天~明天」以便后续可正常办理入住；房间取自当日可订列表（自动排除在住/冲突/维修房）
IN=$(date +%Y-%m-%d); OUT=$(date -d "+1 day" +%Y-%m-%d)
CID=$(curl -s -b "$CJ" "$BASE/customers" | grep -o '/customers/[0-9]*/edit' | head -1 | grep -o '[0-9]*')
RID=$(curl -s -b "$CJ" "$BASE/reservations/available-rooms?checkIn=$IN&checkOut=$OUT" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
echo "   使用 客户ID=$CID 房间ID=$RID 日期 $IN ~ $OUT"

T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new.html")
RCODE=$(post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID" \
  --data-urlencode "checkInDate=$IN" --data-urlencode "checkOutDate=$OUT" \
  --data-urlencode "guestCount=2" --data-urlencode "remark=功能测试单")
chk "POST /reservations/save 返回 302" "$RCODE" "302"

curl -s -b "$CJ" "$BASE/reservations" -o "$TMP/rlist.html"
ORDER=$(grep -o 'RS20[0-9]\{6\}' "$TMP/rlist.html" | head -1)
NEWID=$(grep -o '/reservations/[0-9]*"' "$TMP/rlist.html" | head -1 | grep -o '[0-9]*')
if [ -n "$NEWID" ]; then ok "新订单已生成 $ORDER (id=$NEWID)"; else no "未找到新订单"; fi

echo
echo "=============================================="
echo " 2. 冲突校验：同一房间同一时段重复预订应被拒绝"
echo "=============================================="
BEFORE=$(grep -o '/reservations/[0-9]*"' "$TMP/rlist.html" | sort -u | wc -l | tr -d ' ')
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new2.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID" \
  --data-urlencode "checkInDate=$IN" --data-urlencode "checkOutDate=$OUT" \
  --data-urlencode "guestCount=2" > /dev/null
curl -s -b "$CJ" -c "$CJ" "$BASE/reservations" -o "$TMP/rlist2.html"
AFTER=$(grep -o '/reservations/[0-9]*"' "$TMP/rlist2.html" | sort -u | wc -l | tr -d ' ')
chk "订单数未增加（冲突被拦截）" "$AFTER" "$BEFORE"
echo -n "   页面提示: "; grep -o '该房间[^<]*\|已被预订[^<]*\|冲突[^<]*' "$TMP/rlist2.html" | head -1; echo

echo
echo "=============================================="
echo " 3. 办理入住"
echo "=============================================="
T=$(grep -o 'name="_csrf"[^>]*value="[^"]*"' "$TMP/rlist2.html" | head -1 | sed 's/.*value="//;s/"//')
post_form "$BASE/reservations/$NEWID/checkin" "$CJ" "$T" > /dev/null
curl -s -b "$CJ" "$BASE/reservations/$NEWID" -o "$TMP/rd.html"
chk "订单详情显示【已入住】" "$(grep -c '已入住' "$TMP/rd.html")" "1"
curl -s -b "$CJ" "$BASE/rooms/board" -o "$TMP/b0.html"
chk "房态图出现【已入住】房间" "$([ "$(grep -c 'OCCUPIED' "$TMP/b0.html")" -gt 0 ] && echo yes || echo no)" "yes"

echo
echo "=============================================="
echo " 4. 办理退房 -> 自动生成账单"
echo "=============================================="
T=$(csrf_of "$BASE/reservations" "$CJ" "$TMP/rl3.html")
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' -b "$CJ" -c "$CJ" -X POST \
   --data-urlencode "_csrf=$T" "$BASE/reservations/$NEWID/checkout")
BILLID=$(printf '%s' "$LOC" | grep -o '[0-9]*$')
chk "退房后跳转账单页" "$(printf '%s' "$LOC" | grep -c '/bills/')" "1"
if [ -n "$BILLID" ]; then ok "账单已生成 id=$BILLID"; else no "未取得账单 id ($LOC)"; fi

curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd.html"
chk "账单状态【待结账】" "$([ "$(grep -c '待结账' "$TMP/bd.html")" -gt 0 ] && echo yes || echo no)" "yes"

echo
echo "=============================================="
echo " 5. 追加消费 + 结账"
echo "=============================================="
T=$(grep -o 'name="_csrf"[^>]*value="[^"]*"' "$TMP/bd.html" | head -1 | sed 's/.*value="//;s/"//')
post_form "$BASE/bills/$BILLID/items" "$CJ" "$T" \
  --data-urlencode "itemName=迷你吧饮料" --data-urlencode "unitPrice=38.00" \
  --data-urlencode "quantity=2" --data-urlencode "remark=冰箱可乐" > /dev/null
curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd1.html"
chk "消费明细含【迷你吧饮料】" "$(grep -c '迷你吧饮料' "$TMP/bd1.html")" "1"
chk "其他消费金额 76.00" "$([ "$(grep -c '76.00' "$TMP/bd1.html")" -gt 0 ] && echo yes || echo no)" "yes"

T=$(grep -o 'name="_csrf"[^>]*value="[^"]*"' "$TMP/bd1.html" | head -1 | sed 's/.*value="//;s/"//')
post_form "$BASE/bills/$BILLID/settle" "$CJ" "$T" \
  --data-urlencode "deposit=300.00" --data-urlencode "paidAmount=600.00" \
  --data-urlencode "payMethod=WECHAT" --data-urlencode "remark=功能测试结账" > /dev/null
curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd2.html"
chk "账单状态变为【已结账】" "$(grep -c '已结账' "$TMP/bd2.html")" "1"
chk "出现收款记录" "$([ "$(grep -c '收款' "$TMP/bd2.html")" -gt 0 ] && echo yes || echo no)" "yes"

echo
echo "=============================================="
echo " 6. 退房后房态：待清洁 -> 清洁完成 -> 空闲"
echo "=============================================="
curl -s -b "$CJ" "$BASE/rooms/board" -o "$TMP/b1.html"
chk "房态图出现【清洁完成】操作" "$([ "$(grep -c '清洁完成' "$TMP/b1.html")" -gt 0 ] && echo yes || echo no)" "yes"

echo
echo "=============================================="
echo " 7. 权限隔离：客房 housekeeping"
echo "=============================================="
HCJ="$TMP/hk.cookies"
chk "登录 housekeeping/123456 返回 302" "$(login housekeeping 123456 "$HCJ")" "302"
for p in /dashboard /rooms /rooms/board /customers /reservations /bills /users; do
  printf "   GET %-15s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' -b "$HCJ" "$BASE$p")"
done
chk "housekeeping 访问 /users 被拒(403)" "$(curl -s -o /dev/null -w '%{http_code}' -b "$HCJ" "$BASE/users")" "403"

echo
echo "=============================================="
echo " 8. 未登录访问应重定向到登录页"
echo "=============================================="
chk "匿名访问 /dashboard 重定向" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/dashboard")" "302"

echo
echo "=============================================="
printf " 结果：\033[32mPASS=%d\033[0m  \033[31mFAIL=%d\033[0m\n" "$PASS" "$FAIL"
echo "=============================================="
