#!/usr/bin/env bash
# 酒店管理系统 —— 第五批（洗衣布草 / 扫码消费 / 查房评分 / 房态大屏）验收测试
#
#  1. 洗衣与布草：客衣收件（必须有在住订单）→ 洗涤 → 送回 / 取消；布草内部周转不计费
#     布草库存：入库 / 领用 / 回库 / 报废 三态守恒 + 超额一律拒绝 + 安全库存预警
#  2. 扫码消费：目录 CRUD + 房间二维码 PNG + 免登录下单（来源=住客扫码）
#     安全边界：没有在住订单的房间，即使拿到合法 CSRF 令牌也会被拒绝
#  3. 退房结账：客衣费与扫码挂账自动并入账单其他消费；
#     已取消的客衣单不并入，已送回的要并入（仅「取消」不收钱）
#  4. 查房评分：四项打分 / 合格线 / 不合格自动转待清洁 + 自动派整改单 / 越界分值拒绝
#  5. 房态大屏：/api/board 与房态一览口径一致（可售/已售/出租率），未登录一律 302
#  6. 审计留痕：上述动作全部进 operation_log
#
# 前提：应用已启动（默认 http://127.0.0.1:8081，可用 HMS_BASE 覆盖）
# 数据：脚本自带「上一轮第五批测试数据」清理，可重复运行；
#       全量跑完后建议执行 scripts/clean-test-data.sh 做一次收尾清理
set -u
BASE="${HMS_BASE:-http://127.0.0.1:8081}"
TMP="/tmp/hms-opt5"
mkdir -p "$TMP"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m[PASS]\033[0m %s\n" "$1"; }
no()   { FAIL=$((FAIL+1)); printf "  \033[31m[FAIL]\033[0m %s\n" "$1"; }
chk()  { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else no "$1 期望[$3] 实际[$2]"; fi; }
has()  { if printf '%s' "$2" | grep -q -- "$3"; then ok "$1"; else no "$1（未匹配 [$3]）"; fi; }
lacks(){ if printf '%s' "$2" | grep -q -- "$3"; then no "$1（不应出现 [$3]）"; else ok "$1"; fi; }

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
# 免登录取页面（不携带任何登录态）
anon_page() { rm -f "$2"; curl -s -c "$2" -o "$3" -w '%{http_code}' "$1"; }

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
idle_room() {
  q "select r.id from room r
     where r.status='AVAILABLE'
       and not exists (select 1 from reservation v where v.room_id=r.id and v.status='CHECKED_IN')
     order by r.id limit 1;"
}
scan_token_of() { q "select scan_token from room where id=$1;"; }
jnum() { printf '%s' "$2" | grep -o "\"$1\":[-0-9.]*" | head -1 | cut -d: -f2; }

echo "=============================================="
echo " 0. 准备：三种角色登录 + 新表探测"
echo "=============================================="
CJ="$TMP/admin.cookies"; CJR="$TMP/reception.cookies"; CJH="$TMP/housekeeping.cookies"
chk "登录 admin/admin123 返回 302"        "$(login admin admin123 "$CJ")" "302"
chk "登录 reception/123456 返回 302"      "$(login reception 123456 "$CJR")" "302"
chk "登录 housekeeping/123456 返回 302"   "$(login housekeeping 123456 "$CJH")" "302"

HK_ID=$(q "select id from sys_user where username='housekeeping';")
ADMIN_NAME=$(q "select real_name from sys_user where username='admin';")
CUST_NAME=$(q "select real_name from sys_user where username='housekeeping';")
CID=$(q "select id from customer order by id limit 1;")
TODAY=$(date +%Y-%m-%d); D1=$(date -d "+1 day" +%Y-%m-%d); D2=$(date -d "+2 day" +%Y-%m-%d)

chk "第五批新表已由应用启动自动建好（4 张）" \
    "$(q "select count(*) from information_schema.tables where table_schema=database() and table_name in ('laundry_order','linen_stock','consumable_item','room_inspection');")" "4"
chk "布草库存已有初始台账" "$(q "select if(count(*)>=5,1,0) from linen_stock where item_name in ('床单','被套','枕套','大浴巾','面巾','地巾');")" "1"
chk "消费目录已有初始项目" "$(q "select if(count(*)>=5,1,0) from consumable_item;")" "1"
chk "全部房间已发放扫码令牌" \
    "$(q "select count(*) from room where scan_token is not null and length(scan_token)=32;")" \
    "$(q "select count(*) from room;")"

# 让脚本可重复运行：只清掉「上一轮本脚本自己造的」数据（一律带「第五批测试」前缀），
# 不碰任何演示台账（床单/被套等布草、矿泉水等消费目录）
q "DELETE FROM laundry_order WHERE note LIKE '%第五批测试%' OR item_name LIKE '%第五批测试%';
   DELETE FROM linen_stock WHERE item_name LIKE '第五批测试%';
   DELETE FROM consumable_item WHERE item_name LIKE '第五批测试%';
   DELETE FROM housekeeping_task WHERE remark LIKE '%第五批测试%' OR remark LIKE '查房不合格自动整改%';
   DELETE FROM room_inspection WHERE remark LIKE '%第五批测试%';
   DELETE FROM minibar_item WHERE reservation_id IN (SELECT id FROM reservation WHERE remark LIKE '%第五批测试%');
   DELETE FROM bill_item WHERE bill_id IN
     (SELECT id FROM bill WHERE reservation_id IN (SELECT id FROM reservation WHERE remark LIKE '%第五批测试%'));
   DELETE FROM bill WHERE reservation_id IN (SELECT id FROM reservation WHERE remark LIKE '%第五批测试%');
   DELETE FROM reservation WHERE remark LIKE '%第五批测试%';"
echo "   已清理上一轮遗留的「第五批测试」数据"

echo
echo "=============================================="
echo " 1. 洗衣与布草（客衣 / 布草 + 库存三态）"
echo "=============================================="
# 建一张在住订单，作为客衣挂账与扫码下单的载体
RID_T=$(pick_free_room "$TODAY" "$D2")
echo "   在住测试房 ID=$RID_T"
T=$(csrf_of "$BASE/reservations/new" "$CJ" "$TMP/new.html")
post_form "$BASE/reservations/save" "$CJ" "$T" \
  --data-urlencode "customerId=$CID" --data-urlencode "roomId=$RID_T" \
  --data-urlencode "checkInDate=$TODAY" --data-urlencode "checkOutDate=$D2" \
  --data-urlencode "guestCount=1" --data-urlencode "remark=第五批测试-洗衣与扫码" > /dev/null
ORD_T=$(newest_order "$CJ")
T=$(csrf_of "$BASE/reservations/$ORD_T" "$CJ" "$TMP/ord.html")
post_form "$BASE/reservations/$ORD_T/checkin" "$CJ" "$T" > /dev/null
chk "测试订单已入住" "$(q "select status from reservation where id=$ORD_T;")" "CHECKED_IN"

# --- 客衣收件 ---
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld1.html")
chk "客衣收件（200）" \
    "$(post_form "$BASE/laundry" "$CJ" "$T" \
        --data-urlencode "category=GUEST" --data-urlencode "roomId=$RID_T" \
        --data-urlencode "itemName=衬衫" --data-urlencode "quantity=3" \
        --data-urlencode "unitPrice=15" --data-urlencode "note=第五批测试-客衣")" "200"
LG1=$(q "select max(id) from laundry_order;")
chk "客衣单状态=已收件" "$(q "select status from laundry_order where id=$LG1;")" "RECEIVED"
chk "客衣单金额=单价×件数" "$(q "select amount from laundry_order where id=$LG1;")" "45.00"
chk "客衣单挂在测试订单上" "$(q "select reservation_id from laundry_order where id=$LG1;")" "$ORD_T"

# 客衣挂到没有在住订单的房间 → 拒绝
RID_IDLE=$(idle_room)
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld2.html")
post_form "$BASE/laundry" "$CJ" "$T" \
  --data-urlencode "category=GUEST" --data-urlencode "roomId=$RID_IDLE" \
  --data-urlencode "itemName=西装" --data-urlencode "quantity=1" \
  --data-urlencode "unitPrice=50" --data-urlencode "note=第五批测试-越界客衣" > /dev/null
has "空置房客衣收件被拒" "$(cat "$TMP/post.html")" '没有在住订单'
chk "被拒后未产生洗衣单" \
    "$(q "select count(*) from laundry_order where room_id=$RID_IDLE and item_name='西装';")" "0"

# --- 布草单（内部周转，不计费、不挂订单） ---
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld3.html")
post_form "$BASE/laundry" "$CJ" "$T" \
  --data-urlencode "category=LINEN" --data-urlencode "roomId=" \
  --data-urlencode "itemName=第五批测试-浴巾" --data-urlencode "quantity=20" \
  --data-urlencode "unitPrice=0" --data-urlencode "note=第五批测试-布草" > /dev/null
LG2=$(q "select max(id) from laundry_order;")
chk "布草单金额恒为 0" "$(q "select amount from laundry_order where id=$LG2;")" "0.00"
chk "布草单不关联订单" "$(q "select if(reservation_id is null,1,0) from laundry_order where id=$LG2;")" "1"

# --- 状态流转 ---
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld4.html")
post_form "$BASE/laundry/$LG1/wash" "$CJ" "$T" > /dev/null
chk "客衣单转洗涤中" "$(q "select status from laundry_order where id=$LG1;")" "WASHING"

T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld5.html")
post_form "$BASE/laundry/$LG1/return" "$CJ" "$T" > /dev/null
chk "客衣单已送回" "$(q "select status from laundry_order where id=$LG1;")" "RETURNED"
chk "送回时间已记录" "$(q "select if(returned_at is null,0,1) from laundry_order where id=$LG1;")" "1"

T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld6.html")
post_form "$BASE/laundry/$LG1/return" "$CJ" "$T" > /dev/null
has "重复送回被拒" "$(cat "$TMP/post.html")" '不能重复送回'

# --- 布草库存 ---
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln1.html")
chk "新增布草品名（200）" \
    "$(post_form "$BASE/laundry/linen" "$CJ" "$T" \
        --data-urlencode "itemName=第五批测试-浴巾" --data-urlencode "category=TOWEL" \
        --data-urlencode "inStock=100" --data-urlencode "minStock=30")" "200"
ST=$(q "select id from linen_stock where item_name='第五批测试-浴巾';")
chk "布草入库初始数量" "$(q "select in_stock from linen_stock where id=$ST;")" "100"

chk "客房部无权新增布草品名（403）" \
    "$(T=$(csrf_of "$BASE/laundry" "$CJH" "$TMP/ln_hk.html"); \
       post_form "$BASE/laundry/linen" "$CJH" "$T" \
        --data-urlencode "itemName=第五批测试-越权" --data-urlencode "inStock=1" --data-urlencode "minStock=0")" "403"
chk "越权新增未落库" \
    "$(q "select count(*) from linen_stock where item_name='第五批测试-越权';")" "0"

T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln2.html")
post_form "$BASE/laundry/linen/$ST/issue" "$CJ" "$T" --data-urlencode "quantity=30" > /dev/null
chk "领用后在库=70"     "$(q "select in_stock from linen_stock where id=$ST;")" "70"
chk "领用后洗涤中=30"   "$(q "select in_wash from linen_stock where id=$ST;")" "30"

T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln3.html")
post_form "$BASE/laundry/linen/$ST/issue" "$CJ" "$T" --data-urlencode "quantity=99999" > /dev/null
has "超额领用被拒" "$(cat "$TMP/post.html")" '无法领用 99999 件'
chk "被拒领用未改数量" "$(q "select in_stock from linen_stock where id=$ST;")" "70"

T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln4.html")
post_form "$BASE/laundry/linen/$ST/back" "$CJ" "$T" --data-urlencode "quantity=10" > /dev/null
chk "回库后在库=80"   "$(q "select in_stock from linen_stock where id=$ST;")" "80"
chk "回库后洗涤中=20" "$(q "select in_wash from linen_stock where id=$ST;")" "20"

T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln5.html")
post_form "$BASE/laundry/linen/$ST/back" "$CJ" "$T" --data-urlencode "quantity=99999" > /dev/null
has "超额回库被拒" "$(cat "$TMP/post.html")" '无法回库 99999 件'

T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln6.html")
post_form "$BASE/laundry/linen/$ST/scrap" "$CJ" "$T" --data-urlencode "quantity=5" > /dev/null
chk "报废后在库=75"   "$(q "select in_stock from linen_stock where id=$ST;")" "75"
chk "报废后累计报废=5" "$(q "select scrapped from linen_stock where id=$ST;")" "5"

T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln7.html")
post_form "$BASE/laundry/linen/$ST/scrap" "$CJ" "$T" --data-urlencode "quantity=99999" > /dev/null
has "超额报废被拒" "$(cat "$TMP/post.html")" '无法报废 99999 件'
chk "被拒报废未改数量" "$(q "select in_stock from linen_stock where id=$ST;")" "75"

# --- 布草洗衣单送回自动回补库存 ---
chk "（前置）洗涤中 20 件" "$(q "select in_wash from linen_stock where id=$ST;")" "20"
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln8.html")
post_form "$BASE/laundry/$LG2/return" "$CJ" "$T" > /dev/null
chk "布草单已送回" "$(q "select status from laundry_order where id=$LG2;")" "RETURNED"
chk "送回后洗涤中清零" "$(q "select in_wash from linen_stock where id=$ST;")" "0"
chk "送回后在库=95（75+20）" "$(q "select in_stock from linen_stock where id=$ST;")" "95"

# --- 安全库存预警 ---
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ln9.html")
post_form "$BASE/laundry/linen/$ST/update" "$CJ" "$T" \
  --data-urlencode "minStock=200" --data-urlencode "note=第五批测试-预警" > /dev/null
chk "安全库存已调高到 200" "$(q "select min_stock from linen_stock where id=$ST;")" "200"
has "低于安全库存出现预警标记" "$(curl -s -b "$CJ" "$BASE/laundry")" '低于安全库存'

# --- 取消：已取消的客衣单不并入账单（下面退房时验证） ---
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld7.html")
post_form "$BASE/laundry" "$CJ" "$T" \
  --data-urlencode "category=GUEST" --data-urlencode "roomId=$RID_T" \
  --data-urlencode "itemName=第五批测试-外套" --data-urlencode "quantity=1" \
  --data-urlencode "unitPrice=20" --data-urlencode "note=第五批测试-待取消" > /dev/null
LG3=$(q "select max(id) from laundry_order;")
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld8.html")
post_form "$BASE/laundry/$LG3/cancel" "$CJ" "$T" > /dev/null
chk "客衣单可取消" "$(q "select status from laundry_order where id=$LG3;")" "CANCELLED"
T=$(csrf_of "$BASE/laundry" "$CJ" "$TMP/ld9.html")
post_form "$BASE/laundry/$LG3/cancel" "$CJ" "$T" > /dev/null
has "重复取消被拒" "$(cat "$TMP/post.html")" '不能取消'

echo
echo "=============================================="
echo " 2. 扫码消费（目录 + 二维码 + 免登录下单）"
echo "=============================================="
# 目录
T=$(csrf_of "$BASE/consumables" "$CJ" "$TMP/cs1.html")
chk "新增消费项目（200）" \
    "$(post_form "$BASE/consumables" "$CJ" "$T" \
        --data-urlencode "itemName=第五批测试-湿巾" --data-urlencode "category=DAILY" \
        --data-urlencode "unitPrice=5")" "200"
CI=$(q "select id from consumable_item where item_name='第五批测试-湿巾';")
# 注意 enabled 是 MySQL 的 bit(1)：batch 模式下客户端吐的是原始字节 0x01（终端里什么都看不见），
# 不是字符 '1'，必须 +0 转成整数再比对，否则永远取到空串。
chk "消费项目已上架" "$(q "select enabled+0 from consumable_item where id=$CI;")" "1"

chk "客房部无权维护消费目录（403）" \
    "$(T=$(csrf_of "$BASE/consumables" "$CJH" "$TMP/cs_hk.html"); \
       post_form "$BASE/consumables" "$CJH" "$T" \
        --data-urlencode "itemName=第五批测试-越权目录" --data-urlencode "category=MINIBAR" --data-urlencode "unitPrice=1")" "403"
chk "越权目录未落库" \
    "$(q "select count(*) from consumable_item where item_name='第五批测试-越权目录';")" "0"

# 下架后扫码页不可见
T=$(csrf_of "$BASE/consumables" "$CJ" "$TMP/cs2.html")
post_form "$BASE/consumables/$CI/update" "$CJ" "$T" \
  --data-urlencode "unitPrice=5" --data-urlencode "enabled=false" > /dev/null
TOK_T=$(scan_token_of "$RID_T")
anon_page "$BASE/scan/$TOK_T" "$TMP/anon1.cookies" "$TMP/scan_off.html" > /dev/null
lacks "下架项目不出现在扫码页" "$(cat "$TMP/scan_off.html")" '第五批测试-湿巾'

T=$(csrf_of "$BASE/consumables" "$CJ" "$TMP/cs3.html")
post_form "$BASE/consumables/$CI/update" "$CJ" "$T" \
  --data-urlencode "unitPrice=5" --data-urlencode "enabled=true" > /dev/null
anon_page "$BASE/scan/$TOK_T" "$TMP/anon2.cookies" "$TMP/scan_on.html" > /dev/null
has "上架项目出现在扫码页" "$(cat "$TMP/scan_on.html")" '第五批测试-湿巾'

# 二维码图片
chk "二维码 PNG 生成（200）" "$(code_of "$CJ" "$BASE/consumables/rooms/$RID_T/qr.png")" "200"
QR_TYPE=$(curl -s -o "$TMP/qr.png" -w '%{content_type}' -b "$CJ" "$BASE/consumables/rooms/$RID_T/qr.png")
has "二维码返回 image/png" "$QR_TYPE" 'image/png'
QR_SIZE=$(wc -c < "$TMP/qr.png" | tr -d ' ')
if [ "$QR_SIZE" -gt 1000 ]; then ok "二维码体积合理（${QR_SIZE}B）"; else no "二维码体积异常（${QR_SIZE}B）"; fi

# 重置令牌：旧码立即失效
OLD_TOK="$TOK_T"
T=$(csrf_of "$BASE/consumables" "$CJ" "$TMP/cs4.html")
post_form "$BASE/consumables/rooms/$RID_T/reset-token" "$CJ" "$T" > /dev/null
NEW_TOK=$(scan_token_of "$RID_T")
if [ "$OLD_TOK" != "$NEW_TOK" ]; then ok "重置后令牌已变化"; else no "重置后令牌未变化"; fi
anon_page "$BASE/scan/$OLD_TOK" "$TMP/anon3.cookies" "$TMP/scan_old.html" > /dev/null
has "旧二维码立即失效" "$(cat "$TMP/scan_old.html")" '二维码无效或已失效'

# 免登录下单
chk "免登录可打开扫码页（200）" \
    "$(anon_page "$BASE/scan/$NEW_TOK" "$TMP/anon.cookies" "$TMP/scan_ok.html")" "200"
has "扫码页显示房号" "$(cat "$TMP/scan_ok.html")" "$(q "select room_no from room where id=$RID_T;")"
has "扫码页显示住客姓名" "$(cat "$TMP/scan_ok.html")" "$(q "select c.name from reservation r join customer c on c.id=r.customer_id where r.id=$ORD_T;")"
CT=$(csrf_in "$TMP/scan_ok.html")
chk "扫码页带出会话级 CSRF" "$([ -n "$CT" ] && echo yes || echo no)" "yes"
ITEM_MINERAL=$(q "select id from consumable_item where item_name='矿泉水';")
chk "住客扫码下单（200）" \
    "$(curl -sL -o "$TMP/scan_post.html" -w '%{http_code}' -b "$TMP/anon.cookies" -c "$TMP/anon.cookies" \
        --data-urlencode "_csrf=$CT" --data-urlencode "itemId=$ITEM_MINERAL" \
        --data-urlencode "quantity=2" "$BASE/scan/$NEW_TOK")" "200"
has "下单成功提示" "$(cat "$TMP/scan_post.html")" '下单成功'
chk "扫码挂账落库（待入账）" \
    "$(q "select concat(quantity,'/',status,'/',source) from minibar_item where reservation_id=$ORD_T and item_name='矿泉水' order by id desc limit 1;")" \
    "2/PENDING/SCAN"
chk "扫码挂账金额=目录价×数量" \
    "$(q "select amount from minibar_item where reservation_id=$ORD_T and item_name='矿泉水' order by id desc limit 1;")" "16.00"
has "迷你吧页显示来源「住客扫码」" "$(curl -s -b "$CJH" "$BASE/housekeeping/minibar")" '住客扫码'

# 安全边界：没有在住订单的房间，即使拿到合法令牌也拒绝
RID_FREE=$(idle_room)
TOK_FREE=$(scan_token_of "$RID_FREE")
chk "空置房扫码页提示未入住" \
    "$(anon_page "$BASE/scan/$TOK_FREE" "$TMP/anon4.cookies" "$TMP/scan_free.html" > /dev/null; \
       grep -c '本房间当前没有在住客人' "$TMP/scan_free.html")" "1"
# 用「有在住客人」页面拿到的合法 CSRF，打到空置房上
rm -f "$TMP/anon5.cookies"
CT=$(csrf_of "$BASE/scan/$NEW_TOK" "$TMP/anon5.cookies" "$TMP/scan_csrf.html")
chk "伪造到空置房的扫码下单（200 但被业务拒绝）" \
    "$(curl -sL -o "$TMP/scan_bad.html" -w '%{http_code}' -b "$TMP/anon5.cookies" -c "$TMP/anon5.cookies" \
        --data-urlencode "_csrf=$CT" --data-urlencode "itemId=$ITEM_MINERAL" \
        --data-urlencode "quantity=1" "$BASE/scan/$TOK_FREE")" "200"
has "空置房扫码下单被拒" "$(cat "$TMP/scan_bad.html")" '没有在住订单'
chk "被拒后空置房无挂账" "$(q "select count(*) from minibar_item where room_id=$RID_FREE;")" "0"

echo
echo "=============================================="
echo " 3. 退房结账：客衣费与扫码挂账自动并入账单"
echo "=============================================="
T=$(csrf_of "$BASE/reservations/$ORD_T" "$CJ" "$TMP/co.html")
URL=$(curl -s -o /dev/null -w '%{redirect_url}' -b "$CJ" -c "$CJ" -X POST \
    --data-urlencode "_csrf=$(csrf_in "$TMP/co.html")" \
    --data-urlencode "actualCheckOutDate=$D1" \
    "$BASE/reservations/$ORD_T/checkout")
BILL=$(printf '%s' "$URL" | grep -o '[0-9]*$')
echo "   退房生成账单 id=$BILL"
chk "测试订单已退房" "$(q "select status from reservation where id=$ORD_T;")" "CHECKED_OUT"
# 客衣：衬衫 3×15 = 45（已送回，仍应收） + 扫码：矿泉水 2×8 = 16 → 其他消费 61
chk "账单其他消费 = 客衣 45 + 扫码 16" "$(q "select other_amount from bill where id=$BILL;")" "61.00"
chk "账单应收 = 房费 + 其他消费" \
    "$(awk -v t="$(q "select total_amount from bill where id=$BILL;")" \
           -v r="$(q "select room_amount from bill where id=$BILL;")" \
       'BEGIN{printf "%.2f", t - r - 61}')" "0.00"
has "账单出现洗衣明细" "$(curl -s -b "$CJ" "$BASE/bills/$BILL")" '洗衣 · 衬衫'
has "账单出现扫码迷你吧明细" "$(curl -s -b "$CJ" "$BASE/bills/$BILL")" '迷你吧 · 矿泉水'
chk "已并入的客衣单回写账单号" \
    "$(q "select count(*) from laundry_order where id=$LG1 and bill_id=$BILL;")" "1"
chk "已取消的客衣单不并入账单" \
    "$(q "select if(bill_id is null,1,0) from laundry_order where id=$LG3;")" "1"
chk "布草单不计费也不并入账单" \
    "$(q "select if(bill_id is null and amount=0,1,0) from laundry_order where id=$LG2;")" "1"
chk "扫码挂账已标记为已入账单" \
    "$(q "select count(*) from minibar_item where reservation_id=$ORD_T and status='POSTED' and bill_id=$BILL;")" "1"
chk "客衣单已送回仍计费（口径：仅取消不收）" \
    "$(q "select status from laundry_order where id=$LG1;")" "RETURNED"

echo
echo "=============================================="
echo " 4. 查房评分（含不合格自动整改）"
echo "=============================================="
chk "（前置）测试房已转待清洁" \
    "$(T=$(csrf_of "$BASE/housekeeping/tasks" "$CJ" "$TMP/hk_pre.html"); \
       post_form "$BASE/rooms/$RID_IDLE/status" "$CJ" "$T" --data-urlencode "status=AVAILABLE" > /dev/null; \
       q "select status from room where id=$RID_IDLE;")" "AVAILABLE"

T=$(csrf_of "$BASE/inspections" "$CJ" "$TMP/in1.html")
chk "登记合格查房（200）" \
    "$(post_form "$BASE/inspections" "$CJ" "$T" \
        --data-urlencode "roomId=$RID_IDLE" \
        --data-urlencode "scoreHygiene=25" --data-urlencode "scoreFacility=25" \
        --data-urlencode "scoreLinen=25" --data-urlencode "scoreAmenity=25" \
        --data-urlencode "remark=第五批测试-合格")" "200"
IN1=$(q "select max(id) from room_inspection;")
chk "总分=100"       "$(q "select total_score from room_inspection where id=$IN1;")" "100"
chk "结论=合格"      "$(q "select result from room_inspection where id=$IN1;")" "PASS"
chk "合格不派整改单" "$(q "select if(rectified_task_id is null,1,0) from room_inspection where id=$IN1;")" "1"
chk "合格不改房态"   "$(q "select status from room where id=$RID_IDLE;")" "AVAILABLE"
has "页面提示优秀" "$(cat "$TMP/post.html")" '查房完成：总分 100（优秀）'

T=$(csrf_of "$BASE/inspections" "$CJ" "$TMP/in2.html")
chk "登记不合格查房（200）" \
    "$(post_form "$BASE/inspections" "$CJ" "$T" \
        --data-urlencode "roomId=$RID_IDLE" \
        --data-urlencode "scoreHygiene=10" --data-urlencode "scoreFacility=10" \
        --data-urlencode "scoreLinen=10" --data-urlencode "scoreAmenity=10" \
        --data-urlencode "remark=第五批测试-不合格" \
        --data-urlencode "assigneeId=$HK_ID")" "200"
IN2=$(q "select max(id) from room_inspection;")
chk "总分=40"       "$(q "select total_score from room_inspection where id=$IN2;")" "40"
chk "结论=不合格"   "$(q "select result from room_inspection where id=$IN2;")" "FAIL"
chk "不合格后房态转待清洁" "$(q "select status from room where id=$RID_IDLE;")" "CLEANING"
RIDGE=$(q "select rectified_task_id from room_inspection where id=$IN2;")
if [ -n "$RIDGE" ]; then ok "已自动生成整改派工单 #$RIDGE"; else no "未生成整改派工单"; fi
chk "整改单类型=深度清洁" "$(q "select type from housekeeping_task where id=$RIDGE;")" "DEEP"
chk "整改单状态=待完成"   "$(q "select status from housekeeping_task where id=$RIDGE;")" "PENDING"
chk "整改单指派给指定服务员" "$(q "select assignee_id from housekeeping_task where id=$RIDGE;")" "$HK_ID"
has "整改单备注带总分" "$(q "select remark from housekeeping_task where id=$RIDGE;")" '总分 40'

T=$(csrf_of "$BASE/inspections" "$CJ" "$TMP/in3.html")
post_form "$BASE/inspections" "$CJ" "$T" \
  --data-urlencode "roomId=$RID_IDLE" \
  --data-urlencode "scoreHygiene=30" --data-urlencode "scoreFacility=25" \
  --data-urlencode "scoreLinen=25" --data-urlencode "scoreAmenity=25" > /dev/null
has "越界分值被拒" "$(cat "$TMP/post.html")" '卫生得分需在 0–25 之间'
chk "越界不新增记录" "$(q "select count(*) from room_inspection where room_id=$RID_IDLE and score_hygiene=30;")" "0"

has "按结论筛选：不合格可见" "$(curl -s -b "$CJ" "$BASE/inspections?result=FAIL")" "$(q "select room_no from room where id=$RID_IDLE;")"
has "查房页显示平均分" "$(curl -s -b "$CJ" "$BASE/inspections")" '平均分'

echo
echo "=============================================="
echo " 5. 房态大屏（口径一致 + 权限）"
echo "=============================================="
chk "管理员可访问大屏"       "$(code_of "$CJ" "$BASE/board")" "200"
chk "客房部可访问大屏"       "$(code_of "$CJH" "$BASE/board")" "200"
chk "未登录访问大屏 → 302"   "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/board")" "302"
chk "未登录调用大屏接口 → 302" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/board")" "302"

