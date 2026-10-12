package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.ChecklistCategory;
import com.hotel.hms.entity.ChecklistItem;
import com.hotel.hms.entity.HousekeepingTask;
import com.hotel.hms.entity.TaskChecklist;
import com.hotel.hms.entity.TaskType;
import com.hotel.hms.repository.ChecklistItemRepository;
import com.hotel.hms.repository.TaskChecklistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清洁标准检查单服务。
 *
 * <h3>两条链路</h3>
 * <ol>
 *     <li><b>模板</b>：把「一间走客房该做哪些事」沉淀成条目（{@link ChecklistItem}），可在
 *         {@code /checklists} 维护；</li>
 *     <li><b>执行</b>：派工单创建时按模板<b>快照</b>生成清单（{@link TaskChecklist}），
 *         服务员逐条打勾，标记完成时要求全勾。</li>
 * </ol>
 *
 * <p>这里刻意不依赖 {@link HousekeepingService}（只依赖任务仓库），
 * 由 {@code HousekeepingService} 反向调用本服务——否则两个 Service 互相注入会形成循环依赖。
 */
@Service
@RequiredArgsConstructor
public class ChecklistService {

    private final ChecklistItemRepository checklistItemRepository;
    private final TaskChecklistRepository taskChecklistRepository;
    private final OperationLogService operationLogService;

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChecklistItem> listItems() {
        return checklistItemRepository.findAllByOrderByCategoryAscSortOrderAscIdAsc();
    }

