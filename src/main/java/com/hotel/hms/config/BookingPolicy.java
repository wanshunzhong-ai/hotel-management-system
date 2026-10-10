package com.hotel.hms.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 预订业务规则参数，对应 {@code application.yml} 中的 {@code hotel.*} 配置。
 * <p>
 * 这里把「最多可提前多少天预订」与「单次最多住多少晚」拆成两个独立参数：
 * 前者约束的是「今天 → 入住日」的距离，后者约束的是「入住日 → 离店日」的跨度，
 * 二者语义不同，不能共用一个值。
 */
@Getter
@Component
public class BookingPolicy {

    /** 最多可提前预订的天数（自今天起算）。 */
    private final int maxAdvanceDays;

    /** 单次预订最多可住晚数。 */
    private final int maxStayNights;

    public BookingPolicy(@Value("${hotel.max-advance-days:365}") int maxAdvanceDays,
                         @Value("${hotel.max-stay-nights:30}") int maxStayNights) {
        this.maxAdvanceDays = maxAdvanceDays;
        this.maxStayNights = maxStayNights;
    }
}