curl -s -b "$CJ" "$BASE/api/board" -o "$TMP/api.json"
TOTAL=$(jnum total "$(cat "$TMP/api.json")")
SELLABLE=$(jnum sellable "$(cat "$TMP/api.json")")
SOLD=$(jnum sold "$(cat "$TMP/api.json")")
FREE=$(jnum FREE "$(cat "$TMP/api.json")")
CLEANING=$(jnum CLEANING "$(cat "$TMP/api.json")")
DEPARTING=$(jnum DEPARTING "$(cat "$TMP/api.json")")
INH=$(jnum IN_HOUSE "$(cat "$TMP/api.json")")
MAINT=$(jnum MAINTENANCE "$(cat "$TMP/api.json")")
ARR=$(jnum ARRIVING "$(cat "$TMP/api.json")")
RES=$(jnum RESERVED "$(cat "$TMP/api.json")")
OCC=$(jnum occupancy "$(cat "$TMP/api.json")")

chk "大屏房总量 = 数据库房量" "$TOTAL" "$(q "select count(*) from room;")"
chk "可售 = 空闲 + 待清洁 + 今日离店" "$SELLABLE" "$((FREE + CLEANING + DEPARTING))"
chk "已售 = 在住 + 预抵 + 已预订 + 维修中" "$SOLD" "$((INH + ARR + RES + MAINT))"
chk "可售 + 已售 = 总量" "$((SELLABLE + SOLD))" "$TOTAL"
chk "维修中与数据库一致" "$MAINT" "$(q "select count(*) from room where status='MAINTENANCE';")"
chk "在住房数与数据库一致（今晚有在住订单）" "$INH" \
    "$(q "select count(distinct room_id) from reservation where status='CHECKED_IN' and check_in_date <= curdate() and check_out_date > curdate();")"
