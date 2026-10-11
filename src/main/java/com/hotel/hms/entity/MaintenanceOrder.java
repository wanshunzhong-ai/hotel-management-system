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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 维修工单：报修 → 开工 → 完工。
 *
 * <p>联动房态：空闲/待清洁的房间报修时自动置「维修中」；完工后置「待清洁」。
 * 在住房间报修不改动房态（客人还住着）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "maintenance_order", indexes = {
        @Index(name = "idx_maintenance_status", columnList = "status"),
        @Index(name = "idx_maintenance_room", columnList = "room_id")
})
public class MaintenanceOrder extends BaseEntity {

    @NotNull(message = "请选择房间")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /** 故障描述 */
    @NotBlank(message = "请描述故障情况")
    @Column(nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MaintenanceStatus status = MaintenanceStatus.OPEN;

    /** 维修人 */
    @Size(max = 50)
    @Column(length = 50)
    private String handler;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 维修结果说明 */
    @Size(max = 500)
    @Column(length = 500)
    private String result;

    /** 维修费用（备件等） */
    @Column(precision = 12, scale = 2)
    private BigDecimal cost = BigDecimal.ZERO;

    /** 报修操作人 */
    @Column(length = 50)
    private String operator;
}
