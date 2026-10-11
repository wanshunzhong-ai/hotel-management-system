package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 查房结论。
 *
 * <p>合格线 {@link #PASS_SCORE}：四项各 25 分、总分 100 分，<b>80 分</b>及以上判为合格。
 * 不合格会自动生成整改派工单（见 {@code InspectionService}）。
 */
@Getter
public enum InspectionResult {

    PASS("合格", "success"),
    FAIL("不合格", "danger");

    /** 合格线（含） */
    public static final int PASS_SCORE = 80;

    private final String label;
    private final String color;

    InspectionResult(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public static InspectionResult of(int totalScore) {
        return totalScore >= PASS_SCORE ? PASS : FAIL;
    }
}
