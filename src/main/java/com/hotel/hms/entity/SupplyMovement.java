package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 耗材出入库流水：每一次库存变动都留一条。
 *
 * <p>入库记供货单价；领用时 {@link #target} 记去向（房间号或领用人），
 * 报废时记原因——单看流水就能还原「这批东西去哪了」。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "supply_movement", indexes = {
        @Index(name = "idx_movement_item", columnList = "item_id"),
        @Index(name = "idx_movement_time", columnList = "moved_at")
})
public class SupplyMovement extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private SupplyItem item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplyMovementType type = SupplyMovementType.IN;

    /**
     * 变动数量。
     * <p>入库/领用/报废一律<b>正数</b>（方向由 {@link #type} 决定）；
     * 只有盘点调整允许为负，表示盘亏。
     */
    @NotNull
    @Column(nullable = false)
    private Integer quantity = 0;

    /** 变动后的库存快照，便于对账 */
    @Column(name = "stock_after", nullable = false)
    private Integer stockAfter = 0;

    /** 单价（入库成本 / 领用参考成本） */
    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost = BigDecimal.ZERO;

    /** 去向：房间号 / 领用人 / 供货商 */
    @Size(max = 100)
    @Column(length = 100)
    private String target;

    @Column(name = "moved_at")
    private LocalDateTime movedAt;

    @Size(max = 200)
    @Column(length = 200)
    private String remark;

    @Column(length = 50)
    private String operator;
}
