#!/usr/bin/env bash
# 酒店管理系统 —— 第四批（客房服务部模块）验收测试
#
#  1. 角色与权限隔离：客房部（HOUSEKEEPING）只能进客房相关页面；验收限管理员与前台（客房部不能验收）
#  2. 派工单：派工 → 服务员完成 → 主管验收通过（房间自动转「空闲」，房态闭环）
#  3. 打回重做：打回必须填原因；打回后服务员可重新提交验收
#  4. 迷你吧 / 客用品挂账：在住挂账（非在住房间被拒）→ 退房时自动并入账单其他消费
#  5. 维修工单：报修（联动房态）→ 开工 → 完工（转待清洁）；在住房间报修不改房态
#  6. 失物招领：拾遗登记 → 关键词检索 → 认领（重复认领被拒）
#  7. 审计留痕：上述动作全部进 operation_log 且管理员可查
#
# 前提：应用已启动（默认 http://127.0.0.1:8080，可用 HMS_BASE 覆盖）
# 数据：脚本会创建订单/账单/派工单等，结束后请执行 scripts/clean-test-data.sh 清理
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8080}"
TMP="/tmp/hms-opt4"
mkdir -p "$TMP"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
no()   { FAIL=$((FAIL+1)); printf "  \033[31m[FAIL]\033[0m %s\n" "$1"; }
chk()  { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else no "$1 期望[$3] 实际[$2]"; fi; }
has()  { if printf '%s' "$2" | grep -q -- "$3"; then ok "$1"; else no "$1（未匹配 [$3]）"; fi; }
lacks(){ if printf '%s' "$2" | grep -q -- "$3"; then no "$1（不应出现 [$3]）"; else ok "$1"; fi; }
# operation_log 只增不删（clean-test-data.sh 不清理它），所以对日志计数只能用「>=$3」，
# 否则第二次运行就会因为上一轮的记录而假失败
ge()   { if [ "${2:-0}" -ge "${3:-1}" ] 2>/dev/null; then ok "$1 ($2)"; else no "$1 期望>=[$3] 实际[$2]"; fi; }
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

post_form() { # $1=url $2=cookiejar $3=csrf 剩余=表单
  local url="$1" cj="$2" t="$3"; shift 3
  curl -sL -o "$TMP/post.html" -w '%{http_code}' -b "$cj" -c "$cj" \
    --data-urlencode "_csrf=$t" "$@" "$url"
}

code_of() { curl -s -o "$TMP/page.html" -w '%{http_code}' -b "$1" "$2"; }

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
first_room_id() { curl -s -b "$1" "$BASE/rooms" | grep -o '/rooms/[0-9]*/edit' | head -1 | grep -o '[0-9]*'; }
# 选一间「空闲且没有在住客人」的房间——否则会把在住房的房态改坏、也测不出挂账拦截
idle_room() {
  q "select r.id from room r
     where r.status='AVAILABLE'
       and not exists (select 1 from reservation v where v.room_id=r.id and v.status='CHECKED_IN')
     order by r.id limit 1;"
}

# 第七批起：派工单会按「清洁标准」生成检查清单，未全勾不能提交完成。
# 测试里先一键全勾，再把工单标记完成——即模拟服务员逐项确认后的提交。
tick_all() { # $1=任务id $2=cookiejar $3=临时文件
  local t
  t=$(csrf_of "$BASE/housekeeping/tasks/$1" "$2" "$3")
  post_form "$BASE/housekeeping/tasks/$1/checklist/all" "$2" "$t" \
    --data-urlencode "checked=true" > /dev/null
}

echo "=============================================="
echo " 0. 准备：三种角色登录 + 基础数据"
echo "=============================================="
CJ="$TMP/admin.cookies"; CJR="$TMP/reception.cookies"; CJH="$TMP/housekeeping.cookies"
chk "登录 admin/admin123 返回 302"        "$(login admin admin123 "$CJ")" "302"
chk "登录 reception/123456 返回 302"      "$(login reception 123456 "$CJR")" "302"
chk "登录 housekeeping/123456 返回 302"   "$(login housekeeping 123456 "$CJH")" "302"

