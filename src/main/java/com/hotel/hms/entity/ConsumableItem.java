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
 * 客房消费项目目录（价目表）。
 *
 * <p>两个用途：
 * <ol>
 *     <li>住客扫码后看到的可选清单（只展示 {@link #enabled} 的条目）；</li>
 *     <li>前台在迷你吧挂账页快速选品，自动带出品名与单价，避免手输错价。</li>
 * </ol>
 * 价格只在这里维护，挂账时以目录价为默认值；前台仍可在一次挂账中改价（如促销）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "consumable_item", uniqueConstraints =
        @UniqueConstraint(name = "uk_consumable_name", columnNames = "item_name"))
public class ConsumableItem extends BaseEntity {

    @NotBlank(message = "请填写品名")
    @Size(max = 100)
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsumableCategory category = ConsumableCategory.MINIBAR;

    @NotNull(message = "请填写单价")
    @Min(value = 0, message = "单价不能为负数")
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    /** 是否在扫码页对住客可见 */
    @Column(nullable = false)
    private Boolean enabled = true;

    @Size(max = 200)
    @Column(length = 200)
    private String note;
}
