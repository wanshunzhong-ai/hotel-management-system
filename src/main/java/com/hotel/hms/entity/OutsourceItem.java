package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 布草外送批次明细行：一个品名 × 数量 × 单价。
 *
 * <p>{@link #linenStockId} 指向布草台账（可为空——台账里已删掉该品名时，
 * 历史批次仍要能看）。{@link #itemName} 是快照，保证批次单打印出来不会因为台账改名而变。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "outsource_item", indexes = {
        @Index(name = "idx_outsource_item_batch", columnList = "batch_id")
})
public class OutsourceItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private OutsourceBatch batch;

    /** 溯源用的布草台账 id，不做外键约束 */
    @Column(name = "linen_stock_id")
    private Long linenStockId;

    @NotBlank(message = "请填写布草品名")
    @Size(max = 100)
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Min(value = 1, message = "数量必须大于 0")
    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    public void recalculate() {
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        int qty = quantity == null ? 0 : quantity;
        this.amount = price.multiply(BigDecimal.valueOf(qty)).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
