package com.hotel.hms.service;

import com.hotel.hms.dto.DashboardStats;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.RoomType;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.CustomerRepository;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.RoomRepository;
import com.hotel.hms.repository.RoomTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页看板统计服务。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final RoomRepository roomRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final ReservationRepository reservationRepository;
    private final BillRepository billRepository;
    private final CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public DashboardStats build() {
        DashboardStats stats = new DashboardStats();
        LocalDate today = LocalDate.now();

        long total = roomRepository.count();
        long available = roomRepository.countByStatus(RoomStatus.AVAILABLE);
        long occupied = roomRepository.countByStatus(RoomStatus.OCCUPIED);
        long cleaning = roomRepository.countByStatus(RoomStatus.CLEANING);
        long maintenance = roomRepository.countByStatus(RoomStatus.MAINTENANCE);

        stats.setTotalRooms(total);
        stats.setAvailableRooms(available);
        stats.setOccupiedRooms(occupied);
        stats.setCleaningRooms(cleaning);
        stats.setMaintenanceRooms(maintenance);

        // 出租率口径（第三批 §1.1）：今日已售房 / 可售房。
        // 旧口径只算「在住」，把今天有预订但还没到店的房漏掉了，行业上算错。
        // 可售房需排除维修房，否则长期维修的房间会永久稀释出租率。
        long sellable = total - maintenance;
        long soldToday = reservationRepository.countOccupiedRoomsOn(today);
        stats.setSellableRooms(sellable);
        stats.setSoldRoomsToday(soldToday);
        stats.setAvailableToday(Math.max(sellable - soldToday, 0));
        stats.setOccupancyRate(sellable <= 0 ? 0d
                : BigDecimal.valueOf(soldToday * 100.0 / sellable).setScale(1, RoundingMode.HALF_UP).doubleValue());

        stats.setArrivalsToday(reservationRepository.countArrivalsOn(today));
        stats.setDeparturesToday(reservationRepository.countDeparturesOn(today));
        stats.setCheckedInToday(reservationRepository.countCheckedInBetween(today.atStartOfDay(), today.plusDays(1).atStartOfDay()));
        stats.setInHouseCount(reservationRepository.countByStatus(com.hotel.hms.entity.ReservationStatus.CHECKED_IN));
        stats.setTotalCustomers(customerRepository.count());
        stats.setUnsettledBills(billRepository.countByStatus(BillStatus.UNPAID));

        // 营收：全部用 SQL 聚合，避免把整张账单表拉进 JVM；
        // 口径为「应收合计」totalAmount，押金抵扣部分不会被漏计。
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDate trendStart = today.minusDays(6);

        stats.setTotalRevenue(nvl(billRepository.sumSettledRevenue()));
        stats.setTodayRevenue(nvl(billRepository.sumSettledRevenueBetween(todayStart, tomorrowStart)));
        stats.setMonthRevenue(nvl(billRepository.sumSettledRevenueBetween(monthStart, tomorrowStart)));

        // 在住订单的预计房费（整段住期，不是"今日一天"的营收）
        BigDecimal expected = reservationRepository.findInHouse().stream()
                .map(Reservation::getRoomAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.setInHouseExpectedRoomAmount(expected);

        // 近 7 日营收趋势：按结账日分组聚合，缺数据的天补 0
        Map<LocalDate, BigDecimal> daily = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            daily.put(today.minusDays(i), BigDecimal.ZERO);
        }
        for (Object[] row : billRepository.sumSettledRevenueGroupByDay(trendStart.atStartOfDay(), tomorrowStart)) {
            LocalDate date = LocalDate.of(((Number) row[0]).intValue(),
                    ((Number) row[1]).intValue(),
                    ((Number) row[2]).intValue());
            if (daily.containsKey(date)) {
                daily.put(date, nvl((BigDecimal) row[3]));
            }
        }
        List<String> labels = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        daily.forEach((k, v) -> {
            labels.add(k.format(DAY_FMT));
            values.add(v);
        });
        stats.setRevenueLabels(labels);
        stats.setRevenueValues(values);

        // 房型房量分布：一次 group by 取代逐个房型 count
        Map<Long, Long> countByType = new HashMap<>();
        for (Object[] row : roomRepository.countGroupByRoomType()) {
            countByType.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        List<String> typeLabels = new ArrayList<>();
        List<Long> typeValues = new ArrayList<>();
        for (RoomType type : roomTypeRepository.findAllByOrderByPriceAsc()) {
            typeLabels.add(type.getName());
            typeValues.add(countByType.getOrDefault(type.getId(), 0L));
        }
        stats.setRoomTypeLabels(typeLabels);
        stats.setRoomTypeValues(typeValues);

        // 今日待办
        stats.setTodayArrivalList(reservationRepository.findArrivalsOn(today));
        stats.setTodayDepartureList(reservationRepository.findDeparturesOn(today));

        // 高价房型 TOP 3
        stats.setTopRoomTypes(roomTypeRepository.findAllByOrderByPriceAsc().stream()
                .sorted((a, b) -> b.getPrice().compareTo(a.getPrice()))
                .limit(3)
                .toList());

        return stats;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 供控制器使用的时间边界 */
    public static LocalDateTime startOfDay(LocalDate date) {
        return LocalDateTime.of(date, LocalTime.MIN);
    }
}
