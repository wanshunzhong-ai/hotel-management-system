#!/usr/bin/env bash
# 清理端到端测试（func-test.sh / opt1-test.sh）产生的订单、账单与房态副作用。
#
# 判定方式：订单备注中包含「测试」二字（func-test 用「功能测试单」，opt1-test 用「XX测试」）。
# 处理内容：
#   1. 删除这些订单的账单明细与账单
#   2. 删除订单本身
#   3. 若订单占用的房间已无其它有效订单（BOOKED / CHECKED_IN），
#      且房态被测试改成了「已入住 / 待清洁」，则恢复为「空闲」
#   4. 房态兜底：① 无在住订单却显示「已入住」的脏数据直接还原；
#      ② 「换房」遗留的旧房（订单已改指新房，不再被任何订单引用）按演示基线白名单复位
#         —— 基线非空闲房只有 606（维修中）、607（待清洁）
#
# 用法：
#   bash scripts/clean-test-data.sh                 # 直接执行
#   MYSQL_BIN=/path/to/mysql bash scripts/clean-test-data.sh
set -u
MYSQL_BIN="${MYSQL_BIN:-/d/SoftWare/MySQL/MySQL Server 9.0/bin/mysql.exe}"
DB="${HMS_DB:-hotel_management}"
USER="${HMS_DB_USER:-root}"
PASS="${HMS_DB_PASS:-123456}"
HOST="${HMS_DB_HOST:-127.0.0.1}"
PORT="${HMS_DB_PORT:-3306}"

if [ ! -x "$MYSQL_BIN" ]; then
  echo "找不到 mysql 客户端：$MYSQL_BIN（可用 MYSQL_BIN=... 指定）" >&2
  exit 1
fi

run() { MSYS_NO_PATHCONV=1 "$MYSQL_BIN" --default-character-set=utf8mb4 -u "$USER" -p"$PASS" \
        -h "$HOST" -P "$PORT" -D "$DB" --batch --raw -e "$1" 2>/dev/null; }

echo "=== 待清理的测试订单 ==="
run "SELECT id, order_no, status, room_id, remark FROM reservation WHERE remark LIKE '%测试%' ORDER BY id;"

echo
echo "=== 执行清理 ==="
# 第四批（客房部模块）的表在旧库中可能还不存在，先探测再决定是否清理
HAS_TABLES="$(run "SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE()
                     AND table_name IN ('housekeeping_task','minibar_item','maintenance_order','lost_found');" \
              | grep -E '^[0-9]+' | head -1 | tr -dc '0-9')"
[ -z "$HAS_TABLES" ] && HAS_TABLES=0

# 第五批（洗衣布草 / 扫码消费 / 查房评分）的表
has5() { # $1=表名 → 1/0
  local n
  n="$(run "SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = '$1';" \
       | grep -E '^[0-9]+' | head -1 | tr -dc '0-9')"
  [ "${n:-0}" = "1" ] && echo 1 || echo 0
}
HAS5_LAUNDRY="$(has5 laundry_order)"
HAS5_LINEN="$(has5 linen_stock)"
HAS5_INSPECTION="$(has5 room_inspection)"
HAS5_CONSUMABLE="$(has5 consumable_item)"

# 第七批（客房主管 / 排班 / 清洁检查单 / 耗材台账 / 现场上报 / 布草外送）的表。
#
# ⚠️ 外键依赖顺序（这一块的位置不是随便放的）：
#   task_checklist.task_id  → housekeeping_task ：必须先删检查单，再删下面的派工单；
#   supply_movement.item_id → supply_item       ：必须先删流水，再删台账；
#   outsource_item.batch_id → outsource_batch   ：必须先删明细，再删批次。
# 一旦顺序颠倒，DELETE 会撞外键约束而整体失败（run() 把 stderr 丢弃，会静默漏删）。
has7() { # $1=表名 → 1/0
  local n
  n="$(run "SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = '$1';" \
       | grep -E '^[0-9]+' | head -1 | tr -dc '0-9')"
  [ "${n:-0}" = "1" ] && echo 1 || echo 0
}
HAS7_TASKCHECK="$(has7 task_checklist)"
HAS7_DUTY="$(has7 duty_schedule)"
HAS7_SUPPLY="$(has7 supply_item)"
HAS7_OUTSOURCE="$(has7 outsource_batch)"

