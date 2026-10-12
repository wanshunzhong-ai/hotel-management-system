package com.hotel.hms.config;

import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.ChecklistCategory;
import com.hotel.hms.entity.ChecklistItem;
import com.hotel.hms.entity.ConsumableCategory;
import com.hotel.hms.entity.ConsumableItem;
import com.hotel.hms.entity.Customer;
import com.hotel.hms.entity.Gender;
import com.hotel.hms.entity.IdType;
import com.hotel.hms.entity.LinenCategory;
import com.hotel.hms.entity.LinenStock;
import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.entity.RatePlan;
import com.hotel.hms.entity.RatePlanType;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.entity.RoomType;
import com.hotel.hms.entity.SupplyCategory;
import com.hotel.hms.entity.TaskType;
import com.hotel.hms.entity.User;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.ChecklistItemRepository;
import com.hotel.hms.repository.ConsumableItemRepository;
import com.hotel.hms.repository.CustomerRepository;
import com.hotel.hms.repository.LinenStockRepository;
import com.hotel.hms.repository.RatePlanRepository;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.RoomRepository;
import com.hotel.hms.repository.RoomTypeRepository;
import com.hotel.hms.repository.SupplyItemRepository;
import com.hotel.hms.repository.UserRepository;
import com.hotel.hms.service.ConsumableService;
import com.hotel.hms.service.SerialNumberService;
import com.hotel.hms.service.SupplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 初始化账号与演示数据。已有数据时不会重复写入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final CustomerRepository customerRepository;
    private final ReservationRepository reservationRepository;
    private final BillRepository billRepository;
    private final LinenStockRepository linenStockRepository;
    private final ConsumableItemRepository consumableItemRepository;
    private final RatePlanRepository ratePlanRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final SupplyItemRepository supplyItemRepository;
    private final ConsumableService consumableService;
    private final SupplyService supplyService;
    private final SerialNumberService serialNumberService;
    private final PasswordEncoder passwordEncoder;

    @Value("${hotel.init-demo-data:true}")
    private boolean initDemoData;

    @Override
    @Transactional
    public void run(String... args) {
        initUsers();
        migrateLegacyRoomStatus();
        seedLinenStock();
        seedConsumables();
        seedRatePlans();
        seedChecklistItems();
        seedSupplies();
        int issued = consumableService.ensureRoomTokens();
        if (issued > 0) {
            log.info("已为 {} 间未发放二维码的房间补发扫码令牌。", issued);
        }
        if (!initDemoData) {
            return;
        }
        if (roomTypeRepository.count() == 0) {
            initHotelData();
            log.info("演示数据初始化完成。");
        }
    }

    /**
     * 客房消费目录（幂等：已有任何一条就跳过）。
     */
    private void seedConsumables() {
        if (consumableItemRepository.count() > 0) {
            return;
        }
        seedConsumable("矿泉水", ConsumableCategory.MINIBAR, "8.00", "500ml 装", true);
        seedConsumable("可乐", ConsumableCategory.MINIBAR, "10.00", "330ml 罐装", true);
        seedConsumable("青岛啤酒", ConsumableCategory.MINIBAR, "18.00", "500ml 罐装", true);
        seedConsumable("红牛", ConsumableCategory.MINIBAR, "15.00", "250ml 罐装", true);
        seedConsumable("坚果拼盘", ConsumableCategory.SNACK, "28.00", "混合坚果小食", true);
        seedConsumable("时令水果盘", ConsumableCategory.SNACK, "38.00", "当日新鲜果切", true);
        seedConsumable("一次性洗漱套装", ConsumableCategory.DAILY, "12.00", "牙具 + 梳子 + 剃须刀", true);
        seedConsumable("针线包", ConsumableCategory.DAILY, "6.00", "含黑白线、纽扣", true);
        seedConsumable("客房简餐", ConsumableCategory.OTHER, "45.00", "需提前 30 分钟下单", false);
        log.info("客房消费目录已建立（9 个项目，其中 1 个默认下架）。");
    }

    /**
     * 房价方案基础数据（幂等：已有任何一条就跳过）。
     *
     * <p>与演示客户一起构成「协议价 / 会员价」的可用样例：演示客户的会员等级覆盖
     * 银卡 / 金卡 / 钻石，下单时选定客户即可自动带出对应等级的会员价。
     */
    private void seedRatePlans() {
        if (ratePlanRepository.count() > 0) {
            return;
        }
        seedRatePlan("CORP-YUNQI", "云启科技协议价", RatePlanType.CORPORATE,
                new BigDecimal("0.80"), null, null, "与云启科技签订年度协议，全房型 8 折");
        seedRatePlan("MEMBER-SILVER", "银卡会员价", RatePlanType.MEMBER,
                new BigDecimal("0.95"), null, "银卡", "银卡会员全房型 9.5 折");
        seedRatePlan("MEMBER-GOLD", "金卡会员价", RatePlanType.MEMBER,
                new BigDecimal("0.90"), null, "金卡", "金卡会员全房型 9 折");
        seedRatePlan("MEMBER-DIAMOND", "钻石会员价", RatePlanType.MEMBER,
                new BigDecimal("0.85"), null, "钻石", "钻石会员全房型 8.5 折");
        log.info("房价方案基础数据已建立（1 个协议价 + 3 个会员价）。");
    }

    private void seedRatePlan(String code, String name, RatePlanType type,
                              BigDecimal discountRate, BigDecimal fixedPrice,
                              String memberLevel, String note) {
        RatePlan plan = new RatePlan();
        plan.setCode(code);
        plan.setName(name);
        plan.setType(type);
        plan.setDiscountRate(discountRate);
        plan.setFixedPrice(fixedPrice);
        plan.setMemberLevel(memberLevel);
        plan.setEnabled(true);
        plan.setNote(note);
        ratePlanRepository.save(plan);
    }

    private void seedConsumable(String name, ConsumableCategory category, String price,
                                String note, boolean enabled) {        ConsumableItem item = new ConsumableItem();
        item.setItemName(name);
        item.setCategory(category);
        item.setUnitPrice(new BigDecimal(price));
        item.setNote(note);
        item.setEnabled(enabled);
        consumableItemRepository.save(item);
    }

    /**
     * 布草库存基础品名（幂等：已有任何一条记录就跳过）。
     *
     * <p>与客房演示数据分开处理——老库已经有房型/房间，不会走 {@link #initHotelData}，
     * 但新加的布草表仍然需要一份可用的初始台账。
     */
    private void seedLinenStock() {
        if (linenStockRepository.count() > 0) {
            return;
        }
        seedLinen("床单", LinenCategory.BEDDING, 240, 60);
        seedLinen("被套", LinenCategory.BEDDING, 180, 50);
        seedLinen("枕套", LinenCategory.BEDDING, 320, 80);
        seedLinen("大浴巾", LinenCategory.TOWEL, 160, 60);
        seedLinen("面巾", LinenCategory.TOWEL, 200, 80);
        seedLinen("地巾", LinenCategory.TOWEL, 90, 40);
        log.info("布草库存初始台账已建立（6 个品名）。");
    }

    private void seedLinen(String name, LinenCategory category, int inStock, int minStock) {
        LinenStock stock = new LinenStock();
        stock.setItemName(name);
        stock.setCategory(category);
        stock.setInStock(inStock);
        stock.setInWash(0);
        stock.setScrapped(0);
        stock.setMinStock(minStock);
        linenStockRepository.save(stock);
    }

    /**
     * 清洁标准检查单模板（幂等：已有任何一条就跳过）。
     *
     * <p>模板分两类：<b>通用项</b>（taskType 为空，所有房都适用）与
     * <b>专用项</b>（只对走客房 / 深度清洁生效）。派工单创建时按任务类型取适用条目，
     * 生成该单的执行清单。
     */
    private void seedChecklistItems() {
        if (checklistItemRepository.count() > 0) {
            return;
        }
        int order = 0;
        // ---- 通用：卫生 ----
        seedChecklist("更换床品与枕套", ChecklistCategory.HYGIENE, null, order++, "床单、被套、枕套全套更换");
        seedChecklist("清洁卫浴（马桶 / 面盆 / 淋浴）", ChecklistCategory.HYGIENE, null, order++, "含除垢与消毒");
        seedChecklist("地面吸尘并拖净", ChecklistCategory.HYGIENE, null, order++, "含床底与角落");
        seedChecklist("擦拭桌面、床头与家具", ChecklistCategory.HYGIENE, null, order++, "不留水渍与浮尘");
        // ---- 通用：设施 ----
        seedChecklist("检查灯具与开关", ChecklistCategory.FACILITY, null, order++, "含床头灯与阅读灯");
        seedChecklist("检查空调与遥控器", ChecklistCategory.FACILITY, null, order++, "制冷正常、遥控有电");
        seedChecklist("检查门锁、猫眼与防盗链", ChecklistCategory.FACILITY, null, order++, "反锁功能正常");
        // ---- 通用：布草 ----
        seedChecklist("补充浴巾、面巾、地巾", ChecklistCategory.LINEN, null, order++, "按房型标准配置数量");
        seedChecklist("检查布草无破损与污渍", ChecklistCategory.LINEN, null, order++, "有瑕必换");
        // ---- 通用：客用品 ----
        seedChecklist("补充六小件（牙具 / 梳子 / 剃须刀 / 浴帽 / 拖鞋 / 护理包）",
                ChecklistCategory.AMENITY, null, order++, "按标准配齐");
        seedChecklist("补充矿泉水与茶饮", ChecklistCategory.AMENITY, null, order++, "缺一补一");
        seedChecklist("清空垃圾桶并更换垃圾袋", ChecklistCategory.AMENITY, null, order++, "含卫生间与书桌桶");
        // ---- 走客房专用 ----
        seedChecklist("检查客人遗留物品", ChecklistCategory.OTHER, TaskType.CHECKOUT, order++,
                "发现即登记失物招领");
        // ---- 深度清洁专用 ----
        seedChecklist("清洗窗帘与床垫除螨", ChecklistCategory.HYGIENE, TaskType.DEEP, order++, "深度保洁");
        seedChecklist("清洗空调滤网", ChecklistCategory.FACILITY, TaskType.DEEP, order++, "拆洗后复位");
        log.info("清洁标准检查单模板已建立（{} 项，含走客房与深度清洁专用项）。", checklistItemRepository.count());
    }

    private void seedChecklist(String itemName, ChecklistCategory category, TaskType taskType,
                               int sortOrder, String note) {
        ChecklistItem item = new ChecklistItem();
        item.setItemName(itemName);
        item.setCategory(category);
        item.setTaskType(taskType);
        item.setSortOrder(sortOrder);
        item.setEnabled(true);
        item.setNote(note);
        checklistItemRepository.save(item);
    }

    /**
     * 客房耗材台账基础数据（幂等：已有任何一条就跳过）。
     *
     * <p>走 {@link SupplyService#createItem} 而不是直接写仓库，
     * 是为了让「建档」同时留下一条入库流水——否则台账第一笔库存从哪来就成了无头案。
     */
    private void seedSupplies() {
        if (supplyItemRepository.count() > 0) {
            return;
        }
        seedSupply("牙具套装", SupplyCategory.AMENITY, "套", 100, 30, "2.50");
        seedSupply("梳子", SupplyCategory.AMENITY, "把", 120, 40, "1.20");
        seedSupply("剃须刀", SupplyCategory.AMENITY, "套", 80, 30, "2.00");
        seedSupply("浴帽", SupplyCategory.AMENITY, "个", 60, 20, "0.80");
        seedSupply("一次性拖鞋", SupplyCategory.AMENITY, "双", 200, 60, "3.50");
        seedSupply("护理包（棉签 / 指甲锉）", SupplyCategory.AMENITY, "套", 50, 20, "1.50");
        seedSupply("洁厕剂", SupplyCategory.CLEANING, "瓶", 24, 10, "12.00");
        seedSupply("多功能清洁剂", SupplyCategory.CLEANING, "瓶", 30, 12, "15.00");
        seedSupply("84 消毒液", SupplyCategory.CLEANING, "瓶", 20, 8, "18.00");
        seedSupply("卷纸", SupplyCategory.PAPER, "卷", 300, 80, "1.80");
        seedSupply("抽纸", SupplyCategory.PAPER, "盒", 150, 50, "4.50");
        seedSupply("洗衣粉", SupplyCategory.LAUNDRY, "袋", 20, 8, "25.00");
        log.info("客房耗材台账已建立（{} 个品名）。", supplyItemRepository.count());
    }

    private void seedSupply(String name, SupplyCategory category, String unit,
                            int stock, int minStock, String unitCost) {
        supplyService.createItem(name, category, unit, stock, minStock,
                new BigDecimal(unitCost), "初始化账套", "系统");
    }

    /**
     * 房态语义收敛的一次性数据迁移（第三批 §1.1）。
     *
     * <p>旧实现把「未来有预订」也写进 {@code room.status}，导致房间当前明显空着
     * 却显示「已预订」。收敛后该值不再由业务写入，这里把历史遗留值清回「空闲」，
     * 可售性改由预约表按日期区间实时计算。
     */
    private void migrateLegacyRoomStatus() {
        List<Room> legacy = roomRepository.findByStatusOrderByRoomNoAsc(RoomStatus.RESERVED);
        if (legacy.isEmpty()) {
            return;
        }
        legacy.forEach(room -> room.setStatus(RoomStatus.AVAILABLE));
        roomRepository.saveAll(legacy);
        log.info("房态语义收敛：{} 间历史「已预订」房已重置为「空闲」（可售性改由预约表按日期计算）", legacy.size());
    }

    // ------------------------------------------------------------------
    // 账号
    // ------------------------------------------------------------------
    private void initUsers() {
        createIfAbsent("admin", "admin123", "系统管理员", Role.ADMIN, "13800000001");
        createIfAbsent("reception", "123456", "前台-小林", Role.RECEPTION, "13800000002");
        createIfAbsent("housekeeping", "123456", "客房-王姐", Role.HOUSEKEEPING, "13800000003");
        createIfAbsent("supervisor", "123456", "客房主管-李领班", Role.HOUSEKEEPING_SUPERVISOR, "13800000004");
    }

    private void createIfAbsent(String username, String rawPassword, String realName, Role role, String phone) {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRealName(realName);
        user.setRole(role);
        user.setPhone(phone);
        user.setEnabled(true);
        userRepository.save(user);
        log.info("已创建默认账号 {} / {}", username, rawPassword);
    }

    // ------------------------------------------------------------------
    // 演示业务数据
    // ------------------------------------------------------------------
    private void initHotelData() {
        List<RoomType> types = new ArrayList<>();
        types.add(saveType("标准大床房", "1.8m 大床", 26, 2, "268.00", "面积 26㎡，含双早，免费 Wi-Fi"));
        types.add(saveType("标准双床房", "1.2m 双床", 28, 2, "298.00", "面积 28㎡，适合双人入住，含双早"));
        types.add(saveType("商务大床房", "1.8m 大床", 35, 2, "428.00", "面积 35㎡，独立办公区，含双早"));
        types.add(saveType("家庭套房", "1.8m + 1.2m", 48, 3, "568.00", "面积 48㎡，双卧设计，适合亲子出行"));
        types.add(saveType("豪华景观套房", "2.0m 大床", 62, 2, "888.00", "面积 62㎡，客厅独立，含行政酒廊权益"));

        // 2~6 层，每层 8 间
        List<Room> rooms = new ArrayList<>();
        int roomNo = 1;
        for (int floor = 2; floor <= 6; floor++) {
            for (int idx = 1; idx <= 8; idx++) {
                RoomType type = types.get((roomNo + floor) % types.size());
                Room room = new Room();
                room.setRoomNo(String.valueOf(floor * 100 + idx));
                room.setFloor(floor);
                room.setRoomType(type);
                room.setStatus(RoomStatus.AVAILABLE);
                room.setRemark(null);
                rooms.add(room);
                roomNo++;
            }
        }
        roomRepository.saveAll(rooms);

        List<Customer> customers = new ArrayList<>();
        customers.add(saveCustomer("张伟", Gender.MALE, "110101199003071234", "13811110001", "zhangwei@example.com", "金卡", "北京市朝阳区建国路 88 号"));
        customers.add(saveCustomer("李娜", Gender.FEMALE, "310101199205124567", "13822220002", "lina@example.com", "钻石", "上海市浦东新区世纪大道 1 号"));
        customers.add(saveCustomer("王强", Gender.MALE, "440101198811203344", "13833330003", "wangqiang@example.com", "银卡", "广州市天河区体育西路 12 号"));
        customers.add(saveCustomer("陈静", Gender.FEMALE, "330101199409155566", "13844440004", "chenjing@example.com", "普通", "杭州市西湖区文三路 5 号"));
        customers.add(saveCustomer("刘洋", Gender.MALE, "510101199101018877", "13855550005", "liuyang@example.com", "银卡", "成都市锦江区红星路 3 段"));
        customers.add(saveCustomer("赵敏", Gender.FEMALE, "320101199307229900", "13866660006", "zhaomin@example.com", "金卡", "南京市鼓楼区中山北路 20 号"));
        customers.add(saveCustomer("孙浩", Gender.MALE, "120101198705141122", "13877770007", "sunhao@example.com", "普通", "天津市和平区南京路 66 号"));
        customers.add(saveCustomer("周雪", Gender.FEMALE, "420101199601303355", "13888880008", "zhouxue@example.com", "钻石", "武汉市江汉区解放大道 100 号"));
        customerRepository.saveAll(customers);

        LocalDate today = LocalDate.now();

        // 1) 在住订单
        Reservation inHouse1 = createReservation(rooms.get(0), customers.get(0), today.minusDays(2), today.plusDays(1), 2, "前台-小林");
        inHouse1.setStatus(ReservationStatus.CHECKED_IN);
        inHouse1.setCheckInTime(today.minusDays(2).atTime(14, 20));
        reservationRepository.save(inHouse1);
        markRoom(rooms.get(0), RoomStatus.OCCUPIED);

        Reservation inHouse2 = createReservation(rooms.get(9), customers.get(1), today.minusDays(1), today.plusDays(2), 2, "前台-小林");
        inHouse2.setStatus(ReservationStatus.CHECKED_IN);
        inHouse2.setCheckInTime(today.minusDays(1).atTime(19, 5));
        reservationRepository.save(inHouse2);
        markRoom(rooms.get(9), RoomStatus.OCCUPIED);

        Reservation inHouse3 = createReservation(rooms.get(17), customers.get(2), today, today.plusDays(3), 1, "前台-小林");
        inHouse3.setStatus(ReservationStatus.CHECKED_IN);
        inHouse3.setCheckInTime(today.atTime(9, 40));
        reservationRepository.save(inHouse3);
        markRoom(rooms.get(17), RoomStatus.OCCUPIED);

        // 2) 今日预抵（房间物理上是空的，只是今晚已被订走——房态图会按日期算出来）
        Reservation arrival1 = createReservation(rooms.get(25), customers.get(3), today, today.plusDays(1), 2, "前台-小林");
        reservationRepository.save(arrival1);

        // 3) 未来预订（不再把房间置为「已预订」占位，避免房态图把今天也显示成不可售）
        reservationRepository.save(createReservation(rooms.get(30), customers.get(4), today.plusDays(3), today.plusDays(5), 3, "前台-小林"));
        reservationRepository.save(createReservation(rooms.get(33), customers.get(5), today.plusDays(7), today.plusDays(9), 2, "系统管理员"));

        // 4) 历史已退房 + 已结账账单（供给近 7 日营收曲线）
        checkoutHistory(rooms.get(20), customers.get(6), 6, 4, BigDecimal.ZERO);
        checkoutHistory(rooms.get(21), customers.get(7), 5, 3, new BigDecimal("88.00"));
        checkoutHistory(rooms.get(22), customers.get(0), 3, 2, BigDecimal.ZERO);
        checkoutHistory(rooms.get(23), customers.get(1), 1, 1, new BigDecimal("168.00"));

        // 5) 维修与待清洁
        markRoom(rooms.get(37), RoomStatus.MAINTENANCE);
        markRoom(rooms.get(38), RoomStatus.CLEANING);
    }

    private RoomType saveType(String name, String bedType, int area, int capacity, String price, String desc) {
        RoomType type = new RoomType();
        type.setName(name);
        type.setBedType(bedType);
        type.setArea(area);
        type.setCapacity(capacity);
        type.setPrice(new BigDecimal(price));
        type.setDescription(desc);
        type.setEnabled(true);
        return roomTypeRepository.save(type);
    }

    private Customer saveCustomer(String name, Gender gender, String idNo, String phone, String email,
                                  String memberLevel, String address) {
        Customer customer = new Customer();
        customer.setName(name);
        customer.setGender(gender);
        customer.setIdType(IdType.ID_CARD);
        customer.setIdNo(idNo);
        customer.setPhone(phone);
        customer.setEmail(email);
        customer.setAddress(address);
        customer.setMemberLevel(memberLevel);
        return customerRepository.save(customer);
    }

    private Reservation createReservation(Room room, Customer customer, LocalDate in, LocalDate out,
                                          int guests, String operator) {
        Reservation reservation = new Reservation();
        reservation.setOrderNo(serialNumberService.nextOrderNo());
        reservation.setCustomer(customer);
        reservation.setRoom(room);
        reservation.setCheckInDate(in);
        reservation.setCheckOutDate(out);
        reservation.setGuestCount(guests);
        reservation.setStatus(ReservationStatus.BOOKED);
        reservation.setUnitPrice(room.getRoomType().getPrice());
        reservation.setOperator(operator);
        return reservation;
    }

    /** 生成一条已退房订单与已结账账单 */
    private void checkoutHistory(Room room, Customer customer, int daysAgoCheckIn, int nights,
                                 BigDecimal otherAmount) {
        LocalDate today = LocalDate.now();
        LocalDate checkIn = today.minusDays(daysAgoCheckIn);
        LocalDate checkOut = checkIn.plusDays(nights);

        Reservation reservation = createReservation(room, customer, checkIn, checkOut, 2, "前台-小林");
        reservation.setStatus(ReservationStatus.CHECKED_OUT);
        reservation.setCheckInTime(checkIn.atTime(15, 0));
        reservation.setCheckOutTime(checkOut.atTime(12, 0));
        reservation.setActualCheckOutDate(checkOut);
        reservationRepository.save(reservation);

        Bill bill = new Bill();
        bill.setBillNo(serialNumberService.nextBillNo());
        bill.setReservation(reservation);
        bill.setCustomer(customer);
        bill.setRoom(room);
        BigDecimal roomAmount = reservation.getRoomAmount();
        bill.setRoomAmount(roomAmount);
        bill.setOtherAmount(otherAmount);
        bill.setDiscountAmount(BigDecimal.ZERO);
        bill.setEarlyCheckoutDiscount(BigDecimal.ZERO);
        bill.setTotalAmount(roomAmount.add(otherAmount));
        bill.setDeposit(new BigDecimal("300.00"));
        bill.setPaidAmount(bill.getTotalAmount());
        bill.setRefundAmount(bill.getDeposit().add(bill.getPaidAmount()).subtract(bill.getTotalAmount()));
        bill.setPayMethod(PayMethod.WECHAT);
        bill.setStatus(BillStatus.SETTLED);
        bill.setSettledAt(checkOut.atTime(12, 30));
        bill.setOperator("前台-小林");
        billRepository.save(bill);

        markRoom(room, RoomStatus.AVAILABLE);
    }

    private void markRoom(Room room, RoomStatus status) {
        Room fresh = roomRepository.findById(room.getId()).orElseThrow();
        fresh.setStatus(status);
        roomRepository.save(fresh);
    }
}