HK_ID=$(q "select id from sys_user where username='housekeeping';")
ADMIN_NAME=$(q "select real_name from sys_user where username='admin';")
echo "   客房服务员 id=$HK_ID  管理员=$ADMIN_NAME"

TODAY=$(date +%Y-%m-%d); D1=$(date -d "+1 day" +%Y-%m-%d); D2=$(date -d "+2 day" +%Y-%m-%d)
CID=$(q "select id from customer order by id limit 1;")
chk "新表已由应用启动自动建好" \
    "$(q "select count(*) from information_schema.tables where table_schema=database() and table_name in ('housekeeping_task','minibar_item','maintenance_order','lost_found');")" "4"

echo
echo "=============================================="
echo " 1. 角色与权限隔离"
echo "=============================================="
chk "客房部可访问派工页"   "$(code_of "$CJH" "$BASE/housekeeping/tasks")" "200"
chk "客房部可访问迷你吧页" "$(code_of "$CJH" "$BASE/housekeeping/minibar")" "200"
chk "客房部可访问维修页"   "$(code_of "$CJH" "$BASE/maintenance")" "200"
chk "客房部可访问失物页"   "$(code_of "$CJH" "$BASE/lost-found")" "200"
chk "客房部进不了账单页"   "$(code_of "$CJH" "$BASE/bills")" "403"
chk "客房部进不了操作日志" "$(code_of "$CJH" "$BASE/logs")" "403"
CH=$(curl -s -b "$CJH" "$BASE/housekeeping/tasks")
has "客房部导航含「派工与验收」" "$CH" '派工与验收'
lacks "客房部导航不含「账单结算」" "$CH" '>账单结算<'
lacks "客房部看不到「新建派工单」表单" "$CH" '新建派工单'

echo
echo "=============================================="
echo " 2. 派工 → 完成 → 验收通过（房态闭环）"
echo "=============================================="
RID1=$(idle_room)
echo "   使用空闲房间ID=$RID1"
T=$(csrf_of "$BASE/rooms/$RID1/edit" "$CJ" "$TMP/r1.html")
post_form "$BASE/rooms/$RID1/status" "$CJ" "$T" --data-urlencode "status=CLEANING" > /dev/null
chk "先把房间置为待清洁" "$(q "select status from room where id=$RID1;")" "CLEANING"

T=$(csrf_of "$BASE/housekeeping/tasks" "$CJ" "$TMP/hk1.html")
post_form "$BASE/housekeeping/tasks" "$CJ" "$T" \
  --data-urlencode "roomId=$RID1" --data-urlencode "type=CHECKOUT" \
  --data-urlencode "assigneeId=$HK_ID" --data-urlencode "remark=第四批测试-走客房" > /dev/null
TASK1=$(q "select max(id) from housekeeping_task;")
chk "派工单落库且状态为待完成" "$(q "select status from housekeeping_task where id=$TASK1;")" "PENDING"
chk "派工单记录了服务员"       "$(q "select assignee_id from housekeeping_task where id=$TASK1;")" "$HK_ID"

RNO1=$(q "select room_no from room where id=$RID1;")
has "服务员能看到派给自己的单" "$(curl -s -b "$CJH" "$BASE/housekeeping/tasks")" "$RNO1"

# 令牌必须取自被登录人自己的会话，否则 CSRF 校验会 403
T=$(csrf_of "$BASE/housekeeping/tasks" "$CJH" "$TMP/hk_hk.html")
# 第七批：检查单未全勾就提交完成 → 业务拒绝（返回 200 但状态不变 + 页面提示）
chk "未打勾即提交完成（200 但被业务拒绝）" \
    "$(post_form "$BASE/housekeeping/tasks/$TASK1/done" "$CJH" "$T")" "200"