if [ "$HAS7_TASKCHECK" = "1" ]; then
  # 测试派工单的检查单快照：按 task_id 反查（task_checklist 里没有「测试」字样可依），
  # 必须早于下面那句 DELETE FROM housekeeping_task。
  run "DELETE tc FROM task_checklist tc
       JOIN housekeeping_task t ON tc.task_id = t.id
       WHERE t.remark LIKE '%测试%';"
else
  echo "（跳过 task_checklist：当前库尚未创建）"
fi

if [ "$HAS7_DUTY" = "1" ]; then
  # 排班按测试备注清理；另用「测试专用日期」（今天 +30 天）兜底——
  # 该日期基线为空且日常无人使用，即便上一轮中断留下无备注的残排也能收干净。
  run "DELETE FROM duty_schedule
       WHERE remark LIKE '%测试%'
          OR work_date = DATE_ADD(CURDATE(), INTERVAL 30 DAY);"
else
  echo "（跳过 duty_schedule：当前库尚未创建）"
fi

if [ "$HAS7_OUTSOURCE" = "1" ]; then
  # 关键：仍在「已送出」状态的测试批次，布草还挂在「洗涤中」，
  # 直接删批次会把数量永久留在洗涤中（在库凭空少一批）。先把数量退回在库再删。
  # 已收回 / 已结算 / 已取消的批次，库存早前已回补，不能再退。
  run "UPDATE linen_stock ls
       JOIN outsource_item oi ON oi.linen_stock_id = ls.id
       JOIN outsource_batch ob ON oi.batch_id = ob.id
       SET ls.in_wash  = GREATEST(0, ls.in_wash - oi.quantity),
           ls.in_stock = ls.in_stock + oi.quantity
       WHERE ob.status = 'SENT'
         AND (ob.vendor LIKE '%测试%' OR ob.remark LIKE '%测试%');"
  run "DELETE oi FROM outsource_item oi
       JOIN outsource_batch ob ON oi.batch_id = ob.id
       WHERE ob.vendor LIKE '%测试%' OR ob.remark LIKE '%测试%';"
  run "DELETE FROM outsource_batch
       WHERE vendor LIKE '%测试%' OR remark LIKE '%测试%';"
else
  echo "（跳过 outsource_batch：当前库尚未创建）"
fi

if [ "$HAS7_SUPPLY" = "1" ]; then
  # 耗材：先删测试品名的流水（外键），再删台账。
  # 种子 12 个品名与它们的「建档初始入库」流水一律保留。
  run "DELETE sm FROM supply_movement sm
       JOIN supply_item si ON sm.item_id = si.id
       WHERE si.item_name LIKE '%测试%';"
  run "DELETE FROM supply_item WHERE item_name LIKE '%测试%';"
else
  echo "（跳过 supply_item：当前库尚未创建）"
fi

# 客房部表：先清理迷你吧挂账（外键指向 reservation）与工单，再删订单
if [ "$HAS_TABLES" = "4" ]; then
  run "DELETE FROM housekeeping_task WHERE remark LIKE '%测试%';"
  run "DELETE FROM minibar_item
       WHERE note LIKE '%测试%'
          OR item_name LIKE '%测试%'
          OR reservation_id IN (SELECT id FROM reservation WHERE remark LIKE '%测试%');"
  run "CREATE TEMPORARY TABLE tmp_test_mt AS
         SELECT id, room_id FROM maintenance_order WHERE description LIKE '%测试%';
       DELETE FROM maintenance_order WHERE id IN (SELECT id FROM tmp_test_mt);
       UPDATE room r SET r.status = 'AVAILABLE'
       WHERE r.id IN (SELECT room_id FROM tmp_test_mt)
         AND r.status IN ('MAINTENANCE','CLEANING')
         AND NOT EXISTS (SELECT 1 FROM reservation v
                         WHERE v.room_id = r.id AND v.status IN ('BOOKED','CHECKED_IN'));"
  run "DELETE FROM lost_found WHERE description LIKE '%测试%' OR item_name LIKE '%测试%';"
