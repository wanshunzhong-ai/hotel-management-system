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

    // 房态总览
    private long totalRooms;
    private long availableRooms;
    private long reservedRooms;
    private long occupiedRooms;
    private long cleaningRooms;
    private long maintenanceRooms;
    /** 入住率（%）= 在住房间 / 可售房间（已排除维修房） */
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