chk "未打勾 → 任务仍是待完成" "$(q "select status from housekeeping_task where id=$TASK1;")" "PENDING"
has "页面提示检查单未打勾" "$(cat "$TMP/post.html")" '项未打勾'
tick_all "$TASK1" "$CJH" "$TMP/hk_cl1.html"
chk "一键全勾后检查单全部打勾" \
    "$(q "select count(*) from task_checklist where task_id=$TASK1 and checked+0=0;")" "0"
T=$(csrf_of "$BASE/housekeeping/tasks" "$CJH" "$TMP/hk_hk1.html")
chk "服务员标记完成（200）" \
    "$(post_form "$BASE/housekeeping/tasks/$TASK1/done" "$CJH" "$T")" "200"
chk "任务转为待验收" "$(q "select status from housekeeping_task where id=$TASK1;")" "DONE"

T=$(csrf_in "$TMP/post.html")
chk "服务员无权验收（403）" \
    "$(post_form "$BASE/housekeeping/tasks/$TASK1/inspect" "$CJH" "$T" \
        --data-urlencode "action=approve")" "403"
chk "被拒后任务状态不变" "$(q "select status from housekeeping_task where id=$TASK1;")" "DONE"

T=$(csrf_of "$BASE/housekeeping/tasks" "$CJ" "$TMP/hk2.html")
chk "管理员验收通过（200）" \
    "$(post_form "$BASE/housekeeping/tasks/$TASK1/inspect" "$CJ" "$T" \
        --data-urlencode "action=approve" --data-urlencode "inspectRemark=已复查，合格")" "200"
chk "任务状态=验收通过" "$(q "select status from housekeeping_task where id=$TASK1;")" "APPROVED"
chk "验收人已记录"       "$(q "select inspector from housekeeping_task where id=$TASK1;")" "$ADMIN_NAME"
chk "验收通过后房间转空闲" "$(q "select status from room where id=$RID1;")" "AVAILABLE"

echo
echo "=============================================="
echo " 3. 打回重做"
echo "=============================================="
T=$(csrf_of "$BASE/housekeeping/tasks" "$CJ" "$TMP/hk3.html")
post_form "$BASE/housekeeping/tasks" "$CJ" "$T" \
  --data-urlencode "roomId=$RID1" --data-urlencode "type=STAYOVER" \
  --data-urlencode "assigneeId=$HK_ID" --data-urlencode "remark=第四批测试-打回" > /dev/null
TASK2=$(q "select max(id) from housekeeping_task;")
tick_all "$TASK2" "$CJH" "$TMP/hk_cl2.html"
T=$(csrf_of "$BASE/housekeeping/tasks" "$CJH" "$TMP/hk_hk2.html")
post_form "$BASE/housekeeping/tasks/$TASK2/done" "$CJH" "$T" > /dev/null
chk "第二单已提交验收" "$(q "select status from housekeeping_task where id=$TASK2;")" "DONE"

# 管理员会话的令牌放在管理员自己的页面上（hk3.html 由 CJ 抓取，仅用于创建派工单）
T=$(csrf_of "$BASE/housekeeping/tasks" "$CJ" "$TMP/hk_admin.html")
post_form "$BASE/housekeeping/tasks/$TASK2/inspect" "$CJ" "$T" \
  --data-urlencode "action=reject" > "$TMP/rej_no_reason.html"
chk "无原因打回被拒（仍是待验收）" "$(q "select status from housekeeping_task where id=$TASK2;")" "DONE"
has "页面提示打回必须填原因" "$(cat "$TMP/post.html")" '打回时必须填写打回原因'

T=$(csrf_of "$BASE/housekeeping/tasks" "$CJ" "$TMP/hk_admin2.html")
post_form "$BASE/housekeeping/tasks/$TASK2/inspect" "$CJ" "$T" \
  --data-urlencode "action=reject" --data-urlencode "inspectRemark=浴缸没刷干净" > /dev/null
chk "带原因打回成功" "$(q "select status from housekeeping_task where id=$TASK2;")" "REJECTED"
chk "打回原因已留痕" "$(q "select inspect_remark from housekeeping_task where id=$TASK2;")" "浴缸没刷干净"

