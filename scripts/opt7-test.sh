#!/usr/bin/env bash
# 酒店管理系统 —— 第七批（客房服务补全：主管角色 / 自行派工 / 清洁检查单 / 排班工作量 /
#                              耗材出入库 / 移动端扫码上报 / 站内待办中心 / 布草外送结算）验收测试
#
#  1.  角色继承：新角色「客房主管」自动获得客房服务员全部页面权限（URL 层继承）
#  2.  自行派工：主管可派工，服务员不可（403）；派工单详情页 + 检查单自动生成
#  3.  清洁标准检查单：模板增删改 / 停用；执行清单为<b>快照</b>（模板删了历史单不受影响）；
#      未逐项打勾不能提交完成；一键全勾后可提交；验收通过 / 打回
#  4.  排班与工作量：三班次排班（重复排班被拒）+ 按人工作量统计
#  5.  耗材出入库：建档（自动一笔入库流水）/ 领用 / 报废 / 盘点调整；超库存被拒；
#      有流水的耗材不能删；服务员可领用但不可建档
#  6.  移动端扫码上报：/hk 必须登录（与免登录的 /scan 是两条路径）；扫码直达房间操作台；
#      现场报修 / 登记失物 / 挂账 / 改房态 / 一键完成工单
#  7.  站内待办中心：/todos 实时派生待办 + 侧栏角标（无消息表）
#  8.  布草洗涤外包：送出（在库→洗涤中）/ 回收 / 结算；未回收不能结算；超量被拒；
#      服务员不可建批次；交接单可打印（送出 / 已收回 / 已结算三状态下明细行均须渲染）
#  9.  权限边界：客房部（含主管）进不了前台六个资金模块与 /users /logs（403）
# 10.  页面渲染：九个新页面均可访问且含关键元素（含回归：扫码消费页修复后不再报错）
#
# 前提：应用已启动（默认 http://127.0.0.1:8080，可用 HMS_BASE 覆盖）
# 数据：脚本创建的数据备注 / 品名含「测试」，结束后执行 scripts/clean-test-data.sh 清理
#
# ⚠️ CSRF：本系统表单受 CSRF 保护，且令牌与<b>会话绑定</b>。
#    提交时必须用「该会话自己页面里」的令牌，不能拿管理员的令牌去提交主管的表单——
#    那样会被 CSRF 过滤器先拦成 403，把「权限被拒」和「令牌不对」混为一谈。
#    因此下面 csrf() 的第一个参数是路径、第二个参数必须是提交时用的同一个 cookie 罐。
#
# ⚠️ 错误页会以 HTTP 200 返回：模板渲染到一半抛异常时响应已按 200 提交，
#    错误页被追写进同一个响应体。只看状态码、或用 has 在「半截页面」里找关键字，
#    都会把「整页坏掉」误判成 PASS。凡「页面能打开」的断言一律用 pageok()。
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8080}"
TMP="/tmp/hms-opt7"
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
csrf()    { csrf_of "$BASE$1" "${2:-$CJ}" "$TMP/c.html"; }
code_of() { curl -s --noproxy '*' -o "$TMP/page.html" -w '%{http_code}' -b "$1" "$2"; }
get_page(){ curl -s --noproxy '*' -b "$1" "$BASE$2"; }
page_text(){ cat "$TMP/page.html"; }
anon_code(){ curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE$1"; }

# 错误页识别。踩过的坑：模板渲染到一半抛异常时，响应早已按 200 提交，
# 紧接着错误页被追写进同一个响应体 —— 于是「页面挂了」却是 HTTP 200，
# 而页面前半截已经渲染出的文字又让 has 断言匹配成功，整页坏掉却报 PASS。
# 所以凡「页面能正常打开」的断言，一律走 pageok：既看状态码，也看正文不是错误页。
err_page(){ printf '%s' "$1" | grep -q -e '操作未能完成' -e '系统开小差了' -e 'No converter for'; }
pageok(){ # $1=描述 $2=cookie罐 $3=路径  剩余=正文中必须出现的关键字
  local desc="$1" cj="$2" path="$3"; shift 3
  local html; html=$(curl -s --noproxy '*' -b "$cj" "$BASE$path")
  if err_page "$html"; then no "$desc（返回了错误页）"; return; fi
  local kw
  for kw in "$@"; do
    if ! printf '%s' "$html" | grep -q -- "$kw"; then no "$desc（缺 [$kw]）"; return; fi
  done
  ok "$desc"
}

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

task_id_of() { q "select id from housekeeping_task where remark='$1' order by id desc limit 1;"; }
tick_all() { # $1=taskId $2=cookiejar
  local t; t=$(csrf "/housekeeping/tasks/$1" "$2")
  post "$BASE/housekeeping/tasks/$1/checklist/all" "$2" "$t" --data-urlencode "checked=true" >/dev/null
}

echo "=============================================="
echo " 0. 准备：四角色登录 + 基础数据"
echo "=============================================="
CJ="$TMP/admin.cookies"; CJR="$TMP/reception.cookies"
CJH="$TMP/housekeeping.cookies"; CJS="$TMP/supervisor.cookies"
chk "登录 admin/admin123 返回 302"           "$(login admin admin123 "$CJ")"   "302"
chk "登录 reception/123456 返回 302"         "$(login reception 123456 "$CJR")" "302"
chk "登录 housekeeping/123456 返回 302"      "$(login housekeeping 123456 "$CJH")" "302"
chk "登录 supervisor/123456 返回 302（第七批新角色）" "$(login supervisor 123456 "$CJS")" "302"

chk "客房主管账号已存在" "$(q "select count(*) from sys_user where username='supervisor' and role='HOUSEKEEPING_SUPERVISOR';")" "1"
ge "检查单模板种子已建立（>=15 项）" "$(q "select count(*) from checklist_item;")" "15"
ge "耗材台账种子已建立（>=12 品名）" "$(q "select count(*) from supply_item;")" "12"

HK_ID=$(q "select id from sys_user where username='housekeeping';")
FARDATE=$(date -d '+30 day' +%F)

# --- 幂等前置清理：清掉上一轮（可能中断的）残留，保证重复运行结果一致 ---
# 外键顺序：task_checklist → housekeeping_task；supply_movement → supply_item；
#           outsource_item → outsource_batch。outsource 送出的批次还要先把数量退回布草在库。
q "DELETE tc FROM task_checklist tc JOIN housekeeping_task t ON tc.task_id=t.id
   WHERE t.remark LIKE '%测试%';"
q "DELETE FROM housekeeping_task WHERE remark LIKE '%测试%';"
q "DELETE FROM duty_schedule WHERE remark LIKE '%测试%' OR work_date=DATE_ADD(CURDATE(), INTERVAL 30 DAY);"
q "UPDATE linen_stock ls JOIN outsource_item oi ON oi.linen_stock_id=ls.id
     JOIN outsource_batch ob ON oi.batch_id=ob.id
   SET ls.in_wash=GREATEST(0, ls.in_wash-oi.quantity), ls.in_stock=ls.in_stock+oi.quantity
   WHERE ob.status='SENT' AND (ob.vendor LIKE '%测试%' OR ob.remark LIKE '%测试%');"
q "DELETE oi FROM outsource_item oi JOIN outsource_batch ob ON oi.batch_id=ob.id
   WHERE ob.vendor LIKE '%测试%' OR ob.remark LIKE '%测试%';"
q "DELETE FROM outsource_batch WHERE vendor LIKE '%测试%' OR remark LIKE '%测试%';"
q "DELETE FROM linen_stock WHERE item_name LIKE '%测试%';"
q "DELETE sm FROM supply_movement sm JOIN supply_item si ON sm.item_id=si.id
   WHERE si.item_name LIKE '%测试%';"
q "DELETE FROM supply_item WHERE item_name LIKE '%测试%';"
q "DELETE FROM checklist_item WHERE item_name LIKE '%测试%';"
# 第七批「移动端扫码上报」产生的三类现场数据：报修工单 / 失物登记 / 迷你吧挂账。
# 不清理的话，下面「本轮应生成 1 条」这类断言在重复运行时会累加（1→3）而误报失败。
# 报修可能把房间置为「维修中」，删工单前先把这些房间复位；只认测试工单涉及到的房间，
# 不会误伤 606（演示基线「维修中」，无测试工单）。
q "UPDATE room r SET r.status='AVAILABLE'
   WHERE r.status IN ('MAINTENANCE','CLEANING')
     AND EXISTS (SELECT 1 FROM maintenance_order m
                 WHERE m.room_id=r.id AND m.description LIKE '%第七批测试%')
     AND NOT EXISTS (SELECT 1 FROM reservation v
                     WHERE v.room_id=r.id AND v.status IN ('BOOKED','CHECKED_IN'));"
q "DELETE FROM maintenance_order WHERE description LIKE '%第七批测试%';"
q "DELETE FROM lost_found WHERE item_name LIKE '%第七批测试%' OR description LIKE '%第七批测试%';"
q "DELETE FROM minibar_item WHERE item_name LIKE '%第七批测试%' OR note LIKE '%第七批测试%';"

# 基线断言必须放在幂等清理之后：否则重复运行会看到上一轮残留而误报「基线不为空」
chk "布草外送批次基线为空" "$(q "select count(*) from outsource_batch;")" "0"
chk "排班基线为空" "$(q "select count(*) from duty_schedule;")" "0"
chk "报修工单基线为空" "$(q "select count(*) from maintenance_order where description LIKE '%第七批测试%';")" "0"
chk "失物登记基线为空" "$(q "select count(*) from lost_found where item_name LIKE '%第七批测试%';")" "0"
chk "现场挂账基线为空" "$(q "select count(*) from minibar_item where item_name LIKE '%第七批测试%';")" "0"

# 测试用房间：任务用「空闲房」、扫码动作用另一间空闲房、挂账用有在住订单的房
ROOM_ID=$(q "select id from room where status='AVAILABLE' and room_no not in ('606','607') order by id limit 1;")
ROOM_NO=$(q "select room_no from room where id=$ROOM_ID;")
ROOM2_ID=$(q "select id from room where status='AVAILABLE' and room_no not in ('606','607') and id<>$ROOM_ID order by id limit 1;")
INHOUSE_ID=$(q "select id from room where id in (select room_id from reservation where status='CHECKED_IN') order by id limit 1;")
ge "取到可用任务房间" "$ROOM_ID" "1"
ge "取到第二间空闲房（扫码用）" "$ROOM2_ID" "1"
ge "取到在住房（挂账用）" "$INHOUSE_ID" "1"

echo
echo "=============================================="
echo " 1. 角色继承：客房主管 ⊃ 客房服务员（URL 层）"
echo "=============================================="
# 这些页面的 URL 授权只写了 HOUSEKEEPING，主管能进说明 RoleHierarchy 生效
for p in /housekeeping/tasks /housekeeping/schedule /checklists /supplies /laundry \
         /laundry/outsourcing /hk /inspections /maintenance /lost-found /consumables; do
  chk "主管可进 $p" "$(code_of "$CJS" "$BASE$p")" "200"
done
chk "服务员同样可进 /housekeeping/tasks（对照）" "$(code_of "$CJH" "$BASE/housekeeping/tasks")" "200"

echo
echo "=============================================="
echo " 2. 自行派工 + 派工单详情"
echo "=============================================="
TH=$(csrf /housekeeping/tasks "$CJH")
TS=$(csrf /housekeeping/tasks "$CJS")
chk "服务员派工被拒(403)" "$(post "$BASE/housekeeping/tasks" "$CJH" "$TH" \
    --data-urlencode "roomId=$ROOM_ID" --data-urlencode "type=CHECKOUT" \
    --data-urlencode "assigneeId=$HK_ID" --data-urlencode "remark=第七批测试派工-越权")" "403"
chk "主管派工成功" "$(post "$BASE/housekeeping/tasks" "$CJS" "$TS" \
    --data-urlencode "roomId=$ROOM_ID" --data-urlencode "type=CHECKOUT" \
    --data-urlencode "assigneeId=$HK_ID" --data-urlencode "remark=第七批测试派工")" "200"
TASK1=$(task_id_of "第七批测试派工")
ge "派工单已落库" "$TASK1" "1"

CKL1=$(get_page "$CJS" "/housekeeping/tasks/$TASK1")
chk "派工单详情页可访问" "$(code_of "$CJS" "$BASE/housekeeping/tasks/$TASK1")" "200"
has "详情页含检查项「更换床品与枕套」" "$CKL1" "更换床品与枕套"
has "详情页含流程节点「验收」" "$CKL1" "验收"
has "走客房清单含专用项「检查客人遗留物品」" "$CKL1" "检查客人遗留物品"
lacks "走客房清单不含深度清洁专用项" "$CKL1" "清洗窗帘与床垫除螨"
ge "派工自动生成检查单（>=13 项）" "$(q "select count(*) from task_checklist where task_id=$TASK1;")" "13"

# 深度清洁任务的清单应换成 DEEP 专用项
post "$BASE/housekeeping/tasks" "$CJS" "$TS" \
    --data-urlencode "roomId=$ROOM_ID" --data-urlencode "type=DEEP" \
    --data-urlencode "assigneeId=$HK_ID" --data-urlencode "remark=第七批测试派工-深度" >/dev/null
TASKD=$(task_id_of "第七批测试派工-深度")
CKD=$(get_page "$CJS" "/housekeeping/tasks/$TASKD")
has "深度清洁清单含「清洗窗帘与床垫除螨」" "$CKD" "清洗窗帘与床垫除螨"
lacks "深度清洁清单不含走客房专用项" "$CKD" "检查客人遗留物品"

echo
echo "=============================================="
echo " 3. 清洁标准检查单"
echo "=============================================="
echo "-- 3.1 模板维护（新增 / 停用 / 删除 + 快照语义）--"
chk "模板页可访问" "$(code_of "$CJS" "$BASE/checklists")" "200"
TH=$(csrf /checklists "$CJH"); TS=$(csrf /checklists "$CJS")
chk "服务员新增检查项被拒(403)" "$(post "$BASE/checklists" "$CJH" "$TH" \
    --data-urlencode "itemName=第七批测试检查项-越权" --data-urlencode "category=HYGIENE")" "403"
chk "主管新增检查项成功" "$(post "$BASE/checklists" "$CJS" "$TS" \
    --data-urlencode "itemName=第七批测试检查项" --data-urlencode "category=OTHER" \
    --data-urlencode "note=第七批测试")" "200"
CI_ID=$(q "select id from checklist_item where item_name='第七批测试检查项';")
ge "检查项已落库" "$CI_ID" "1"
has "模板页出现新检查项" "$(get_page "$CJS" /checklists)" "第七批测试检查项"

# 新增后建的派工单，清单里应带上这一条（通用项对所有任务类型生效）
TS=$(csrf /housekeeping/tasks "$CJS")
post "$BASE/housekeeping/tasks" "$CJS" "$TS" \
    --data-urlencode "roomId=$ROOM_ID" --data-urlencode "type=CHECKOUT" \
    --data-urlencode "assigneeId=$HK_ID" --data-urlencode "remark=第七批测试派工-快照" >/dev/null
TASKS=$(task_id_of "第七批测试派工-快照")
chk "新任务清单已含新增项（14 项）" "$(q "select count(*) from task_checklist where task_id=$TASKS;")" "14"
has "新任务清单含新增项名称" "$(get_page "$CJS" "/housekeeping/tasks/$TASKS")" "第七批测试检查项"

# 停用 + 改排序
TS=$(csrf /checklists "$CJS")
post "$BASE/checklists/$CI_ID/update" "$CJS" "$TS" \
    --data-urlencode "category=OTHER" --data-urlencode "sortOrder=99" \
    --data-urlencode "note=第七批测试改" >/dev/null
chk "检查项排序已更新" "$(q "select sort_order from checklist_item where id=$CI_ID;")" "99"
post "$BASE/checklists/$CI_ID/toggle" "$CJS" "$TS" --data-urlencode "enabled=false" >/dev/null
chk "检查项已停用" "$(q "select enabled+0 from checklist_item where id=$CI_ID;")" "0"

# 删除模板 —— 已生成的执行清单必须原样保留（快照设计）
post "$BASE/checklists/$CI_ID/delete" "$CJS" "$TS" >/dev/null
chk "模板已删除" "$(q "select count(*) from checklist_item where id=$CI_ID;")" "0"
chk "历史派工单的清单条数不受影响（仍 14）" "$(q "select count(*) from task_checklist where task_id=$TASKS;")" "14"
has "历史清单仍保留已删模板的条目名（快照）" "$(get_page "$CJS" "/housekeeping/tasks/$TASKS")" "第七批测试检查项"

echo "-- 3.2 执行：未打勾不可提交 → 全勾 → 完成 → 验收 --"
TS=$(csrf "/housekeeping/tasks/$TASK1" "$CJS")
chk "未打勾即提交完成：业务拒绝（页面 200）" "$(post "$BASE/housekeeping/tasks/$TASK1/done" "$CJS" "$TS")" "200"
has "拒绝提示含「项未打勾」" "$(flash '项未打勾')" "项未打勾"
chk "被拒后任务仍为待完成(PENDING)" "$(q "select status from housekeeping_task where id=$TASK1;")" "PENDING"

tick_all "$TASK1" "$CJS"
chk "一键全勾后无未勾项" "$(q "select count(*) from task_checklist where task_id=$TASK1 and checked=0;")" "0"
TS=$(csrf "/housekeeping/tasks/$TASK1" "$CJS")
chk "全勾后提交完成成功" "$(post "$BASE/housekeeping/tasks/$TASK1/done" "$CJS" "$TS")" "200"
chk "任务状态转为待验收(DONE)" "$(q "select status from housekeeping_task where id=$TASK1;")" "DONE"
chk "验收通过" "$(post "$BASE/housekeeping/tasks/$TASK1/inspect" "$CJS" "$TS" \
    --data-urlencode "action=approve" --data-urlencode "inspectRemark=第七批测试验收")" "200"
chk "任务状态转为已验收(APPROVED)" "$(q "select status from housekeeping_task where id=$TASK1;")" "APPROVED"

# 打回流程
TS=$(csrf /housekeeping/tasks "$CJS")
post "$BASE/housekeeping/tasks" "$CJS" "$TS" \
    --data-urlencode "roomId=$ROOM_ID" --data-urlencode "type=CHECKOUT" \
    --data-urlencode "assigneeId=$HK_ID" --data-urlencode "remark=第七批测试派工-打回" >/dev/null
TASK2=$(task_id_of "第七批测试派工-打回")
tick_all "$TASK2" "$CJS"
TS=$(csrf "/housekeeping/tasks/$TASK2" "$CJS")
post "$BASE/housekeeping/tasks/$TASK2/done" "$CJS" "$TS" >/dev/null
chk "打回前任务为待验收(DONE)" "$(q "select status from housekeeping_task where id=$TASK2;")" "DONE"
chk "不填原因打回：业务拒绝" "$(post "$BASE/housekeeping/tasks/$TASK2/inspect" "$CJS" "$TS" \
    --data-urlencode "action=reject")" "200"
has "提示必须填写打回原因" "$(flash '打回时必须填写打回原因')" "打回时必须填写打回原因"
chk "未填原因时状态未变" "$(q "select status from housekeeping_task where id=$TASK2;")" "DONE"
chk "填写原因打回成功" "$(post "$BASE/housekeeping/tasks/$TASK2/inspect" "$CJS" "$TS" \
    --data-urlencode "action=reject" --data-urlencode "inspectRemark=第七批测试打回")" "200"
chk "任务状态转为已打回(REJECTED)" "$(q "select status from housekeeping_task where id=$TASK2;")" "REJECTED"

echo
echo "=============================================="
echo " 4. 排班与工作量"
echo "=============================================="
SCH=$(get_page "$CJS" "/housekeeping/schedule?date=$FARDATE")
has "排班页含「早班」" "$SCH" "早班"
has "排班页含「中班」" "$SCH" "中班"
has "排班页含「夜班」" "$SCH" "夜班"
has "排班页含工作量表头「完成率」" "$SCH" "完成率"
has "工作量表含客房服务员姓名" "$SCH" "客房-王姐"
has "工作量表含客房主管姓名" "$SCH" "客房主管-李领班"

TH=$(csrf "/housekeeping/schedule?date=$FARDATE" "$CJH")
TS=$(csrf "/housekeeping/schedule?date=$FARDATE" "$CJS")
chk "服务员排班被拒(403)" "$(post "$BASE/housekeeping/schedule" "$CJH" "$TH" \
    --data-urlencode "workDate=$FARDATE" --data-urlencode "shift=MORNING" \
    --data-urlencode "staffId=$HK_ID" --data-urlencode "remark=第七批测试排班-越权")" "403"
chk "主管排班成功" "$(post "$BASE/housekeeping/schedule" "$CJS" "$TS" \
    --data-urlencode "workDate=$FARDATE" --data-urlencode "shift=MORNING" \
    --data-urlencode "staffId=$HK_ID" --data-urlencode "remark=第七批测试排班")" "200"
chk "排班已落库" "$(q "select count(*) from duty_schedule where work_date='$FARDATE' and shift='MORNING' and staff_id=$HK_ID;")" "1"
chk "重复排班：业务拒绝" "$(post "$BASE/housekeeping/schedule" "$CJS" "$TS" \
    --data-urlencode "workDate=$FARDATE" --data-urlencode "shift=MORNING" \
    --data-urlencode "staffId=$HK_ID" --data-urlencode "remark=第七批测试排班")" "200"
has "重复排班提示「已排过班」" "$(flash '已排过班')" "已排过班"
DUTY_ID=$(q "select id from duty_schedule where work_date='$FARDATE' and shift='MORNING' and staff_id=$HK_ID;")
chk "取消排班成功" "$(post "$BASE/housekeeping/schedule/$DUTY_ID/delete" "$CJS" "$TS" \
    --data-urlencode "date=$FARDATE")" "200"
chk "排班记录已删除" "$(q "select count(*) from duty_schedule where id=$DUTY_ID;")" "0"

echo
echo "=============================================="
echo " 5. 耗材出入库台账"
echo "=============================================="
su() { q "select $1 from supply_item where item_name='第七批测试耗材';"; }
chk "耗材页可访问" "$(code_of "$CJS" "$BASE/supplies")" "200"
TH=$(csrf /supplies "$CJH"); TS=$(csrf /supplies "$CJS")
chk "服务员耗材建档被拒(403)" "$(post "$BASE/supplies" "$CJH" "$TH" \
    --data-urlencode "itemName=第七批测试耗材-越权" --data-urlencode "stock=1")" "403"
chk "主管耗材建档成功" "$(post "$BASE/supplies" "$CJS" "$TS" \
    --data-urlencode "itemName=第七批测试耗材" --data-urlencode "category=PAPER" \
    --data-urlencode "unit=卷" --data-urlencode "stock=50" --data-urlencode "minStock=20" \
    --data-urlencode "unitCost=1.50" --data-urlencode "note=第七批测试")" "200"
SUP_ID=$(su id)
ge "耗材台账已落库" "$SUP_ID" "1"
chk "建档初始库存 50" "$(su stock)" "50"
chk "建档同时生成一笔入库流水" "$(q "select count(*) from supply_movement where item_id=$SUP_ID and type='IN';")" "1"

TH=$(csrf /supplies "$CJH"); TS=$(csrf /supplies "$CJS")
chk "服务员可领用耗材（无需主管）" "$(post "$BASE/supplies/$SUP_ID/move" "$CJH" "$TH" \
    --data-urlencode "type=OUT" --data-urlencode "quantity=8" \
    --data-urlencode "target=$ROOM_NO 房间")" "200"
chk "领用后库存 50-8=42" "$(su stock)" "42"
chk "领用流水 stock_after 快照 =42" "$(q "select stock_after from supply_movement where item_id=$SUP_ID order by id desc limit 1;")" "42"
chk "报废 2 卷" "$(post "$BASE/supplies/$SUP_ID/move" "$CJH" "$TH" \
    --data-urlencode "type=SCRAP" --data-urlencode "quantity=2" \
    --data-urlencode "remark=第七批测试报废")" "200"
chk "报废后库存 40" "$(su stock)" "40"
chk "盘点调整 -2" "$(post "$BASE/supplies/$SUP_ID/move" "$CJS" "$TS" \
    --data-urlencode "type=ADJUST" --data-urlencode "quantity=-2" \
    --data-urlencode "remark=第七批测试盘亏")" "200"
chk "盘点后库存 38" "$(su stock)" "38"

chk "超库存领用：业务拒绝" "$(post "$BASE/supplies/$SUP_ID/move" "$CJH" "$TH" \
    --data-urlencode "type=OUT" --data-urlencode "quantity=999")" "200"
has "超领提示「超出当前库存」" "$(flash '超出当前库存')" "超出当前库存"
chk "超领后库存不变" "$(su stock)" "38"
chk "有流水的耗材不能删除" "$(post "$BASE/supplies/$SUP_ID/delete" "$CJS" "$TS")" "200"
has "删除被拒理由含「不能删除」" "$(flash '不能删除')" "不能删除"
chk "耗材仍在台账" "$(su id)" "$SUP_ID"
has "耗材明细页可访问" "$(get_page "$CJS" "/supplies/$SUP_ID")" "第七批测试耗材"

echo
echo "=============================================="
echo " 6. 移动端扫码上报（/hk）"
echo "=============================================="
chk "未登录访问 /hk 被拦截（302 到登录页）" "$(anon_code /hk)" "302"
chk "服务员登录后 /hk 可访问" "$(code_of "$CJH" "$BASE/hk")" "200"
chk "主管 /hk 可访问" "$(code_of "$CJS" "$BASE/hk")" "200"
chk "房间操作台可访问" "$(code_of "$CJH" "$BASE/hk/room/$ROOM2_ID")" "200"
has "操作台含房号" "$(page_text)" "$(q "select room_no from room where id=$ROOM2_ID;")"
has "操作台含「报修」入口" "$(page_text)" "报修"

TOKEN=$(q "select scan_token from room where id=$ROOM2_ID;")
ge "房间已发放扫码令牌" "${#TOKEN}" "10"
SCAN_RES=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code} %{redirect_url}' \
           -b "$CJH" -c "$CJH" "$BASE/hk/scan/$TOKEN")
