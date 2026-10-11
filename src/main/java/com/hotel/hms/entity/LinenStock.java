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

/**
 * 布草库存：每个品名一行，记录在库 / 洗涤中 / 累计报废 / 安全库存。
 *
 * <h3>数量守恒</h3>
 * 任何一次操作都只在三态之间搬运，不凭空增减：
 * <ul>
 *     <li>领用出库 {@code issue}：在库 → 洗涤中</li>
 *     <li>洗涤回库 {@code back}：洗涤中 → 在库</li>
 *     <li>报废 {@code scrap}：在库 → 报废（不可逆）</li>
 * </ul>
 * 因此「在库 + 洗涤中 + 报废」只会因新购（手工调整在库）而变大。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "linen_stock", uniqueConstraints =
        @UniqueConstraint(name = "uk_linen_name", columnNames = "item_name"))
public class LinenStock extends BaseEntity {

    @NotBlank(message = "请填写布草品名")
    @Size(max = 100)
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LinenCategory category = LinenCategory.BEDDING;

    /** 在库可用数量 */
    @NotNull
    @Min(value = 0, message = "在库数量不能为负")
    @Column(name = "in_stock", nullable = false)
    private Integer inStock = 0;

    /** 已送洗未回数量 */
    @NotNull
    @Min(value = 0, message = "洗涤中数量不能为负")
    @Column(name = "in_wash", nullable = false)
    private Integer inWash = 0;

    /** 累计报废数量 */
    @NotNull
    @Min(value = 0, message = "报废数量不能为负")
    @Column(nullable = false)
    private Integer scrapped = 0;

    /** 安全库存：在库低于该值即预警 */
    @NotNull
    @Min(value = 0, message = "安全库存不能为负")
    @Column(name = "min_stock", nullable = false)
    private Integer minStock = 0;

    @Size(max = 200)
    @Column(length = 200)
    private String note;

    /** 在库是否低于安全库存 */
    public boolean isBelowMin() {
        return inStock != null && minStock != null && inStock < minStock;
    }

    /** 总量（在库 + 洗涤中 + 报废） */
    public int getTotal() {
        return nz(inStock) + nz(inWash) + nz(scrapped);
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
