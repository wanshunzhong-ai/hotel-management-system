package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 客房耗材分类。
 *
 * <p>与 {@link ConsumableCategory}（对客消费价目表）不同：这里是<b>内部物料成本</b>口径，
 * 记录客房六小件、清洁剂等消耗品的库存与出入库，不直接向客人计价。
 */
@Getter
public enum SupplyCategory {

    AMENITY("客房六小件", "bi-basket"),
    CLEANING("清洁用品", "bi-droplet-half"),
    PAPER("纸品", "bi-file-earmark"),
    LAUNDRY("洗涤耗材", "bi-bag"),
    OTHER("其他", "bi-three-dots");

    private final String label;
    private final String icon;

    SupplyCategory(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }
}
