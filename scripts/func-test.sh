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

# 状态徽章计数。页面上别处也会出现「已结账」字样（如「已结账，明细锁定」），
# 直接 grep -c 会把计数抬高，因此只匹配真正的 <span class="badge-soft ...">状态</span>。
badge_count() { # $1=文件 $2=状态文字
  grep -o "badge-soft[^>]*>$2" "$1" | wc -l | tr -d ' '
}

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

# 选一间「日期可订 ∩ 房态空闲」的房间。
# 只按日期筛是不够的：房态为「已入住」但今天离店的房间，其占用区间与 [今天,明天) 不相交，
# 会被 available-rooms 当成可订返回，但办理入住时必然因「房间已有人入住」失败
# ——房态语义的结构性问题，见 docs/优化建议.md 第 1.1 条。
pick_free_room() { # $1=checkIn $2=checkOut，回显 roomId
  local ids free id
  ids=$(curl -s -b "$CJ" "$BASE/reservations/available-rooms?checkIn=$1&checkOut=$2" \
        | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
  free=$(curl -s -b "$CJ" "$BASE/rooms?status=AVAILABLE" \
        | grep -o '/rooms/[0-9]*/edit' | grep -o '[0-9]*')
  for id in $ids; do
    if printf '%s\n' "$free" | grep -qx "$id"; then printf '%s' "$id"; return 0; fi
  done
}

echo "=============================================="
echo " 1. 前台接待登录 + 新建预订"
echo "=============================================="
CJ="$TMP/rec.cookies"
chk "登录 reception/123456 返回 302" "$(login reception 123456 "$CJ")" "302"

# 用「今天~明天」以便后续可正常办理入住；房间取自当日可订列表（自动排除在住/冲突/维修房）
IN=$(date +%Y-%m-%d); OUT=$(date -d "+1 day" +%Y-%m-%d)
CID=$(curl -s -b "$CJ" "$BASE/customers" | grep -o '/customers/[0-9]*/edit' | head -1 | grep -o '[0-9]*')
RID=$(pick_free_room "$IN" "$OUT")
echo "   使用 客户ID=$CID 房间ID=$RID 日期 $IN ~ $OUT"
if [ -z "$RID" ]; then no "找不到「日期可订且房态空闲」的房间，无法继续"; exit 1; fi

# 建单前先记下总数：业务异常同样会返回 302（重定向回 /reservations/new），
# 只看状态码无法判断是否真的建单成功，必须比对订单总数。
COUNT_BEFORE=$(curl -s -b "$CJ" "$BASE/reservations?size=100" | grep -o '/reservations/[0-9]*"' | sort -u | wc -l | tr -d ' ')

T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new.html")
RCODE=$(post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID" \
  --data-urlencode "checkInDate=$IN" --data-urlencode "checkOutDate=$OUT" \
  --data-urlencode "guestCount=2" --data-urlencode "remark=功能测试单")
chk "POST /reservations/save 返回 302" "$RCODE" "302"
if grep -q '已被预订\|正在维修\|不能早于今天\|不能超过' "$TMP/post.html"; then
  echo "   !! 建单被拒：$(grep -o '该房间[^<]*\|房间[^<]*维修[^<]*\|入住日期[^<]*\|单次预订[^<]*' "$TMP/post.html" | head -1)"
fi

curl -s -b "$CJ" "$BASE/reservations?size=100" -o "$TMP/rlist.html"
ORDER=$(grep -o 'RS20[0-9]\{6\}' "$TMP/rlist.html" | head -1)
NEWID=$(grep -o '/reservations/[0-9]*"' "$TMP/rlist.html" | head -1 | grep -o '[0-9]*')
COUNT_AFTER=$(grep -o '/reservations/[0-9]*"' "$TMP/rlist.html" | sort -u | wc -l | tr -d ' ')
chk "订单总数 +1" "$COUNT_AFTER" "$((COUNT_BEFORE+1))"
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

# 结账金额按账单「实际应收」推算，不能写死：房价随房型而变，
# 写死押金 300 + 补付 600 会在房价较高的房型上触发「收款不足」校验而假失败。
BILL_TOTAL=$(grep -o 'data-total="[0-9.]*"' "$TMP/bd1.html" | head -1 | grep -o '[0-9.]*')
if awk -v t="${BILL_TOTAL:-0}" 'BEGIN{exit !(t>=300)}'; then
  DEPOSIT="300.00"
  PAY_REST=$(awk -v t="$BILL_TOTAL" 'BEGIN{printf "%.2f", t-300}')
else
  DEPOSIT="${BILL_TOTAL:-0}"
  PAY_REST="0.00"
fi
echo "   账单应收 ￥$BILL_TOTAL → 押金 ￥$DEPOSIT + 补付 ￥$PAY_REST"

T=$(grep -o 'name="_csrf"[^>]*value="[^"]*"' "$TMP/bd1.html" | head -1 | sed 's/.*value="//;s/"//')
post_form "$BASE/bills/$BILLID/settle" "$CJ" "$T" \
  --data-urlencode "deposit=$DEPOSIT" --data-urlencode "paidAmount=$PAY_REST" \
  --data-urlencode "payMethod=WECHAT" --data-urlencode "remark=功能测试结账" > /dev/null
if grep -q '收款不足' "$TMP/post.html"; then
  echo "   !! 结账被拒：$(grep -o '收款不足[^<]*' "$TMP/post.html" | head -1)"
fi
curl -s -b "$CJ" "$BASE/bills/$BILLID" -o "$TMP/bd2.html"
chk "账单状态变为【已结账】" "$(badge_count "$TMP/bd2.html" '已结账')" "1"
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