T=$(csrf_of "$BASE/housekeeping/tasks" "$CJH" "$TMP/hk_hk3.html")
tick_all "$TASK2" "$CJH" "$TMP/hk_cl3.html"
T=$(csrf_of "$BASE/housekeeping/tasks" "$CJH" "$TMP/hk_hk4.html")
chk "被打回后可重新标记完成（200）" \
    "$(post_form "$BASE/housekeeping/tasks/$TASK2/done" "$CJH" "$T")" "200"
chk "重新提交后回到待验收" "$(q "select status from housekeeping_task where id=$TASK2;")" "DONE"
curl -s -b "$CJH" "$BASE/housekeeping/tasks?status=REJECTED" -o "$TMP/rej_list.html"
# 断言口径：页面「被打回」徽标条数必须等于库里 REJECTED 的条数。
# 早前这里写死期望 0，隐含假设「全库没有别的被打回工单」——一旦别的批次（如第七批的
# 打回流程验收）留下一条 REJECTED 记录，这里就会假失败。改成与库中计数比对，
# 既保留「状态筛选真的生效」的验证力，又不再依赖测试执行顺序。
chk "按状态筛选：已打回条数与库中一致" \
    "$(grep -c 'badge-soft[^>]*>被打回' "$TMP/rej_list.html")" \
    "$(q "select count(*) from housekeeping_task where status='REJECTED';")"

echo
echo "=============================================="
echo " 4. 迷你吧 / 客用品挂账 → 退房并入账单"
echo "=============================================="
RID_MB=$(pick_free_room "$TODAY" "$D2")
echo "   使用房间ID=$RID_MB（$TODAY ~ $D2）"
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID_MB" \
  --data-urlencode "checkInDate=$TODAY" --data-urlencode "checkOutDate=$D2" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=第四批测试-迷你吧" > /dev/null
ORD=$(newest_order "$CJ")
T=$(csrf_of "$BASE/reservations/$ORD" "$CJ" "$TMP/ord.html")
post_form "$BASE/reservations/$ORD/checkin" "$CJ" "$T" > /dev/null
chk "订单已入住" "$(q "select status from reservation where id=$ORD;")" "CHECKED_IN"

# 非在住房间挂账应被拒——必须另选一间此刻仍空闲、且没有在住客人的房
RID_NB=$(idle_room)
echo "   负向测试用房间ID=$RID_NB"
T=$(csrf_of "$BASE/housekeeping/minibar" "$CJ" "$TMP/mb0.html")
post_form "$BASE/housekeeping/minibar" "$CJ" "$T" \
  --data-urlencode "roomId=$RID_NB" --data-urlencode "itemName=测试-越界挂账" \
  --data-urlencode "quantity=1" --data-urlencode "unitPrice=10" > "$TMP/mb_fail.html"
has "非在住房间挂账被拒" "$(cat "$TMP/post.html")" '没有在住订单'
chk "被拒后未产生挂账记录" \
    "$(q "select count(*) from minibar_item where room_id=$RID_NB;")" "0"

T=$(csrf_in "$TMP/post.html")
post_form "$BASE/housekeeping/minibar" "$CJ" "$T" \
  --data-urlencode "roomId=$RID_MB" --data-urlencode "itemName=矿泉水" \
  --data-urlencode "quantity=2" --data-urlencode "unitPrice=8" \
  --data-urlencode "note=第四批测试-挂账" > /dev/null
T=$(csrf_in "$TMP/post.html")
post_form "$BASE/housekeeping/minibar" "$CJ" "$T" \
  --data-urlencode "roomId=$RID_MB" --data-urlencode "itemName=红酒" \
  --data-urlencode "quantity=1" --data-urlencode "unitPrice=288" \
  --data-urlencode "note=第四批测试-挂账" > /dev/null
chk "挂账 2 条已落库" "$(q "select count(*) from minibar_item where reservation_id=$ORD;")" "2"
chk "挂账金额自动计算正确" \
    "$(q "select sum(amount) from minibar_item where reservation_id=$ORD;")" "304.00"
