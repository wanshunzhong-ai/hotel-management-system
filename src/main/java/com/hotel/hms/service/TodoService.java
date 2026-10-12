package com.hotel.hms.service;

import com.hotel.hms.config.LoginUser;
import com.hotel.hms.dto.TodoItem;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.InspectionResult;
import com.hotel.hms.entity.LostFoundStatus;
import com.hotel.hms.entity.MaintenanceStatus;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.TaskStatus;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.HousekeepingTaskRepository;
import com.hotel.hms.repository.LinenStockRepository;
import com.hotel.hms.repository.LostFoundRepository;
import com.hotel.hms.repository.MaintenanceOrderRepository;
import com.hotel.hms.repository.RoomInspectionRepository;
import com.hotel.hms.repository.SupplyItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 待办中心：把散落在各模块的「该我做/该催的事」聚合到一处。
 *
 * <h3>设计取舍</h3>
 * <ul>
 *     <li><b>不进消息表</b>：所有待办都是<b>实时派生</b>的（查库算出来），不落消息记录。
 *         好处是不会出现「消息已读但事还没做」或「事做完了消息还挂着」的不一致；
 *         代价是没有「谁在什么时候看过」的留痕——本系统定位是轻量运营台，这个取舍划算。</li>
 *     <li><b>按时效分档</b>：超时项单独统计，用红色标出来，避免淹没在正常待办里。</li>
 *     <li><b>按角色裁剪</b>：客房服务员只看到「自己的单 + 物资预警」；
 *         主管与前台/管理员才是真正的调度者，能看到全部排队情况。</li>
 * </ul>
 *
 * <p>阈值（写死为常量而非配置，是为了让「为什么这条算超时」在代码里一眼可见）：
 * 派工超时 4 小时、待验收超时 2 小时、维修积压 24 小时、失物无人认领 7 天。
 */
@Service
@RequiredArgsConstructor
public class TodoService {

    /** 派工后多久没完成算超时 */
    private static final int TASK_OVERDUE_HOURS = 4;
    /** 完成后多久没人验收算积压 */
    private static final int VERIFY_OVERDUE_HOURS = 2;
    /** 维修工单报修后多久没完工算积压 */
    private static final int MAINTENANCE_OVERDUE_HOURS = 24;
    /** 失物登记后多久无人认领算滞留 */
    private static final int LOST_FOUND_STALE_DAYS = 7;

    private final HousekeepingTaskRepository taskRepository;
    private final RoomInspectionRepository inspectionRepository;
    private final MaintenanceOrderRepository maintenanceOrderRepository;
    private final LostFoundRepository lostFoundRepository;
    private final LinenStockRepository linenStockRepository;
    private final SupplyItemRepository supplyItemRepository;
    private final BillRepository billRepository;