chk "扫码员工码跳转到房间操作台" "$SCAN_RES" "302 $BASE/hk/room/$ROOM2_ID"
chk "无效令牌跳回 /hk" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
     -b "$CJH" "$BASE/hk/scan/__badtoken__")" "302"

TH=$(csrf "/hk/room/$ROOM2_ID" "$CJH")
chk "现场报修成功" "$(post "$BASE/hk/room/$ROOM2_ID/report" "$CJH" "$TH" \
    --data-urlencode "description=第七批测试报修-马桶漏水")" "200"
chk "报修工单已生成" "$(q "select count(*) from maintenance_order where description LIKE '%第七批测试报修%';")" "1"
chk "登记失物成功" "$(post "$BASE/hk/room/$ROOM2_ID/found" "$CJH" "$TH" \
    --data-urlencode "itemName=第七批测试失物-充电器" --data-urlencode "description=第七批测试捡到")" "200"
chk "失物已登记" "$(q "select count(*) from lost_found where item_name LIKE '%第七批测试失物%';")" "1"
chk "现场改房态为待清洁" "$(post "$BASE/hk/room/$ROOM2_ID/status" "$CJH" "$TH" \
    --data-urlencode "status=CLEANING")" "200"
chk "房态已改为 CLEANING" "$(q "select status from room where id=$ROOM2_ID;")" "CLEANING"
post "$BASE/hk/room/$ROOM2_ID/status" "$CJH" "$TH" --data-urlencode "status=AVAILABLE" >/dev/null
chk "房态已还原为 AVAILABLE" "$(q "select status from room where id=$ROOM2_ID;")" "AVAILABLE"