    /** 按分类分组，供模板页分区展示 */
    @Transactional(readOnly = true)
    public Map<ChecklistCategory, List<ChecklistItem>> groupedItems() {
        Map<ChecklistCategory, List<ChecklistItem>> grouped = new LinkedHashMap<>();
        for (ChecklistItem item : listItems()) {
            grouped.computeIfAbsent(item.getCategory(), k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    public long countEnabled() {
        return checklistItemRepository.countByEnabledTrue();
    }

    @Transactional
    public Long createItem(String itemName, ChecklistCategory category, TaskType taskType,
                           Integer sortOrder, String note, String operator) {
        String name = requireName(itemName);
        if (checklistItemRepository.existsByItemName(name)) {
            throw new BusinessException("检查项「" + name + "」已存在");
        }
        ChecklistItem item = new ChecklistItem();
        item.setItemName(name);
        item.setCategory(category == null ? ChecklistCategory.HYGIENE : category);
        item.setTaskType(taskType);
        item.setSortOrder(sortOrder == null || sortOrder < 0 ? 0 : sortOrder);
        item.setEnabled(true);
        item.setNote(note);
        ChecklistItem saved = checklistItemRepository.save(item);
        operationLogService.record("检查项新增", "checklist_item", saved.getId(),
                saved.getDisplayName()
                        + (taskType == null ? "（通用）" : "（仅" + taskType.getLabel() + "）"), operator);
        return saved.getId();
    }

    @Transactional
    public void updateItem(Long id, ChecklistCategory category, TaskType taskType,
                           Integer sortOrder, Boolean enabled, String note, String operator) {
        ChecklistItem item = getItem(id);
        item.setCategory(category == null ? item.getCategory() : category);
        item.setTaskType(taskType);
        item.setSortOrder(sortOrder == null || sortOrder < 0 ? item.getSortOrder() : sortOrder);
        item.setEnabled(enabled == null ? item.getEnabled() : enabled);
        item.setNote(note);
        checklistItemRepository.save(item);
        operationLogService.record("检查项修改", "checklist_item", id,
                item.getDisplayName() + " / " + (Boolean.TRUE.equals(item.getEnabled()) ? "启用" : "停用")
                        + " / 排序 " + item.getSortOrder(), operator);
    }

    @Transactional
    public void deleteItem(Long id, String operator) {
        ChecklistItem item = getItem(id);
        checklistItemRepository.delete(item);
        operationLogService.record("检查项删除", "checklist_item", id, item.getDisplayName(), operator);
    }

    // ------------------------------------------------------------------
    // 执行清单
    // ------------------------------------------------------------------

    /**
     * 为派工单按模板生成执行清单（快照）。
     *
     * <p>幂等：已有清单的任务不再重复生成——避免「打回后重新完成」时清单翻倍。
     *
     * @return 本次生成的条目数
     */
    @Transactional
    public int materialize(HousekeepingTask task) {
        if (task == null || task.getId() == null) {
            return 0;
        }
        if (taskChecklistRepository.countByTaskId(task.getId()) > 0) {
            return 0;
        }
        List<ChecklistItem> applicable = checklistItemRepository.findApplicable(task.getType());
        if (applicable.isEmpty()) {
            return 0;
        }
        List<TaskChecklist> records = new ArrayList<>();
        for (ChecklistItem item : applicable) {
            TaskChecklist record = new TaskChecklist();
            record.setTask(task);
            record.setChecklistItemId(item.getId());
            record.setItemName(item.getItemName());
            record.setCategory(item.getCategory());
            record.setChecked(false);
            records.add(record);
        }
        taskChecklistRepository.saveAll(records);
        return records.size();
    }

    @Transactional(readOnly = true)
    public List<TaskChecklist> recordsOf(Long taskId) {
        return taskChecklistRepository.findByTaskIdOrderByCategoryAscIdAsc(taskId);
    }

    /**
     * 批量取多个任务的清单进度，返回 {任务id → [已完成数, 总数]}。
     * <p>列表页一次查完，避免每行一次查询。
     */
    @Transactional(readOnly = true)
    public Map<Long, int[]> progressOf(Collection<Long> taskIds) {
        Map<Long, int[]> result = new HashMap<>();
        if (taskIds == null || taskIds.isEmpty()) {
            return result;
        }
        for (Long id : taskIds) {
            long total = taskChecklistRepository.countByTaskId(id);
            long done = taskChecklistRepository.countByTaskIdAndCheckedTrue(id);
            result.put(id, new int[]{(int) done, (int) total});
        }
        return result;
    }

    /** 单任务进度：[已完成, 总数] */
    @Transactional(readOnly = true)
    public int[] progressOf(Long taskId) {
        return new int[]{
                (int) taskChecklistRepository.countByTaskIdAndCheckedTrue(taskId),
                (int) taskChecklistRepository.countByTaskId(taskId)
        };
    }

    @Transactional
    public void toggle(Long recordId, Boolean checked, String operator) {
        TaskChecklist record = taskChecklistRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException("检查项不存在"));
        boolean value = Boolean.TRUE.equals(checked);
        record.setChecked(value);
        record.setCheckedBy(value ? operator : null);
        record.setCheckedAt(value ? LocalDateTime.now() : null);
        taskChecklistRepository.save(record);
    }

    /**
     * 一键全勾 / 全清（派工单详情页的快捷操作）。
     *
     * <p>提供这个动作不是为了「糊弄验收」——实际作业里服务员做完一轮后
     * 往往只剩零星一两项忘了点，一键全勾比来回点几十下更不容易漏。
     * 真正的把关在验收环节（主管会看清单与房间实况）。
     *
     * @return 受影响的条目数
     */
    @Transactional
    public int toggleAll(Long taskId, Boolean checked, String operator) {
        List<TaskChecklist> records = taskChecklistRepository.findByTaskIdOrderByCategoryAscIdAsc(taskId);
        boolean value = Boolean.TRUE.equals(checked);
        LocalDateTime now = LocalDateTime.now();
        for (TaskChecklist record : records) {
            record.setChecked(value);
            record.setCheckedBy(value ? operator : null);
            record.setCheckedAt(value ? now : null);
        }
        taskChecklistRepository.saveAll(records);
        return records.size();
    }

    /** 全部打勾？没有清单时视为通过（老数据/无模板不阻塞流程） */
    @Transactional(readOnly = true)
    public boolean allChecked(Long taskId) {
        long total = taskChecklistRepository.countByTaskId(taskId);
        if (total == 0) {
            return true;
        }
        return taskChecklistRepository.countByTaskIdAndCheckedTrue(taskId) >= total;
    }

    @Transactional(readOnly = true)
    public long uncheckedCount(Long taskId) {
        long total = taskChecklistRepository.countByTaskId(taskId);
        long done = taskChecklistRepository.countByTaskIdAndCheckedTrue(taskId);
        return Math.max(0, total - done);
    }

    // ------------------------------------------------------------------

    private ChecklistItem getItem(Long id) {
        return checklistItemRepository.findById(id)
                .orElseThrow(() -> new BusinessException("检查项不存在"));
    }

    private String requireName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException("请填写检查项名称");
        }
        return itemName.trim();
    }
}
