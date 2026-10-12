package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 客房耗材台账：一个品名一行，记录当前库存与安全库存。
 *
 * <p>与布草台账（{@link LinenStock}）的区别：布草是<b>循环周转</b>资产（在库/洗涤中/报废三态），
 * 耗材是<b>一次消耗</b>（入库 → 领用 → 用掉），因此只有一个库存数，
 * 每次变动都写一条 {@link SupplyMovement} 流水。
 *
 * <h3>库存如何得出</h3>
 * {@link #stock} 是<b>实时维护</b>的当前值（每次出入库直接改），
 * 同时每一笔变动都留流水可追溯。两者对不上的唯一可能是人工直接改库，
 * 因此盘点调整也必须走流水（{@link SupplyMovementType#ADJUST}）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "supply_item", uniqueConstraints =
        @UniqueConstraint(name = "uk_supply_name", columnNames = "item_name"))
public class SupplyItem extends BaseEntity {

    @NotBlank(message = "请填写耗材品名")
    @Size(max = 100)
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplyCategory category = SupplyCategory.AMENITY;

    /** 计量单位：瓶 / 盒 / 个 / 包 / 卷 */
    @Size(max = 20)
    @Column(length = 20)
    private String unit;

    /** 当前库存 */
    @NotNull
    @Min(value = 0, message = "库存不能为负")
    @Column(nullable = false)
    private Integer stock = 0;

    /** 安全库存：低于该值预警 */
    @NotNull
    @Min(value = 0, message = "安全库存不能为负")
    @Column(name = "min_stock", nullable = false)
    private Integer minStock = 0;

    /** 参考单价（用于估算领用成本，非计价依据） */
    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Size(max = 200)
    @Column(length = 200)
    private String note;

    /** 是否低于安全库存 */
    public boolean isBelowMin() {
        return stock != null && minStock != null && stock < minStock;
    }

    /** 展示用：品名（单位） */
    public String getDisplayName() {
        return itemName + (unit == null || unit.isBlank() ? "" : "（" + unit + "）");
    }
}
