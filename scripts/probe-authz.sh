#!/usr/bin/env bash
# 授权面探测（临时排障用）：4 角色 × 只读端点，输出真实状态码矩阵
# 用法：HMS_BASE=http://127.0.0.1:8081 bash scripts/probe-authz.sh
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8081}"
MYSQL="/d/SoftWare/MySQL/MySQL Server 9.0/bin/mysql.exe"
TMP="$(mktemp -d)"
CURL="curl -s --noproxy *"

q(){ MSYS_NO_PATHCONV=1 "$MYSQL" --default-character-set=utf8mb4 -uroot -p123456 -D hotel_management -N -B -e "$1" 2>/dev/null | tr -d '\r'; }

login(){
  local user="$1" jar="$2"
  rm -f "$jar"
  local t; t=$(curl -s --noproxy '*' -b "$jar" -c "$jar" "$BASE/login" -o "$TMP/l.html"; \
              grep -o 'name="_csrf"[^>]*value="[^"]*"' "$TMP/l.html" | head -1 | sed 's/.*value="//;s/"//')
  curl -s --noproxy '*' -o /dev/null -b "$jar" -c "$jar" \
       --data-urlencode "username=$user" --data-urlencode "password=123456" \
       --data-urlencode "_csrf=$t" "$BASE/login"
}

ROOM=$(q "select id from room order by id limit 1;")
TASK=$(q "select id from housekeeping_task order by id limit 1;")
BILL=$(q "select id from bill order by id limit 1;")
RES=$(q "select id from reservation order by id limit 1;")
ITEM=$(q "select id from supply_item order by id limit 1;")
BATCH=$(q "select id from outsource_batch order by id limit 1;")

PATHS=(
  "/dashboard" "/todos" "/profile" "/board" "/api/board" "/dashboard/print/today"
  "/reservations" "/reservations/new" "/bills" "/customers" "/customers/picker"
  "/payments" "/invoices" "/receivables" "/shift" "/groups/checkin" "/rate-plans"
  "/users" "/logs" "/room-types"
  "/rooms" "/rooms/board" "/rooms/new" "/rooms/$ROOM/edit"
  "/housekeeping/tasks" "/housekeeping/tasks/$TASK" "/housekeeping/schedule"
  "/housekeeping/minibar" "/checklists" "/supplies" "/supplies/$ITEM"
  "/inspections" "/maintenance" "/lost-found" "/hk" "/hk/room/$ROOM"
  "/laundry" "/laundry/outsourcing" ${BATCH:+"/laundry/outsourcing/$BATCH"}
  "/consumables" "/consumables/rooms/$ROOM/qr.png"
)

for u in admin reception housekeeping supervisor; do
  jar="$TMP/$u.jar"; login "$u" "$jar"
  printf '\n===== %s =====\n' "$u"
  for p in "${PATHS[@]}"; do
    code=$(curl -s --noproxy '*' -b "$jar" -o "$TMP/p.html" -w '%{http_code}' "$BASE$p")
    err=""; grep -q '操作未能完成\|系统异常\|An error occurred' "$TMP/p.html" && err="  <ERRPAGE>"
    printf '  %-3s %s%s\n' "$code" "$p" "$err"
  done
done

printf '\nids: room=%s task=%s bill=%s res=%s item=%s batch=%s\n' "$ROOM" "$TASK" "$BILL" "$RES" "$ITEM" "$BATCH"
