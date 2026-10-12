package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 客房部班次。
 *
 * <p>与前台的「交接班对账」（{@code shift_handover}）不是一回事：
 * 那个是<b>收银对账</b>，记录当班收了多少钱；这个是<b>人力排班</b>，
 * 记录谁在哪个班次当值，用于派工与工作量考核。
 */
@Getter
public enum DayShift {

    MORNING("早班", "08:00–16:00"),
    EVENING("中班", "16:00–24:00"),
    NIGHT("夜班", "00:00–08:00");

    private final String label;
    private final String timeRange;

    DayShift(String label, String timeRange) {
        this.label = label;
        this.timeRange = timeRange;
    }
}