else
  echo "（跳过客房部表：当前库尚未创建 housekeeping_task / minibar_item / maintenance_order / lost_found）"
fi

# 第五批：洗衣单外键同样指向 reservation，必须早于订单删除；布草/目录/查房按测试标记删除
if [ "$HAS5_LAUNDRY" = "1" ]; then
  run "DELETE FROM laundry_order
       WHERE note LIKE '%测试%'
          OR item_name LIKE '%测试%'
          OR reservation_id IN (SELECT id FROM reservation WHERE remark LIKE '%测试%');"
else
  echo "（跳过 laundry_order：当前库尚未创建）"
fi
if [ "$HAS5_LINEN" = "1" ]; then
  # 只删测试品名；床单/被套等初始台账保留
  run "DELETE FROM linen_stock WHERE item_name LIKE '%测试%';"
fi
if [ "$HAS5_CONSUMABLE" = "1" ]; then
  run "DELETE FROM consumable_item WHERE item_name LIKE '%测试%';"
fi
if [ "$HAS5_INSPECTION" = "1" ]; then
  # 查房不合格会把房间置「待清洁」，清理时一并还原
  run "CREATE TEMPORARY TABLE tmp_test_insp AS
         SELECT id, room_id FROM room_inspection WHERE remark LIKE '%测试%';
       DELETE FROM room_inspection WHERE id IN (SELECT id FROM tmp_test_insp);
       UPDATE room r SET r.status = 'AVAILABLE'
       WHERE r.id IN (SELECT room_id FROM tmp_test_insp)
         AND r.status = 'CLEANING'
         AND NOT EXISTS (SELECT 1 FROM reservation v
                         WHERE v.room_id = r.id AND v.status IN ('BOOKED','CHECKED_IN'));"
fi

# 第六批（收款流水 / 发票 / 交接班 / 房价方案）的表。
# payment_record 与 invoice 的外键分别指向 reservation 与 bill，
# 必须在下面删除订单与账单**之前**清理，否则会撞外键约束。
has6() { # $1=表名 → 1/0
  local n
  n="$(run "SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = '$1';" \
       | grep -E '^[0-9]+' | head -1 | tr -dc '0-9')"
  [ "${n:-0}" = "1" ] && echo 1 || echo 0
}
HAS6_PAYMENT="$(has6 payment_record)"
HAS6_INVOICE="$(has6 invoice)"
HAS6_SHIFT="$(has6 shift_handover)"
HAS6_RATEPLAN="$(has6 rate_plan)"

if [ "$HAS6_PAYMENT" = "1" ]; then
  run "DELETE FROM payment_record
       WHERE remark LIKE '%测试%'
          OR reservation_id IN (SELECT id FROM reservation WHERE remark LIKE '%测试%')
          OR bill_id IN (SELECT b.id FROM bill b
                         JOIN reservation r ON b.reservation_id = r.id
                         WHERE r.remark LIKE '%测试%');"
else
  echo "（跳过 payment_record：当前库尚未创建）"
fi
if [ "$HAS6_INVOICE" = "1" ]; then
  run "DELETE FROM invoice
       WHERE title LIKE '%测试%'
          OR tax_no LIKE '%测试%'
          OR bill_id IN (SELECT b.id FROM bill b
                         JOIN reservation r ON b.reservation_id = r.id
                         WHERE r.remark LIKE '%测试%');"
else
  echo "（跳过 invoice：当前库尚未创建）"
fi
if [ "$HAS6_SHIFT" = "1" ]; then
  # 交接班快照是测试固化的临时数据，按备注清理（种子/正式交班记录不含「测试」）
  run "DELETE FROM shift_handover WHERE remark LIKE '%测试%';"
else
  echo "（跳过 shift_handover：当前库尚未创建）"