has "挂账列表显示品名" "$(curl -s -b "$CJH" "$BASE/housekeeping/minibar")" '矿泉水'

T=$(csrf_in "$TMP/post.html")
post_form "$BASE/reservations/$ORD" "$CJ" "$T" > /dev/null
T=$(csrf_of "$BASE/reservations/$ORD" "$CJ" "$TMP/ord2.html")
URL=$(curl -s -o /dev/null -w '%{redirect_url}' -b "$CJ" -c "$CJ" -X POST \
    --data-urlencode "_csrf=$(csrf_in "$TMP/ord2.html")" \
    --data-urlencode "actualCheckOutDate=$D1" \
    "$BASE/reservations/$ORD/checkout")
BILL=$(printf '%s' "$URL" | grep -o '[0-9]*$')
echo "   退房生成账单 id=$BILL"
chk "账单其他消费 = 挂账合计" "$(q "select other_amount from bill where id=$BILL;")" "304.00"
chk "账单应收 = 房费 + 挂账" \
    "$(awk -v t="$(q "select total_amount from bill where id=$BILL;")" \
           -v r="$(q "select room_amount from bill where id=$BILL;")" \
       'BEGIN{printf "%.2f", t - r - 304}')" "0.00"
has "账单页出现迷你吧明细" "$(curl -s -b "$CJ" "$BASE/bills/$BILL")" '迷你吧 · 矿泉水'
chk "挂账已标记为已入账单" "$(q "select count(*) from minibar_item where reservation_id=$ORD and status='POSTED';")" "2"
chk "挂账关联了账单号"     "$(q "select count(*) from minibar_item where reservation_id=$ORD and bill_id=$BILL;")" "2"
chk "入账后再取消被拒（记录已不在待入账列表）" \
    "$(q "select count(*) from minibar_item where reservation_id=$ORD and status='PENDING';")" "0"

echo
echo "=============================================="
echo " 5. 维修工单 + 房态联动"
echo "=============================================="
RID_MT=$(curl -s -b "$CJ" "$BASE/rooms?status=AVAILABLE" | grep -o '/rooms/[0-9]*/edit' | head -1 | grep -o '[0-9]*')
RNO_MT=$(q "select room_no from room where id=$RID_MT;")
T=$(csrf_of "$BASE/maintenance" "$CJ" "$TMP/mt.html")
post_form "$BASE/maintenance" "$CJ" "$T" \
  --data-urlencode "roomId=$RID_MT" --data-urlencode "description=第四批测试-花洒漏水" > /dev/null
MT1=$(q "select max(id) from maintenance_order;")
chk "工单已创建且待处理" "$(q "select status from maintenance_order where id=$MT1;")" "OPEN"
chk "空闲房报修后自动转维修中" "$(q "select status from room where id=$RID_MT;")" "MAINTENANCE"
has "维修页显示房号与故障" "$(curl -s -b "$CJH" "$BASE/maintenance")" "$RNO_MT"

T=$(csrf_in "$TMP/post.html")
post_form "$BASE/maintenance/$MT1/start" "$CJ" "$T" --data-urlencode "handler=张师傅" > /dev/null
chk "开工后状态为维修中" "$(q "select status from maintenance_order where id=$MT1;")" "FIXING"
chk "记录维修人"         "$(q "select handler from maintenance_order where id=$MT1;")" "张师傅"

T=$(csrf_in "$TMP/post.html")
post_form "$BASE/maintenance/$MT1/complete" "$CJ" "$T" \
  --data-urlencode "result=更换花洒软管" --data-urlencode "cost=45.5" > /dev/null
chk "完工后工单关闭" "$(q "select status from maintenance_order where id=$MT1;")" "DONE"
chk "维修费用已记录" "$(q "select cost from maintenance_order where id=$MT1;")" "45.50"
chk "完工后房间转待清洁" "$(q "select status from room where id=$RID_MT;")" "CLEANING"

