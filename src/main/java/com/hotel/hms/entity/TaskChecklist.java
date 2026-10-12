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
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 某个派工单的<b>检查单执行记录</b>：一条 = 一个检查项的一次打勾状态。
 *
 * <h3>为什么存快照</h3>
 * {@link #itemName} 与 {@link #category} 是生成清单时从模板复制过来的，
 * 不是实时联表读模板。这样即使后来模板被改名、停用甚至删除，
 * 历史派工单上的清单仍然原样可查——审计要的就是「当时按什么标准做的」。
 *
 * <p>{@link #checklistItemId} 只作为溯源线索保留（不做外键），
 * 便于日后统计「哪一条标准最常被漏掉」。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_checklist", indexes = {
        @Index(name = "idx_checklist_task", columnList = "task_id")
})
public class TaskChecklist extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private HousekeepingTask task;

    /** 溯源的模板条目 id；模板删除后仍保留数字，不做外键约束 */
    @Column(name = "checklist_item_id")
    private Long checklistItemId;

    /** 条目名称快照 */
    @NotBlank
    @Size(max = 120)
    @Column(name = "item_name", nullable = false, length = 120)
    private String itemName;

    /** 条目分类快照 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChecklistCategory category = ChecklistCategory.HYGIENE;

    @Column(nullable = false)
    private Boolean checked = false;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "checked_by", length = 50)
    private String checkedBy;
}