fi
if [ "$HAS6_RATEPLAN" = "1" ]; then
  # 只删测试新建的方案；种子方案（CORP-* / MEMBER-*）保留
  run "DELETE FROM rate_plan WHERE code LIKE 'TEST-%' OR name LIKE '%测试%';"
fi

run "
CREATE TEMPORARY TABLE tmp_test_res AS
  SELECT id, room_id FROM reservation WHERE remark LIKE '%测试%';

DELETE bi FROM bill_item bi JOIN bill b ON bi.bill_id = b.id
  WHERE b.reservation_id IN (SELECT id FROM tmp_test_res);
DELETE FROM bill WHERE reservation_id IN (SELECT id FROM tmp_test_res);
DELETE FROM reservation WHERE id IN (SELECT id FROM tmp_test_res);

-- 房间已无有效订单（已预订/已入住）且被测试改成了「已入住/待清洁」时恢复空闲
UPDATE room r
SET r.status = 'AVAILABLE'
WHERE r.id IN (SELECT room_id FROM tmp_test_res)
  AND r.status IN ('OCCUPIED', 'CLEANING')
  AND NOT EXISTS (
        SELECT 1 FROM reservation v
        WHERE v.room_id = r.id AND v.status IN ('BOOKED', 'CHECKED_IN')
      );

-- 被测试误改成「待清洁」但仍有客人在住的房间，还原为「已入住」
UPDATE room r
SET r.status = 'OCCUPIED'
WHERE r.status = 'CLEANING'
  AND EXISTS (SELECT 1 FROM reservation v
              WHERE v.room_id = r.id AND v.status = 'CHECKED_IN');
"

# 兜底：上面只还原「测试订单占用过的房间」。但有些测试是直接改房态的（房态图改状态、
# 报修完工、查房不合格……），压根没建订单，那些房间就漏了。
# 「已入住」是可判定的：没有在住订单却显示已入住，一定是脏数据 → 直接还原。
run "
UPDATE room r
SET r.status = 'AVAILABLE'
WHERE r.status = 'OCCUPIED'
  AND NOT EXISTS (SELECT 1 FROM reservation v
                  WHERE v.room_id = r.id AND v.status = 'CHECKED_IN');
"

# 再兜底：「换房」会把**旧房**置为待清洁，而订单已经改指新房 —— 旧房从此不被任何订单引用，
# 上一条按「测试订单房间」反查的语句永远看不到它，会永久残留。
# 这里按「演示基线的非空闲房」做白名单，其余没有有效订单的非空闲房一律复位为空闲。
# 基线（DataInitializer 种子）：606 维修中、607 待清洁；201/302/402/502 由在住订单撑着，
# 会被上面的 NOT EXISTS 条件自动跳过，无需列入。
run "
UPDATE room r
SET r.status = 'AVAILABLE'
WHERE r.status <> 'AVAILABLE'
  AND r.room_no NOT IN ('606', '607')
  AND NOT EXISTS (SELECT 1 FROM reservation v
                  WHERE v.room_id = r.id AND v.status IN ('BOOKED', 'CHECKED_IN'));
"

echo
echo "=== 清理后的数据概览 ==="
run "
SELECT 'reservations' AS item, COUNT(*) AS cnt FROM reservation
UNION ALL SELECT 'bills', COUNT(*) FROM bill
UNION ALL SELECT 'bill_items', COUNT(*) FROM bill_item
UNION ALL SELECT 'rooms', COUNT(*) FROM room
UNION ALL SELECT 'customers', COUNT(*) FROM customer
UNION ALL SELECT 'users', COUNT(*) FROM sys_user;
SELECT status, COUNT(*) AS cnt FROM room GROUP BY status ORDER BY status;
SELECT IFNULL(SUM(total_amount), 0) AS settled_revenue FROM bill WHERE status = 'SETTLED';
"
if [ "$HAS_TABLES" = "4" ]; then
  echo
  echo "=== 客房部模块表概览 ==="
  run "
