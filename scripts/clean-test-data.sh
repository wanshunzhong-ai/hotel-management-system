#!/usr/bin/env bash
# 清理端到端测试（func-test.sh / opt1-test.sh）产生的订单、账单与房态副作用。
#
# 判定方式：订单备注中包含「测试」二字（func-test 用「功能测试单」，opt1-test 用「XX测试」）。
# 处理内容：
#   1. 删除这些订单的账单明细与账单
#   2. 删除订单本身
#   3. 若订单占用的房间已无其它有效订单（BOOKED / CHECKED_IN），
#      且房态被测试改成了「已入住 / 待清洁」，则恢复为「空闲」
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
