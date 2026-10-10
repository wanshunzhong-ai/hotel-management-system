package com.hotel.hms.dto;

import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.RoomType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页看板统计数据。
 */
@Getter
@Setter
public class DashboardStats {

    // 房态总览（物理状态，四项之和 = 总房量）
    private long totalRooms;
    private long availableRooms;
    private long occupiedRooms;
    private long cleaningRooms;
    private long maintenanceRooms;

    // 今日销售口径（第三批 §1.1 收敛后与房态图同源）
    /** 可售房量 = 总房量 − 维修房 */
    private long sellableRooms;
    /** 今日已售房量（今晚有有效订单的房间数，已入住与已预订都算） */
    private long soldRoomsToday;
    /** 今日剩余可售房量 = 可售房量 − 今日已售房量 */
    private long availableToday;
    /** 出租率（%）= 今日已售房 / 可售房 */
    private double occupancyRate;

    // 今日业务
    private long arrivalsToday;
    private long departuresToday;
    private long checkedInToday;
    private long inHouseCount;
    private long totalCustomers;
    private long unsettledBills;

    // 营收（口径：已结账账单的「应收合计」，不含未结账账单）
    private BigDecimal todayRevenue = BigDecimal.ZERO;
    private BigDecimal monthRevenue = BigDecimal.ZERO;
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    /**
     * 在住订单的预计房费合计（整段住期，非「今日一天」的营收）。
     * 用于衡量尚未结算的房费规模。
     */
    private BigDecimal inHouseExpectedRoomAmount = BigDecimal.ZERO;

    // 图表
    private List<String> revenueLabels = new ArrayList<>();
    private List<BigDecimal> revenueValues = new ArrayList<>();
    private List<String> roomTypeLabels = new ArrayList<>();
    private List<Long> roomTypeValues = new ArrayList<>();

    // 今日待办清单
    private List<Reservation> todayArrivalList = new ArrayList<>();
    private List<Reservation> todayDepartureList = new ArrayList<>();
    private List<RoomType> topRoomTypes = new ArrayList<>();
}
