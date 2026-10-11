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

import java.time.LocalDateTime;

/**
 * 客房清洁派工单：派工 → 服务员完成 → 主管验收通过 / 打回重做。
 *
 * <p>验收通过时若房间为「待清洁」则自动改为「空闲」，形成房态闭环。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "housekeeping_task", indexes = {
        @Index(name = "idx_task_status", columnList = "status"),
        @Index(name = "idx_task_assignee", columnList = "assignee_id")
})
public class HousekeepingTask extends BaseEntity {

    @NotNull(message = "请选择房间")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskType type = TaskType.CHECKOUT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    /** 被派工的服务员 */
    @NotNull(message = "请选择服务员")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "assignee_id", nullable = false)
    private User assignee;

    /** 派工备注 */
    @Size(max = 200)
    @Column(length = 200)
    private String remark;

    /** 服务员标记完成时间 */
    @Column(name = "done_at")
    private LocalDateTime doneAt;

    /** 验收人（主管） */
    @Column(name = "inspector", length = 50)
    private String inspector;

    @Column(name = "inspected_at")
    private LocalDateTime inspectedAt;

    /** 验收备注（通过/打回原因） */
    @Column(name = "inspect_remark", length = 200)
    private String inspectRemark;
}