chk "现场挂账成功（房间有在住订单）" "$(post "$BASE/hk/room/$INHOUSE_ID/minibar" "$CJH" "$TH" \
    --data-urlencode "itemName=第七批测试迷你吧" --data-urlencode "quantity=1" \
    --data-urlencode "unitPrice=8.00")" "200"
chk "挂账已落库" "$(q "select count(*) from minibar_item where item_name LIKE '%第七批测试迷你吧%';")" "1"
chk "无在住房挂账被拒" "$(post "$BASE/hk/room/$ROOM2_ID/minibar" "$CJH" "$TH" \
    --data-urlencode "itemName=第七批测试迷你吧-无房" --data-urlencode "quantity=1")" "200"
has "无在住提示「没有在住订单」" "$(flash '没有在住订单')" "没有在住订单"

# 移动端一键完成工单：先把深度清洁单勾完，再从操作台提交
tick_all "$TASKD" "$CJH"
TH=$(csrf "/hk/room/$ROOM_ID" "$CJH")
chk "操作台一键完成工单成功" "$(post "$BASE/hk/room/$ROOM_ID/task-done" "$CJH" "$TH" \
    --data-urlencode "taskId=$TASKD")" "200"
chk "该工单已转为待验收(DONE)" "$(q "select status from housekeeping_task where id=$TASKD;")" "DONE"