    /**
     * 组装当前用户的待办清单。
     *
     * <p>返回<b>全部</b>条目（含 count = 0 的），是否展示交给视图决定——
     * 这样「零待办」时页面能明确说「一切正常」，而不是空白一片。
     */
    @Transactional(readOnly = true)
    public List<TodoItem> list(LoginUser user) {
        List<TodoItem> items = new ArrayList<>();
        if (user == null) {
            return items;
        }
        boolean supervisorOrAbove = isSupervisorOrAbove(user);
        boolean frontDesk = user.isAdmin() || user.getRole() == Role.RECEPTION;
        LocalDateTime now = LocalDateTime.now();

        // ---- 工单类 ----
        if (supervisorOrAbove || frontDesk) {
            items.add(new TodoItem("待完成工单", taskRepository.countByStatus(TaskStatus.PENDING),
                    "warning", "bi-brush", "/housekeeping/tasks?status=PENDING", "尚未有服务员标记完成"));
            items.add(new TodoItem("待验收工单", taskRepository.countByStatus(TaskStatus.DONE),
                    "info", "bi-patch-check", "/housekeeping/tasks?status=DONE", "服务员已完成，等验收"));
        } else {
            long mine = taskRepository
                    .findByAssigneeIdAndStatusInOrderByIdDesc(user.getId(),
                            List.of(TaskStatus.PENDING, TaskStatus.REJECTED)).size();
            items.add(new TodoItem("我的待办工单", mine,
                    mine > 0 ? "danger" : "info", "bi-brush",
                    "/housekeeping/tasks", "派给我且尚未完成的清洁任务"));
        }

        if (supervisorOrAbove || frontDesk) {
            long overduePending = taskRepository
                    .findByStatusAndCreatedAtBeforeOrderByIdAsc(TaskStatus.PENDING,
                            now.minusHours(TASK_OVERDUE_HOURS)).size();
            items.add(new TodoItem("超时未完成", overduePending,
                    overduePending > 0 ? "danger" : "info", "bi-clock-history",
                    "/housekeeping/tasks?status=PENDING", "派出超过 " + TASK_OVERDUE_HOURS + " 小时仍未完成"));

            long overdueVerify = taskRepository
                    .findByStatusAndDoneAtBeforeOrderByIdAsc(TaskStatus.DONE,
                            now.minusHours(VERIFY_OVERDUE_HOURS)).size();
            items.add(new TodoItem("超时待验收", overdueVerify,
                    overdueVerify > 0 ? "danger" : "info", "bi-hourglass-split",
                    "/housekeeping/tasks?status=DONE", "完成超过 " + VERIFY_OVERDUE_HOURS + " 小时仍无人验收"));
        }

        long rectification = inspectionRepository
                .countPendingRectification(InspectionResult.FAIL, TaskStatus.APPROVED);
        items.add(new TodoItem("查房不合格待整改", rectification,
                rectification > 0 ? "danger" : "info", "bi-clipboard2-x",
                "/inspections?result=FAIL", "不合格查房的整改单尚未验收通过"));

        // ---- 物资类 ----
        long linenLow = linenStockRepository.countBelowMin();
        items.add(new TodoItem("布草低于安全库存", linenLow,
                linenLow > 0 ? "warning" : "info", "bi-layers",
                "/laundry", "在库数量已低于设定的安全库存"));

        long supplyLow = supplyItemRepository.countBelowMin();
        items.add(new TodoItem("耗材低于安全库存", supplyLow,
                supplyLow > 0 ? "warning" : "info", "bi-box-seam",
                "/supplies", "耗材库存已低于安全库存，需及时补货"));

        // ---- 其他 ----
        long maintenance = maintenanceOrderRepository.countOverdue(
                List.of(MaintenanceStatus.OPEN, MaintenanceStatus.FIXING),
                now.minusHours(MAINTENANCE_OVERDUE_HOURS));
        items.add(new TodoItem("维修积压", maintenance,
                maintenance > 0 ? "warning" : "info", "bi-tools",
                "/maintenance", "报修超过 " + MAINTENANCE_OVERDUE_HOURS + " 小时仍未完工"));

        long staleLost = lostFoundRepository.countByStatusAndCreatedAtBefore(
                LostFoundStatus.OPEN, now.minusDays(LOST_FOUND_STALE_DAYS));
        items.add(new TodoItem("失物滞留待认领", staleLost,
                "info", "bi-search-heart",
                "/lost-found", "登记超过 " + LOST_FOUND_STALE_DAYS + " 天仍无人认领"));

        if (frontDesk) {
            long unpaid = billRepository.countByStatus(BillStatus.UNPAID);
            items.add(new TodoItem("未结账单", unpaid,
                    unpaid > 0 ? "warning" : "info", "bi-receipt",
                    "/bills?status=UNPAID", "已退房但尚未结清的账单"));
        }
        return items;
    }

    /** 待办总条数（侧栏角标） */
    @Transactional(readOnly = true)
    public long countFor(LoginUser user) {
        return list(user).stream().filter(TodoItem::isVisible).mapToLong(TodoItem::count).sum();
    }

    private boolean isSupervisorOrAbove(LoginUser user) {
        return user.isAdmin() || user.getRole() == Role.HOUSEKEEPING_SUPERVISOR;
    }
}