EXPECT_OCC=$(awk -v s="$SELLABLE" -v d="$SOLD" 'BEGIN{ if (s==0) printf "0.0"; else printf "%.1f", d*100/s }')
chk "出租率 = 已售/可售" "$OCC" "$EXPECT_OCC"

FLOORS=$(grep -o '"floor":[0-9]*' "$TMP/api.json" | wc -l | tr -d ' ')
chk "楼层分组数 = 数据库楼层数" "$FLOORS" "$(q "select count(distinct floor) from room;")"
ROOMS_IN_API=$(grep -o '"roomNo":"[^"]*"' "$TMP/api.json" | wc -l | tr -d ' ')
chk "大屏房间格子数 = 房量" "$ROOMS_IN_API" "$TOTAL"
has "占用格子带客人姓名" "$(cat "$TMP/api.json")" '"guest":"'
lacks "空闲格子不带客人" "$(cat "$TMP/api.json")" '"state":"FREE","label":"空闲","color":"success","guest":"'

TOMORROW=$(date -d "+1 day" +%Y-%m-%d)
curl -s -b "$CJ" "$BASE/api/board?date=$TOMORROW" -o "$TMP/api2.json"
chk "指定日期快照房量不变" "$(jnum total "$(cat "$TMP/api2.json")")" "$TOTAL"
chk "指定日期快照日期正确" "$(grep -o '"date":"[^"]*"' "$TMP/api2.json" | cut -d'"' -f4)" "$TOMORROW"

echo
echo "=============================================="
echo " 6. 审计留痕"
echo "=============================================="
for act in 洗衣收件 洗衣洗涤 洗衣送回 洗衣取消 布草入库 布草领用 布草回库 布草报废 \
           消费目录新增 消费目录修改 重置房间二维码 查房评分 派工; do
  n=$(q "select count(*) from operation_log where action='$act';")
  if [ "$n" -gt 0 ]; then ok "审计日志含动作「$act」($n 条)"; else no "审计日志缺动作「$act」"; fi
done
has "日志页可见洗衣记录" "$(curl -s -b "$CJ" "$BASE/logs")" '洗衣'
chk "客房部仍打不开日志页" "$(code_of "$CJH" "$BASE/logs")" "403"

echo
echo "=============================================="
printf " 结果：PASS=%d FAIL=%d\n" "$PASS" "$FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ]