echo
echo "=============================================="
echo " 7. 站内待办中心"
echo "=============================================="
chk "待办中心可访问（主管）" "$(code_of "$CJS" "$BASE/todos")" "200"
TODO=$(page_text)
has "待办页含条目「待完成工单」" "$TODO" "待完成工单"
has "待办页含条目「耗材低于安全库存」" "$TODO" "耗材低于安全库存"
has "待办页含条目「布草低于安全库存」" "$TODO" "布草低于安全库存"
has "待办页说明「实时统计」" "$TODO" "实时统计"
chk "服务员也可访问待办中心" "$(code_of "$CJH" "$BASE/todos")" "200"
has "服务员待办页显示「我的待办工单」" "$(page_text)" "我的待办工单"
lacks "服务员待办页不显示调度类条目「超时未完成」" "$(page_text)" "超时未完成"
# 侧栏角标：存在未完成工单时应渲染 nav-badge
has "侧栏渲染待办角标（nav-badge）" "$(get_page "$CJS" /housekeeping/tasks)" 'nav-badge'

echo
echo "=============================================="
echo " 8. 布草洗涤外包结算"
echo "=============================================="
T=$(csrf /laundry "$CJ")
chk "新建测试布草（在库 100）" "$(post "$BASE/laundry/linen" "$CJ" "$T" \
    --data-urlencode "itemName=第七批测试布草" --data-urlencode "category=BEDDING" \
    --data-urlencode "inStock=100" --data-urlencode "minStock=20" --data-urlencode "note=第七批测试")" "200"
