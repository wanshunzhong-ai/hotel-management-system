package com.hotel.hms.config;

import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.Customer;
import com.hotel.hms.entity.Gender;
import com.hotel.hms.entity.IdType;
import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.entity.RoomType;
import com.hotel.hms.entity.User;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.CustomerRepository;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.RoomRepository;
import com.hotel.hms.repository.RoomTypeRepository;
import com.hotel.hms.repository.UserRepository;
import com.hotel.hms.service.SerialNumberService;
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
    private final SerialNumberService serialNumberService;
    private final PasswordEncoder passwordEncoder;

    @Value("${hotel.init-demo-data:true}")
    private boolean initDemoData;

    @Override
    @Transactional
    public void run(String... args) {
        initUsers();
        if (!initDemoData) {
            return;
        }
        if (roomTypeRepository.count() == 0) {
            initHotelData();
            log.info("演示数据初始化完成。");
        }
    }

    // ------------------------------------------------------------------
    // 账号
    // ------------------------------------------------------------------
    private void initUsers() {
        createIfAbsent("admin", "admin123", "系统管理员", Role.ADMIN, "13800000001");
        createIfAbsent("reception", "123456", "前台-小林", Role.RECEPTION, "13800000002");
        createIfAbsent("housekeeping", "123456", "客房-王姐", Role.HOUSEKEEPING, "13800000003");
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

        // 2) 今日预抵
        Reservation arrival1 = createReservation(rooms.get(25), customers.get(3), today, today.plusDays(1), 2, "前台-小林");
        reservationRepository.save(arrival1);
        markRoom(rooms.get(25), RoomStatus.RESERVED);

        // 3) 未来预订
        reservationRepository.save(createReservation(rooms.get(30), customers.get(4), today.plusDays(3), today.plusDays(5), 3, "前台-小林"));
        markRoom(rooms.get(30), RoomStatus.RESERVED);
        reservationRepository.save(createReservation(rooms.get(33), customers.get(5), today.plusDays(7), today.plusDays(9), 2, "系统管理员"));
        markRoom(rooms.get(33), RoomStatus.RESERVED);

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
        reservationRepository.save(reservation);

        Bill bill = new Bill();
        bill.setBillNo(serialNumberService.nextBillNo());
        bill.setReservation(reservation);
        bill.setCustomer(customer);
        bill.setRoom(room);
        BigDecimal roomAmount = reservation.getRoomAmount();
        bill.setRoomAmount(roomAmount);
        bill.setOtherAmount(otherAmount);
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
