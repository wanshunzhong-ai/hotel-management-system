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
echo "完成。"