LINEN_ID=$(q "select id from linen_stock where item_name='第七批测试布草';")
ge "测试布草已落库" "$LINEN_ID" "1"
lin() { q "select $1 from linen_stock where id=$LINEN_ID;"; }
chk "测试布草在库 100" "$(lin in_stock)" "100"

chk "外送页可访问" "$(code_of "$CJS" "$BASE/laundry/outsourcing")" "200"
TH=$(csrf /laundry/outsourcing "$CJH"); TS=$(csrf /laundry/outsourcing "$CJS")
chk "服务员建外送批次被拒(403)" "$(post "$BASE/laundry/outsourcing" "$CJH" "$TH" \
    --data-urlencode "vendor=第七批测试洗涤公司-越权" --data-urlencode "linenId=$LINEN_ID" \
    --data-urlencode "quantity=5" --data-urlencode "price=3.00")" "403"
chk "超量送洗被拒" "$(post "$BASE/laundry/outsourcing" "$CJS" "$TS" \
    --data-urlencode "vendor=第七批测试洗涤公司" --data-urlencode "linenId=$LINEN_ID" \
    --data-urlencode "quantity=999" --data-urlencode "price=3.00" \
    --data-urlencode "remark=第七批测试")" "200"
has "超量提示「无法送洗」" "$(flash '无法送洗')" "无法送洗"
chk "被拒后未生成批次" "$(q "select count(*) from outsource_batch where vendor LIKE '%第七批测试洗涤公司%';")" "0"