# 在住房间报修不改房态
RID_IH=$(q "select room_id from reservation where id=$ORD;")
chk "（前置）该房已退房，改用新在住单" "$(q "select status from room where id=$RID_IH;")" "CLEANING"

RID2=$(pick_free_room "$TODAY" "$D1")
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new2.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID2" \
  --data-urlencode "checkInDate=$TODAY" --data-urlencode "checkOutDate=$D1" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=第四批测试-在住报修" > /dev/null
ORD2=$(newest_order "$CJ")
T=$(csrf_of "$BASE/reservations/$ORD2" "$CJ" "$TMP/ord3.html")
post_form "$BASE/reservations/$ORD2/checkin" "$CJ" "$T" > /dev/null
T=$(csrf_of "$BASE/maintenance" "$CJ" "$TMP/mt2.html")
post_form "$BASE/maintenance" "$CJ" "$T" \
  --data-urlencode "roomId=$RID2" --data-urlencode "description=第四批测试-在住房间空调异响" > /dev/null
chk "在住房间报修不占用房态" "$(q "select status from room where id=$RID2;")" "OCCUPIED"

MT2=$(q "select max(id) from maintenance_order;")
T=$(csrf_in "$TMP/post.html")
post_form "$BASE/maintenance/$MT2/cancel" "$CJ" "$T" > /dev/null
chk "取消工单成功" "$(q "select status from maintenance_order where id=$MT2;")" "CANCELLED"
chk "取消后在住房态不变" "$(q "select status from room where id=$RID2;")" "OCCUPIED"

echo
echo "=============================================="
echo " 6. 失物招领"
echo "=============================================="
T=$(csrf_of "$BASE/lost-found" "$CJH" "$TMP/lf.html")
post_form "$BASE/lost-found" "$CJH" "$T" \
  --data-urlencode "itemName=黑色雨伞" --data-urlencode "description=第四批测试-长柄自动伞" \
  --data-urlencode "location=8806 房间" --data-urlencode "finder=客房-王姐" > /dev/null
LF1=$(q "select max(id) from lost_found;")
chk "失物登记工单为待认领" "$(q "select status from lost_found where id=$LF1;")" "OPEN"
chk "拾获人已记录"         "$(q "select finder from lost_found where id=$LF1;")" "客房-王姐"
has "列表命中关键词「雨伞」" \
    "$(curl -s -G -b "$CJH" --data-urlencode "keyword=雨伞" "$BASE/lost-found")" '黑色雨伞'
chk "无关关键词筛不出记录" \
    "$(curl -s -G -b "$CJH" --data-urlencode "keyword=不存在的失物XYZ" "$BASE/lost-found" \
       | grep -c 'badge-soft[^>]*>待认领')" "0"

T=$(csrf_in "$TMP/post.html")
post_form "$BASE/lost-found/$LF1/claim" "$CJH" "$T" \
  --data-urlencode "claimant=张先生" --data-urlencode "claimContact=13900001111" > /dev/null
chk "认领后状态为已认领" "$(q "select status from lost_found where id=$LF1;")" "CLAIMED"
chk "认领人已记录"       "$(q "select claimant from lost_found where id=$LF1;")" "张先生"

T=$(csrf_in "$TMP/post.html")
post_form "$BASE/lost-found/$LF1/claim" "$CJH" "$T" \
  --data-urlencode "claimant=李女士" > /dev/null
has "重复认领被拒并提示" "$(cat "$TMP/post.html")" '该物品已被认领'
chk "认领人未被覆盖" "$(q "select claimant from lost_found where id=$LF1;")" "张先生"

echo
echo "=============================================="
echo " 7. 审计留痕"
echo "=============================================="
for act in 派工 清洁完成 验收通过 验收打回 迷你吧挂账 迷你吧并入账单 报修 开工 维修完工 维修取消 失物登记 失物认领; do
  n=$(q "select count(*) from operation_log where action='$act';")
  if [ "$n" -gt 0 ]; then ok "审计日志含动作「$act」($n 条)"; else no "审计日志缺动作「$act」"; fi
