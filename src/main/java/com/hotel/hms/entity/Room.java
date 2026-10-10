package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * 客房。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "room", uniqueConstraints = @UniqueConstraint(name = "uk_room_no", columnNames = "room_no"))
public class Room extends BaseEntity {

    /** 房号，如 8801 */
    @NotBlank(message = "房号不能为空")
    @Size(max = 20)
    @Column(name = "room_no", nullable = false, length = 20)
    private String roomNo;

    /** 楼层 */
    @NotNull(message = "楼层不能为空")
    @Min(value = 1, message = "楼层必须大于 0")
    @Column(nullable = false)
    private Integer floor = 1;

    @NotNull(message = "请选择房型")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomStatus status = RoomStatus.AVAILABLE;

    @Size(max = 200)
    @Column(length = 200)
    private String remark;

    /** 展示用全称 */
    public String getDisplayName() {
        return roomNo + (roomType == null ? "" : " · " + roomType.getName());
    }
}
