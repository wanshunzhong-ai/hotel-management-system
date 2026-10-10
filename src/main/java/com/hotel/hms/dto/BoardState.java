package com.hotel.hms.dto;

import lombok.Getter;

import java.util.List;

/**
 * 房态图上一个「房间 × 日期」单元格的<b>营业状态</b>。
 *
 * <p>与 {@link com.hotel.hms.entity.RoomStatus}（物理状态）不同，本枚举回答的是
 * 「<b>这一天晚上，这间房卖不卖得出去</b>」。房态图带上日期选择器之后，
 * 同一间房在不同日期可以有不同的营业状态——这正是第三批 §1.1 想要的效果。
 *
 * <p>判定顺序与 {@code RoomRepository#findAvailableBetween} 保持一致：先看维修，
 * 再看该晚有无有效预约（半开区间，离店当天不占房）。
 */
@Getter
public enum BoardState {

    /** 该晚没有客人，物理上也空闲 */
    FREE("空闲", "success", "bi-door-open", true),
    /** 该晚没有客人，但房间待清洁（清洁完成后即可售） */
    CLEANING("待清洁", "warning", "bi-stars", true),
    /** 客人今天离店，当晚可售（房态图给前台的最有价值提示） */
    DEPARTING("今日离店", "warning", "bi-box-arrow-right", true),

    /**
     * 该晚是某张有效预约的<b>入住当晚</b>：房已为他保留，当晚不可售。
     *
     * <p>标签刻意不带「今日」二字——房态图可以翻到任意日期，
     * 写成「今日预抵」在看未来日期时会误导前台。
     */
    ARRIVING("预抵", "info", "bi-box-arrow-in-right", false),
    /** 该晚落在某张有效预约的中间夜（客人还没来，但晚上这间房已被订走） */
    RESERVED("已预订", "primary", "bi-bookmark-check", false),
    /** 客人正在住 */
    IN_HOUSE("在住", "danger", "bi-person-fill", false),
    /** 维修中，不参与销售 */
    MAINTENANCE("维修中", "secondary", "bi-tools", false);

    /** 图例与筛选器的展示顺序：先可售，后不可售 */
    private static final List<BoardState> DISPLAY_ORDER =
            List.of(FREE, CLEANING, DEPARTING, ARRIVING, RESERVED, IN_HOUSE, MAINTENANCE);

    private final String label;
    /** Bootstrap 色板后缀 */
    private final String color;
    private final String icon;
    /** 当晚是否可售 */
    private final boolean sellable;

    BoardState(String label, String color, String icon, boolean sellable) {
        this.label = label;
        this.color = color;
        this.icon = icon;
        this.sellable = sellable;
    }

    public static List<BoardState> displayOrder() {
        return DISPLAY_ORDER;
    }
}