done
LOGP=$(curl -s -b "$CJ" "$BASE/logs")
has "管理员日志页可见派工记录" "$LOGP" '派工'
chk "日志页仍对客房部关闭" "$(code_of "$CJH" "$BASE/logs")" "403"

echo
echo "=============================================="
echo " 8. 权限加固回归（第五批修补）"
echo "=============================================="
RCP_NAME=$(q "select real_name from sys_user where username='reception';")
HK_NAME=$(q "select real_name from sys_user where username='housekeeping';")

# 8.1 /rooms/save 越权：先前只拦了 GET 表单页（/rooms/new、/rooms/*/edit），
#     漏了表单提交地址，客房部能绕过被隐藏的按钮直接 POST 建房/改房
T=$(csrf_of "$BASE/rooms" "$CJH" "$TMP/p_rooms.html")
chk "客房部提交 /rooms/save 被拒（403）" \
    "$(post_form "$BASE/rooms/save" "$CJH" "$T" --data-urlencode "roomNo=9999")" "403"
# 对照：同一个令牌提交「房态变更」是放行的 → 证明上面的 403 来自授权而非 CSRF 失败
RID_CTL=$(idle_room)
chk "对照：客房部同一令牌改房态放行（200）" \
    "$(post_form "$BASE/rooms/$RID_CTL/status" "$CJH" "$T" --data-urlencode "status=AVAILABLE")" "200"
TR=$(csrf_of "$BASE/rooms" "$CJR" "$TMP/p_rooms_r.html")
chk "前台未被 /rooms/save 拦截（200）" \
    "$(post_form "$BASE/rooms/save" "$CJR" "$TR" \
        --data-urlencode "id=999999" --data-urlencode "roomNo=不存在的房间")" "200"
chk "越权尝试未写入任何客房" \
    "$(q "select count(*) from room where room_no in ('9999','不存在的房间');")" "0"

# 8.2 看板数据隔离：客房部不该看到营收口径
DH=$(curl -s -b "$CJH" "$BASE/dashboard")
DA=$(curl -s -b "$CJ" "$BASE/dashboard")
has   "客房部看板保留房态指标"       "$DH" '在住房间'
has   "客房部看板保留今日待办"       "$DH" '今日待办'
lacks "客房部看板不含「今日营收」"    "$DH" '今日营收'
lacks "客房部看板不含「累计营收」"    "$DH" '累计营收'
lacks "客房部看板不含「在住预计房费」" "$DH" '在住预计房费'
lacks "客房部看板不含营收趋势图"      "$DH" '近 7 日营收趋势'
lacks "客房部看板源码中无金额符号"    "$DH" '￥'
has   "管理员看板仍含「今日营收」"    "$DA" '今日营收'
has   "管理员看板仍含营收趋势图"      "$DA" '近 7 日营收趋势'

# 8.3 验收权开放给前台（解开「管理员不在岗→房间卡在待清洁」的死结）
chk "（前置）第二单仍处于待验收" "$(q "select status from housekeeping_task where id=$TASK2;")" "DONE"
T=$(csrf_of "$BASE/housekeeping/tasks" "$CJR" "$TMP/p_hk_r.html")
chk "前台验收通过（200）" \
    "$(post_form "$BASE/housekeeping/tasks/$TASK2/inspect" "$CJR" "$T" \
        --data-urlencode "action=approve" --data-urlencode "inspectRemark=前台代验收")" "200"
chk "任务状态=验收通过"   "$(q "select status from housekeeping_task where id=$TASK2;")" "APPROVED"
chk "验收人记录为前台"     "$(q "select inspector from housekeeping_task where id=$TASK2;")" "$RCP_NAME"

# 8.4 撤销挂账：必须填原因；客房部只能撤本人录入的
RID3=$(pick_free_room "$TODAY" "$D2")
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/p_new.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID3" \
  --data-urlencode "checkInDate=$TODAY" --data-urlencode "checkOutDate=$D2" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=第四批测试-撤销权限" > /dev/null
