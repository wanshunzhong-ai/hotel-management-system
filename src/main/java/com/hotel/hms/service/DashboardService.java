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
        long reserved = roomRepository.countByStatus(RoomStatus.RESERVED);
        long occupied = roomRepository.countByStatus(RoomStatus.OCCUPIED);
        long cleaning = roomRepository.countByStatus(RoomStatus.CLEANING);
        long maintenance = roomRepository.countByStatus(RoomStatus.MAINTENANCE);

        stats.setTotalRooms(total);
        stats.setAvailableRooms(available);
        stats.setReservedRooms(reserved);
        stats.setOccupiedRooms(occupied);
        stats.setCleaningRooms(cleaning);
        stats.setMaintenanceRooms(maintenance);
        stats.setOccupancyRate(total == 0 ? 0d
                : BigDecimal.valueOf(occupied * 100.0 / total).setScale(1, RoundingMode.HALF_UP).doubleValue());

        stats.setArrivalsToday(reservationRepository.countArrivalsOn(today));
        stats.setDeparturesToday(reservationRepository.countDeparturesOn(today));
        stats.setCheckedInToday(reservationRepository.countCheckedInBetween(today.atStartOfDay(), today.plusDays(1).atStartOfDay()));
        stats.setInHouseCount(reservationRepository.countByStatus(com.hotel.hms.entity.ReservationStatus.CHECKED_IN));
        stats.setTotalCustomers(customerRepository.count());
        stats.setUnsettledBills(billRepository.countByStatus(BillStatus.UNPAID));

        // 营收
        List<com.hotel.hms.entity.Bill> settled = billRepository.findAll().stream()
                .filter(b -> b.getStatus() == BillStatus.SETTLED && b.getSettledAt() != null)
                .toList();

        BigDecimal totalRevenue = sum(settled);
        BigDecimal todayRevenue = sum(settled.stream()
                .filter(b -> b.getSettledAt().toLocalDate().isEqual(today))
                .toList());
        BigDecimal monthRevenue = sum(settled.stream()
                .filter(b -> b.getSettledAt().getYear() == today.getYear()
                        && b.getSettledAt().getMonthValue() == today.getMonthValue())
                .toList());

        stats.setTotalRevenue(totalRevenue);
        stats.setTodayRevenue(todayRevenue);
        stats.setMonthRevenue(monthRevenue);

        // 在住订单的预计房费
        BigDecimal expected = reservationRepository.findInHouse().stream()
                .map(Reservation::getRoomAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.setTodayExpectedRevenue(expected);

        // 近 7 日营收趋势
        Map<LocalDate, BigDecimal> daily = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            daily.put(today.minusDays(i), BigDecimal.ZERO);
        }
        for (com.hotel.hms.entity.Bill bill : settled) {
            LocalDate d = bill.getSettledAt().toLocalDate();
            if (daily.containsKey(d)) {
                daily.put(d, daily.get(d).add(bill.getPaidAmount()));
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

        // 房型房量分布
        List<String> typeLabels = new ArrayList<>();
        List<Long> typeValues = new ArrayList<>();
        for (RoomType type : roomTypeRepository.findAllByOrderByPriceAsc()) {
            typeLabels.add(type.getName());
            typeValues.add(roomRepository.countByRoomTypeId(type.getId()));
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

    private BigDecimal sum(List<com.hotel.hms.entity.Bill> bills) {
        return bills.stream()
                .map(b -> b.getPaidAmount() == null ? BigDecimal.ZERO : b.getPaidAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 供控制器使用的时间边界 */
    public static LocalDateTime startOfDay(LocalDate date) {
        return LocalDateTime.of(date, LocalTime.MIN);
    }
}
