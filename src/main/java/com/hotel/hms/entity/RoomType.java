package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 房型。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "room_type")
public class RoomType extends BaseEntity {

    @NotBlank(message = "房型名称不能为空")
    @Size(max = 50, message = "房型名称不超过 50 个字符")
    @Column(nullable = false, length = 50)
    private String name;

    /** 床型，如「1.8m 大床」 */
    @NotBlank(message = "床型不能为空")
    @Size(max = 50)
    @Column(name = "bed_type", nullable = false, length = 50)
    private String bedType;

    /** 面积（平方米） */
    @Min(value = 1, message = "面积必须大于 0")
    @Column(nullable = false)
    private Integer area = 25;

    /** 可住人数 */
    @NotNull(message = "可住人数不能为空")
    @Min(value = 1, message = "可住人数至少 1 人")
    @Column(nullable = false)
    private Integer capacity = 2;

    /** 门市价（元/晚） */
    @NotNull(message = "房价不能为空")
    @DecimalMin(value = "0.01", message = "房价必须大于 0")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Size(max = 255)
    @Column(length = 255)
    private String description;

    /** 是否启用（停用后不可新建该房型的房间） */
    @Column(nullable = false)
    private Boolean enabled = true;
}
