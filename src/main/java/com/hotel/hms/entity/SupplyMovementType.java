package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 耗材出入库流水类型。
 *
 * <p>库存数量只有两类操作会改动：{@link #IN}（加）与 {@link #OUT}/{@link #SCRAP}（减）；
 * {@link #ADJUST} 是盘点调整，可正可负——三者都留流水，保证「账面库存」可追溯到每一笔。
 */
@Getter
public enum SupplyMovementType {

    IN("入库", "success", 1),
    OUT("领用", "primary", -1),
    SCRAP("报废", "danger", -1),
    ADJUST("盘点调整", "secondary", 0);

    private final String label;
    private final String color;

    /**
     * 对库存的影响方向：1 加、-1 减、0 由数量正负自行决定（仅盘点调整）。
     * <p>领用与报废不做成「负数入库」，是为了让流水本身能一眼看出方向，
     * 不需要靠正负号反推。
     */
    private final int direction;

    SupplyMovementType(String label, String color, int direction) {
        this.label = label;
        this.color = color;
        this.direction = direction;
    }
}