ORD3=$(newest_order "$CJ")
T=$(csrf_of "$BASE/reservations/$ORD3" "$CJ" "$TMP/p_ord.html")
post_form "$BASE/reservations/$ORD3/checkin" "$CJ" "$T" > /dev/null
chk "（前置）新订单已入住" "$(q "select status from reservation where id=$ORD3;")" "CHECKED_IN"

T=$(csrf_of "$BASE/housekeeping/minibar" "$CJH" "$TMP/p_mb_hk.html")
post_form "$BASE/housekeeping/minibar" "$CJH" "$T" \
  --data-urlencode "roomId=$RID3" --data-urlencode "itemName=第五批测试-客房部自录" \
  --data-urlencode "quantity=1" --data-urlencode "unitPrice=12" > /dev/null
MB_HK=$(q "select max(id) from minibar_item;")
chk "（前置）客房部自录挂账落库" "$(q "select operator from minibar_item where id=$MB_HK;")" "$HK_NAME"

T=$(csrf_of "$BASE/housekeeping/minibar" "$CJR" "$TMP/p_mb_r.html")
post_form "$BASE/housekeeping/minibar" "$CJR" "$T" \
  --data-urlencode "roomId=$RID3" --data-urlencode "itemName=第五批测试-前台录入" \
  --data-urlencode "quantity=1" --data-urlencode "unitPrice=20" > /dev/null
MB_R=$(q "select max(id) from minibar_item;")
chk "（前置）前台录入挂账落库" "$(q "select operator from minibar_item where id=$MB_R;")" "$RCP_NAME"

T=$(csrf_of "$BASE/housekeeping/minibar" "$CJH" "$TMP/p_mb1.html")
post_form "$BASE/housekeeping/minibar/$MB_HK/delete" "$CJH" "$T" > /dev/null
has "撤销不填原因被拒" "$(cat "$TMP/post.html")" '请填写撤销原因'
chk "被拒后该挂账仍在" "$(q "select count(*) from minibar_item where id=$MB_HK;")" "1"

T=$(csrf_of "$BASE/housekeeping/minibar" "$CJH" "$TMP/p_mb2.html")
post_form "$BASE/housekeeping/minibar/$MB_R/delete" "$CJH" "$T" \
  --data-urlencode "reason=想抹掉别人记的账" > /dev/null
has "客房部撤销他人挂账被拒" "$(cat "$TMP/post.html")" '只能撤销本人录入的挂账'
chk "被拒后他人挂账仍在" "$(q "select count(*) from minibar_item where id=$MB_R;")" "1"

T=$(csrf_of "$BASE/housekeeping/minibar" "$CJH" "$TMP/p_mb3.html")
post_form "$BASE/housekeeping/minibar/$MB_HK/delete" "$CJH" "$T" \
  --data-urlencode "reason=录错房间需重录" > /dev/null
chk "客房部撤销本人挂账成功" "$(q "select count(*) from minibar_item where id=$MB_HK;")" "0"

T=$(csrf_of "$BASE/housekeeping/minibar" "$CJR" "$TMP/p_mb4.html")
post_form "$BASE/housekeeping/minibar/$MB_R/delete" "$CJR" "$T" \
  --data-urlencode "reason=客人已自行买单" > /dev/null
chk "前台撤销他人挂账成功" "$(q "select count(*) from minibar_item where id=$MB_R;")" "0"

ge "两条撤销均已留痕（迷你吧取消）" \
   "$(q "select count(*) from operation_log where action='迷你吧取消'
           and (detail like '%第五批测试-客房部自录%' or detail like '%第五批测试-前台录入%');")" 2
ge "撤销原因已写入操作日志" \
   "$(q "select count(*) from operation_log where action='迷你吧取消' and detail like '%录错房间需重录%';")" 1

echo
echo "=============================================="
printf " 结果：PASS=%d FAIL=%d\n" "$PASS" "$FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ]
