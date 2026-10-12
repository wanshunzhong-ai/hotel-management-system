package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 清洁标准检查单的条目分类。
 *
 * <p>与查房评分的四项口径（卫生/设施/布草/客用品）刻意对齐，
 * 这样「服务员照着做」与「主管照着评」用的是同一套语言。
 */
@Getter
public enum ChecklistCategory {

    HYGIENE("卫生", "bi-droplet"),
    FACILITY("设施", "bi-lightning-charge"),
    LINEN("布草", "bi-layers"),
    AMENITY("客用品", "bi-box-seam"),
    OTHER("其他", "bi-three-dots");

    private final String label;
    private final String icon;

    ChecklistCategory(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }
}