chk "主管创建外送批次成功（10 件 × 3.50）" "$(post "$BASE/laundry/outsourcing" "$CJS" "$TS" \
    --data-urlencode "vendor=第七批测试洗涤公司" --data-urlencode "linenId=$LINEN_ID" \
    --data-urlencode "quantity=10" --data-urlencode "price=3.50" \
    --data-urlencode "remark=第七批测试外送")" "200"
BATCH=$(q "select id from outsource_batch where vendor='第七批测试洗涤公司' order by id desc limit 1;")
ge "批次已落库" "$BATCH" "1"
chk "批次号以 WX 开头" "$(q "select LEFT(batch_no,2) from outsource_batch where id=$BATCH;")" "WX"
chk "批次状态为已送出(SENT)" "$(q "select status from outsource_batch where id=$BATCH;")" "SENT"
chk "批次总件数 10" "$(q "select total_qty from outsource_batch where id=$BATCH;")" "10"
chk "批次总金额 35.00" "$(q "select total_amount from outsource_batch where id=$BATCH;")" "35.00"
chk "送出后布草在库 100-10=90" "$(lin in_stock)" "90"
chk "送出后布草洗涤中 10" "$(lin in_wash)" "10"
# 交接单详情页必须真的渲染出洗涤明细行 —— 品名只会在 batch.items 循环里出现。
# 这一条曾漏网：items 是 LAZY 集合而 OSIV 已关，模板迭代时抛 LazyInitializationException，
# 页面前半截（含「布草外送交接单」字样）已按 200 提交，随后错误页被追写进同一响应体，
# 于是「has 布草外送交接单」匹配成功、HTTP 也是 200，页面其实整页是坏的。
pageok "交接单可打印（洗涤明细行已渲染）" "$CJS" "/laundry/outsourcing/$BATCH" \
       "布草外送交接单" "第七批测试布草" "35.00" "已送出"

