package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 清洁标准检查单的<b>模板条目</b>。
 *
 * <p>解决的痛点：查房只有「四项各 25 分」这种主观打分，标准不可复现，新人不知道
 * 具体该做哪几件事。模板把「一间走客房要做什么」拆成一条条可打勾的动作，
 * 派工单创建时按模板生成执行清单，服务员做完一条勾一条。
 *
 * <p>模板改动<b>不影响</b>已生成的清单——执行清单保存的是条目快照
 * （见 {@link TaskChecklist#getItemName()}），改标准不会让历史记录面目全非。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "checklist_item")
public class ChecklistItem extends BaseEntity {

    @NotBlank(message = "请填写检查项名称")
    @Size(max = 120)
    @Column(name = "item_name", nullable = false, length = 120)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChecklistCategory category = ChecklistCategory.HYGIENE;

    /** 适用的任务类型；为空表示所有类型通用 */
    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", length = 20)
    private TaskType taskType;

    /** 排序号，越小越靠前 */
    @NotNull
    @Min(value = 0, message = "排序号不能为负")
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Size(max = 200)
    @Column(length = 200)
    private String note;

    /** 展示用：分类 + 名称 */
    public String getDisplayName() {
        return category.getLabel() + " · " + itemName;
    }
}