SELECT 'housekeeping_task' AS item, COUNT(*) AS cnt FROM housekeeping_task
UNION ALL SELECT 'minibar_item', COUNT(*) FROM minibar_item
UNION ALL SELECT 'maintenance_order', COUNT(*) FROM maintenance_order
UNION ALL SELECT 'lost_found', COUNT(*) FROM lost_found
UNION ALL SELECT 'operation_log', COUNT(*) FROM operation_log;
"
fi
if [ "$HAS5_LAUNDRY" = "1" ] || [ "$HAS5_LINEN" = "1" ] || [ "$HAS5_INSPECTION" = "1" ] || [ "$HAS5_CONSUMABLE" = "1" ]; then
  echo
  echo "=== 第五批模块表概览 ==="
  run "
SELECT 'laundry_order' AS item, COUNT(*) AS cnt FROM laundry_order
UNION ALL SELECT 'linen_stock', COUNT(*) FROM linen_stock
UNION ALL SELECT 'consumable_item', COUNT(*) FROM consumable_item
UNION ALL SELECT 'room_inspection', COUNT(*) FROM room_inspection
UNION ALL SELECT 'room-with-scan-token', COUNT(*) FROM room WHERE scan_token IS NOT NULL;
"
fi
if [ "$HAS6_PAYMENT" = "1" ] || [ "$HAS6_INVOICE" = "1" ] || [ "$HAS6_SHIFT" = "1" ] || [ "$HAS6_RATEPLAN" = "1" ]; then
  echo
  echo "=== 第六批模块表概览 ==="
  run "
SELECT 'payment_record' AS item, COUNT(*) AS cnt FROM payment_record
UNION ALL SELECT 'invoice', COUNT(*) FROM invoice
UNION ALL SELECT 'shift_handover', COUNT(*) FROM shift_handover
UNION ALL SELECT 'rate_plan', COUNT(*) FROM rate_plan
UNION ALL SELECT 'unpaid-bills(应收)', COUNT(*) FROM bill WHERE status = 'UNPAID';
"
fi
if [ "$HAS7_TASKCHECK" = "1" ] || [ "$HAS7_DUTY" = "1" ] || [ "$HAS7_SUPPLY" = "1" ] || [ "$HAS7_OUTSOURCE" = "1" ]; then
  echo
  echo "=== 第七批模块表概览 ==="
  echo "（基线：checklist_item 15 项 / supply_item 12 品名 / duty_schedule 0 / outsource_batch 0；"
  echo "  linen_stock 洗涤中应全为 0，否则说明有测试外送批次未收干净）"
  run "
SELECT 'checklist_item' AS item, COUNT(*) AS cnt FROM checklist_item
UNION ALL SELECT 'supply_item', COUNT(*) FROM supply_item
UNION ALL SELECT 'supply_movement', COUNT(*) FROM supply_movement
UNION ALL SELECT 'duty_schedule', COUNT(*) FROM duty_schedule
UNION ALL SELECT 'outsource_batch', COUNT(*) FROM outsource_batch
UNION ALL SELECT 'outsource_item', COUNT(*) FROM outsource_item
UNION ALL SELECT 'task_checklist', COUNT(*) FROM task_checklist
UNION ALL SELECT 'supervisor(账号)', COUNT(*) FROM sys_user WHERE role = 'HOUSEKEEPING_SUPERVISOR';
SELECT 'linen 洗涤中合计' AS item, IFNULL(SUM(in_wash), 0) AS cnt FROM linen_stock;
"
fi
echo
echo "=== 房态残留自查 ==="
echo "（606 维修中 / 607 待清洁 是演示数据的基线状态，属正常；其余非空闲房间请人工确认）"
run "
SELECT r.room_no AS 房号, r.status AS 房态,
       IFNULL((SELECT COUNT(*) FROM reservation v
               WHERE v.room_id = r.id AND v.status IN ('BOOKED','CHECKED_IN')), 0) AS 有效订单
FROM room r
WHERE r.status <> 'AVAILABLE'
ORDER BY r.room_no;
"
echo "完成。"
