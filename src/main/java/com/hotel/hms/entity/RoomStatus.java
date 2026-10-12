package com.hotel.hms.entity;

import lombok.Getter;

import java.util.List;

/**
 * 房间的<b>物理</b>状态。
 *
 * <h3>语义收敛（第三批 §1.1）</h3>
 * 本枚举只回答一个问题：「这间房此刻物理上是什么状态」。
 * <b>不再负责表达「某天是否已被预订」</b>——某一天晚上这间房卖不卖得出去，
 * 一律由预约单按日期区间实时计算（半开区间 {@code [checkIn, checkOut)}），
 * 见 {@code RoomRepository#findAvailableBetween} 与 {@code RoomService#boardOn}。
 *
 * <p>收敛之前，{@code room.status} 同时承担了「物理状态」和「未来预订占位」两种语义：
 * 一间房 7 天后有订单，今天就被置为「已预订」，房态图和可售房数双双失真。
 * 收敛之后两者统一到同一套口径，不会再出现「房态图说不可售、可订房查询说可售」的矛盾。
 *
 * <p>{@link #RESERVED} 是历史遗留常量，已废弃。保留它的唯一目的是兼容可能残留在
 * 数据库里的旧值（避免 {@code valueOf} 抛异常）；存量数据由
 * {@code DataInitializer} 在启动时一次性迁移为 {@link #AVAILABLE}，
 * 业务代码不应再写入该值。
 */
@Getter
public enum RoomStatus {

    AVAILABLE("空闲", "success", "bi-door-open"),
    OCCUPIED("已入住", "danger", "bi-person-fill"),
    CLEANING("待清洁", "warning", "bi-stars"),
    MAINTENANCE("维修中", "secondary", "bi-tools"),

    /**
     * @deprecated 房态语义已收敛为物理状态，业务代码不应再写入。
     * 存量值由 {@code DataInitializer} 启动时迁移为 {@link #AVAILABLE}。
     */
    @Deprecated
    RESERVED("已预订", "info", "bi-bookmark-check");

    /**
     * UI 可展示、可选择的物理状态。
     * <p>请用本列表替代 {@code RoomStatus.values()}——后者会把废弃的 {@link #RESERVED} 也带出来。
     */
    private static final List<RoomStatus> PHYSICAL = List.of(AVAILABLE, OCCUPIED, CLEANING, MAINTENANCE);

    /**
     * 「变更房态」入口<b>允许写入</b>的状态（第八批缺口 6）。
     *
     * <p>不含 {@link #OCCUPIED}：「已入住」是<b>办理入住</b>这个业务动作的结果，
     * 不能由「改房态」凭空产生——否则一间空房被标成已入住后既没有订单支撑，
     * 又直接吃掉可售房量；而且按「在住只能转待清洁」的约束还得绕两步才能复原。
     * 房态图、客房列表、房间表单与移动端操作台的房态控件一律以本列表为准。
     */
    private static final List<RoomStatus> CHANGEABLE = List.of(AVAILABLE, CLEANING, MAINTENANCE);

    private final String label;
    /** Bootstrap 色板后缀 */
    private final String color;
    private final String icon;

    RoomStatus(String label, String color, String icon) {
        this.label = label;
        this.color = color;
        this.icon = icon;
    }

    /** 不含废弃值的物理状态列表，供图例、下拉框、筛选器等遍历。 */
    public static List<RoomStatus> physical() {
        return PHYSICAL;
    }

    /** 「变更房态」入口允许写入的状态列表——不含「已入住」（见 {@link #CHANGEABLE}）。 */
    public static List<RoomStatus> changeable() {
        return CHANGEABLE;
    }

    /** 是否为收敛后的物理状态（用于兜底过滤脏数据）。 */
    public boolean isPhysical() {
        return PHYSICAL.contains(this);
    }

    /** 是否允许通过「变更房态」入口写入（「已入住」只能由办理入住产生）。 */
    public boolean isChangeable() {
        return CHANGEABLE.contains(this);
    }

    /** 物理上是否处于「可以直接开房」的闲置状态。待清洁需先完成清洁，因此不算。 */
    public boolean isVacant() {
        return this == AVAILABLE;
    }
}
