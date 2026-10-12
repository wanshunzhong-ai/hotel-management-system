#!/usr/bin/env bash
# 写操作授权探测（临时排障用）
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8081}"
MYSQL="/d/SoftWare/MySQL/MySQL Server 9.0/bin/mysql.exe"
TMP="$(mktemp -d)"
q(){ MSYS_NO_PATHCONV=1 "$MYSQL" --default-character-set=utf8mb4 -uroot -p123456 -D hotel_management -N -B -e "$1" 2>/dev/null | tr -d '\r'; }
csrf(){ local jar="$1" path="$2"; local t
  t=$(curl -s --noproxy '*' -b "$jar" -c "$jar" "$BASE$path" -o "$TMP/c.html"; \
      grep -o 'name="_csrf"[^>]*value="[^"]*"' "$TMP/c.html" | head -1 | sed 's/.*value="//;s/"//'); echo "$t"; }
login(){ local u="$1" p="$2" jar="$3"; rm -f "$jar"
  local t; t=$(csrf "$jar" "/login")
  curl -s --noproxy '*' -o /dev/null -b "$jar" -c "$jar" \
    --data-urlencode "username=$u" --data-urlencode "password=$p" --data-urlencode "_csrf=$t" "$BASE/login"; }
post(){ local path="$1" jar="$2" src="$3"; shift 3
  local t; t=$(csrf "$jar" "$src")
  curl -s --noproxy '*' -o "$TMP/r.html" -w '%{http_code}' -b "$jar" -c "$jar" \
    --data-urlencode "_csrf=$t" "$@" "$BASE$path"; }

J="$TMP/hk.jar"; login housekeeping 123456 "$J"

echo "=== 客房服务员 POST 探测 ==="
printf '%-58s %s\n' "POST /housekeeping/schedule (排班)"        "$(post /housekeeping/schedule "$J" /housekeeping/schedule --data-urlencode "workDate=2026-11-01" --data-urlencode "shift=MORNING" --data-urlencode "userId=6")"
printf '%-58s %s\n' "POST /housekeeping/tasks (派工)"            "$(post /housekeeping/tasks "$J" /housekeeping/tasks --data-urlencode "roomId=2" --data-urlencode "type=DEEP")"
printf '%-58s %s\n' "POST /rooms/save (建房)"                    "$(post /rooms/save "$J" /rooms/new --data-urlencode "roomNo=9999" --data-urlencode "floor=9" --data-urlencode "roomTypeId=1")"
printf '%-58s %s\n' "POST /checklists (检查单模板)"              "$(post /checklists "$J" /checklists --data-urlencode "itemName=越权测试项")"
printf '%-58s %s\n' "POST /supplies (耗材建档)"                  "$(post /supplies "$J" /supplies --data-urlencode "itemName=越权测试耗材" --data-urlencode "unit=个")"
printf '%-58s %s\n' "POST /laundry/linen (布草建档)"             "$(post /laundry/linen "$J" /laundry --data-urlencode "itemName=越权测试布草")"
printf '%-58s %s\n' "POST /consumables (消费目录)"               "$(post /consumables "$J" /consumables --data-urlencode "itemName=越权测试消费" --data-urlencode "price=1")"
printf '%-58s %s\n' "POST /consumables/rooms/2/reset-token"      "$(post /consumables/rooms/2/reset-token "$J" /consumables)"
printf '%-58s %s\n' "POST /laundry/outsourcing (外送建批次)"     "$(post /laundry/outsourcing "$J" /laundry/outsourcing --data-urlencode "vendor=越权测试" --data-urlencode "linenId=1" --data-urlencode "quantity=1" --data-urlencode "price=1")"
printf '%-58s %s\n' "POST /users/save"                           "$(post /users/save "$J" /users/new --data-urlencode "username=越权账号" --data-urlencode "rawPassword=123456" --data-urlencode "name=x")"
printf '%-58s %s\n' "POST /payments/deposit"                     "$(post /payments/deposit "$J" /payments --data-urlencode "reservationId=1" --data-urlencode "amount=1" --data-urlencode "method=CASH")"
printf '%-58s %s\n' "POST /bills/1/unsettle"                     "$(post /bills/1/unsettle "$J" /bills/1)"

echo
echo "=== 房态越权试验（room 2 初始 AVAILABLE）==="
echo "before: $(q "select status from room where id=2;")"
echo "POST /rooms/2/status?status=OCCUPIED -> $(post /rooms/2/status "$J" /rooms --data-urlencode "status=OCCUPIED")"
echo "after : $(q "select status from room where id=2;")"
echo "POST /rooms/2/status?status=AVAILABLE (复原) -> $(post /rooms/2/status "$J" /rooms --data-urlencode "status=AVAILABLE")"
echo "after2: $(q "select status from room where id=2;")"
echo
echo "=== 移动端改房态（/hk/room/2/status）==="
echo "POST OCCUPIED -> $(post /hk/room/2/status "$J" /hk/room/2 --data-urlencode "status=OCCUPIED")"
echo "after : $(q "select status from room where id=2;")"
echo "POST AVAILABLE (复原) -> $(post /hk/room/2/status "$J" /hk/room/2 --data-urlencode "status=AVAILABLE")"
echo "after2: $(q "select status from room where id=2;")"
