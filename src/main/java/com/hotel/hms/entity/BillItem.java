package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 账单消费明细（迷你吧、洗衣、餐饮等）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bill_item")
public class BillItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    /** 项目名称 */
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer quantity = 1;

    /** 金额 = 单价 × 数量 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(length = 200)
    private String remark;

    public void recalculate() {
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        int qty = quantity == null ? 0 : quantity;
        this.amount = price.multiply(BigDecimal.valueOf(qty));
    }
}
