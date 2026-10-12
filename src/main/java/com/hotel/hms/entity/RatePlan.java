package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 房价方案（第六批：协议价 / 会员价）。
 *
 * <p>把「打几折」从靠前台手填折扣，变成有编码、有标准、可复用的方案：
 * <ul>
 *     <li><b>协议价</b>：对签约单位给固定折扣率或固定房价（如某公司 8 折）；</li>
 *     <li><b>会员价</b>：按客户会员等级自动匹配（如金卡 9 折、钻石 8.5 折）。</li>
 * </ul>
 *
 * <p>计价优先级：{@link #fixedPrice} &gt; {@link #discountRate} &gt; 门市价。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "rate_plan",
        uniqueConstraints = @UniqueConstraint(name = "uk_rate_plan_code", columnNames = "code"))
public class RatePlan extends BaseEntity {

    /** 方案编码，如 CORP-YUNQI / MEMBER-GOLD */
    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RatePlanType type = RatePlanType.CORPORATE;

    /** 折扣率：0.9000 = 9 折、0.8500 = 8.5 折 */
    @Column(name = "discount_rate", precision = 5, scale = 4)
    private BigDecimal discountRate;

    /** 固定房价（元/晚）；设置后优先于折扣率 */
    @Column(name = "fixed_price", precision = 10, scale = 2)
    private BigDecimal fixedPrice;

    /** 适用的会员等级（会员价专用）：普通 / 银卡 / 金卡 / 钻石；为空表示不按等级匹配 */
    @Column(name = "member_level", length = 20)
    private String memberLevel;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(length = 200)
    private String note;

    /**
     * 按本方案计算房价。
     *
     * @param listPrice 门市价（房型基准价）
     * @return 折后房价；方案未配置折扣时原样返回门市价
     */
    public BigDecimal priceFor(BigDecimal listPrice) {
        BigDecimal base = listPrice == null ? BigDecimal.ZERO : listPrice;
        if (fixedPrice != null && fixedPrice.signum() > 0) {
            return fixedPrice.setScale(2, RoundingMode.HALF_UP);
        }
        if (discountRate != null && discountRate.signum() > 0) {
            return base.multiply(discountRate).setScale(2, RoundingMode.HALF_UP);
        }
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    /** 折扣率展示：0.9 → 「9 折」，未配置 → 「—」 */
    public String getDiscountLabel() {
        if (fixedPrice != null && fixedPrice.signum() > 0) {
            return "固定 ￥" + fixedPrice.stripTrailingZeros().toPlainString();
        }
        if (discountRate == null || discountRate.signum() <= 0) {
            return "—";
        }
        BigDecimal tenths = discountRate.multiply(BigDecimal.TEN).setScale(2, RoundingMode.HALF_UP);
        return tenths.stripTrailingZeros().toPlainString() + " 折";
    }
}