TS=$(csrf "/laundry/outsourcing/$BATCH" "$CJS")
chk "未回收即结算：业务拒绝" "$(post "$BASE/laundry/outsourcing/$BATCH/settle" "$CJS" "$TS" \
    --data-urlencode "amount=35.00")" "200"
has "拒绝提示「不能结算」" "$(flash '不能结算')" "不能结算"
chk "被拒后状态仍为已送出" "$(q "select status from outsource_batch where id=$BATCH;")" "SENT"

chk "回收布草" "$(post "$BASE/laundry/outsourcing/$BATCH/return" "$CJS" "$TS")" "200"
chk "批次状态为已收回(RETURNED)" "$(q "select status from outsource_batch where id=$BATCH;")" "RETURNED"
pageok "已收回状态交接单可打印" "$CJS" "/laundry/outsourcing/$BATCH" \
       "布草外送交接单" "第七批测试布草" "已收回"
chk "回收后在库回到 100" "$(lin in_stock)" "100"
chk "回收后洗涤中回到 0" "$(lin in_wash)" "0"
chk "结算成功（￥35.00）" "$(post "$BASE/laundry/outsourcing/$BATCH/settle" "$CJS" "$TS" \
    --data-urlencode "amount=35.00")" "200"
chk "批次状态为已结算(SETTLED)" "$(q "select status from outsource_batch where id=$BATCH;")" "SETTLED"
pageok "已结算状态交接单仍可打印" "$CJS" "/laundry/outsourcing/$BATCH" \
       "布草外送交接单" "第七批测试布草" "已结算"

# 取消送出：数量退回在库
TS=$(csrf /laundry/outsourcing "$CJS")
post "$BASE/laundry/outsourcing" "$CJS" "$TS" \
    --data-urlencode "vendor=第七批测试洗涤公司-待取消" --data-urlencode "linenId=$LINEN_ID" \
    --data-urlencode "quantity=6" --data-urlencode "price=3.00" --data-urlencode "remark=第七批测试" >/dev/null
BATCH2=$(q "select id from outsource_batch where vendor='第七批测试洗涤公司-待取消' order by id desc limit 1;")
chk "取消前洗涤中 6" "$(lin in_wash)" "6"
post "$BASE/laundry/outsourcing/$BATCH2/cancel" "$CJS" "$TS" \
    --data-urlencode "reason=第七批测试取消" >/dev/null
chk "取消后布草退回在库（洗涤中 0）" "$(lin in_wash)" "0"
chk "取消后批次留痕为已收回" "$(q "select status from outsource_batch where id=$BATCH2;")" "RETURNED"

echo
echo "=============================================="
echo " 9. 权限边界"
echo "=============================================="
for p in /payments /invoices /receivables /shift /groups /rate-plans /bills /customers; do
  chk "服务员进不了 $p (403)" "$(code_of "$CJH" "$BASE$p")" "403"
  chk "客房主管也进不了 $p (403，继承是单向的)" "$(code_of "$CJS" "$BASE$p")" "403"
done
chk "服务员进不了 /users (403)" "$(code_of "$CJH" "$BASE/users")" "403"
chk "服务员进不了 /logs (403)" "$(code_of "$CJH" "$BASE/logs")" "403"
chk "主管进不了 /users (403)" "$(code_of "$CJS" "$BASE/users")" "403"
chk "前台进不了 /users (403)" "$(code_of "$CJR" "$BASE/users")" "403"

echo
echo "=============================================="
echo " 10. 页面渲染"
echo "=============================================="
pageok "派工与验收页"      "$CJS" "/housekeeping/tasks"      "检查单"
pageok "派工单详情页"      "$CJS" "/housekeeping/tasks/$TASK1" "验收" "检查单"
pageok "排班与工作量页"    "$CJS" "/housekeeping/schedule"   "新增排班"
pageok "清洁标准检查单页"  "$CJS" "/checklists"
pageok "耗材出入库台账页"  "$CJS" "/supplies"
pageok "现场上报入口页"    "$CJS" "/hk"
pageok "房间操作台"        "$CJS" "/hk/room/$ROOM_ID"
pageok "待办中心"          "$CJS" "/todos"
pageok "布草外送批次列表页" "$CJS" "/laundry/outsourcing"    "交接单"
# 回归：扫码消费页此前有一处 th:text 表达式写法错误，整页 500 / 渲染成错误页
pageok "扫码消费页（历史缺陷回归）" "$CJ" "/consumables"     "qr-card" "员工入口"
has "侧栏含「待办中心」导航" "$(get_page "$CJS" /housekeeping/tasks)" "待办中心"
has "侧栏含「排班与工作量」导航" "$(get_page "$CJS" /housekeeping/tasks)" "排班与工作量"
has "侧栏含「耗材出入库」导航" "$(get_page "$CJS" /housekeeping/tasks)" "耗材出入库"
has "侧栏含「布草外送」导航" "$(get_page "$CJS" /housekeeping/tasks)" "布草外送"

echo
echo "=============================================="
printf " 第七批验收：\033[32m通过 %d\033[0m / \033[31m失败 %d\033[0m\n" "$PASS" "$FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ]
