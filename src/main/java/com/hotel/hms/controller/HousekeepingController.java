package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.MinibarStatus;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.TaskStatus;
import com.hotel.hms.entity.TaskType;
import com.hotel.hms.service.ChecklistService;
import com.hotel.hms.service.ConsumableService;
import com.hotel.hms.service.HousekeepingService;
import com.hotel.hms.service.MinibarService;
import com.hotel.hms.service.ReservationService;
import com.hotel.hms.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客房部工作台：派工验收 + 迷你吧挂账。
 *
 * <h3>第七批权限调整</h3>
 * 派工与验收从「管理员 / 前台」扩到 <b>再加客房主管</b>：
 * 主管本来就要管排班与验收，让他能自己派工是理顺流程，而不是放权。
 * 客房服务员仍然不能派工、不能验收（不能自己给自己派活、也不能自己验自己的房）。
 */
@Controller
@RequestMapping("/housekeeping")
@RequiredArgsConstructor
public class HousekeepingController {

    private final HousekeepingService housekeepingService;
    private final ChecklistService checklistService;
    private final MinibarService minibarService;
    private final ConsumableService consumableService;
    private final RoomService roomService;
    private final ReservationService reservationService;

    // ---------- 派工 / 验收 ----------

    @GetMapping("/tasks")
    public String tasks(@RequestParam(required = false) TaskStatus status,
                        @RequestParam(required = false) Long assigneeId,
                        @AuthenticationPrincipal LoginUser loginUser,
                        Model model) {
        List<com.hotel.hms.entity.HousekeepingTask> tasks = housekeepingService.list(status, assigneeId);
        model.addAttribute("tasks", tasks);
        // 检查单进度：一次批量查出，避免每行一次查询
        model.addAttribute("checklistProgress",
                checklistService.progressOf(tasks.stream().map(com.hotel.hms.entity.HousekeepingTask::getId).toList()));
        model.addAttribute("staff", housekeepingService.listStaff());
        model.addAttribute("rooms", roomService.findAll());
        model.addAttribute("taskStatuses", TaskStatus.values());
        model.addAttribute("taskTypes", TaskType.values());
        model.addAttribute("status", status);
        model.addAttribute("assigneeId", assigneeId);
        model.addAttribute("pendingCount", housekeepingService.countByStatus(TaskStatus.PENDING));
        model.addAttribute("doneCount", housekeepingService.countByStatus(TaskStatus.DONE));
        model.addAttribute("canDispatch", canDispatch(loginUser));
        model.addAttribute("active", "housekeeping");
        return "housekeeping/tasks";
    }

    /** 派工单详情：检查单逐项打勾 + 全流程时间线 */
    @GetMapping("/tasks/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         Model model) {
        com.hotel.hms.entity.HousekeepingTask task = housekeepingService.get(id);
        model.addAttribute("task", task);
        model.addAttribute("records", checklistService.recordsOf(id));
        model.addAttribute("progress", checklistService.progressOf(id));
        model.addAttribute("canDispatch", canDispatch(loginUser));
        model.addAttribute("canMarkDone", canOperate(loginUser, task));
        model.addAttribute("active", "housekeeping");
        return "housekeeping/task-detail";
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String create(@RequestParam Long roomId,
                         @RequestParam(required = false) TaskType type,
                         @RequestParam Long assigneeId,
                         @RequestParam(required = false) String remark,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            housekeepingService.create(roomId, type, assigneeId, remark, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "派工单已创建，检查单已按标准生成");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/tasks";
    }

    @PostMapping("/tasks/{id}/done")
    public String done(@PathVariable Long id,
                       @AuthenticationPrincipal LoginUser loginUser,
                       RedirectAttributes ra) {
        try {
            housekeepingService.markDone(id, loginUser.getId(), canOperateOthers(loginUser),
                    operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已标记完成，等待验收");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/tasks/" + id;
    }

    // ---------- 清洁检查单 ----------

    /** 单项打勾 / 取消打勾 */
    @PostMapping("/tasks/{id}/checklist")
    public String toggleChecklist(@PathVariable Long id,
                                  @RequestParam Long recordId,
                                  @RequestParam(required = false) Boolean checked,
                                  @AuthenticationPrincipal LoginUser loginUser,
                                  RedirectAttributes ra) {
        try {
            checklistService.toggle(recordId, checked, operatorOf(loginUser));
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/tasks/" + id;
    }

    /** 一键全勾 / 一键全清 */
    @PostMapping("/tasks/{id}/checklist/all")
    public String toggleAllChecklist(@PathVariable Long id,
                                     @RequestParam(defaultValue = "true") Boolean checked,
                                     @AuthenticationPrincipal LoginUser loginUser,
                                     RedirectAttributes ra) {
        try {
            int n = checklistService.toggleAll(id, checked, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", Boolean.TRUE.equals(checked)
                    ? "已全部打勾（" + n + " 项）" : "已清空全部打勾");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/tasks/" + id;
    }

    /**
     * 验收：action=approve 通过（房间自动转空闲）/ reject 打回。
     *
     * <p>开放给管理员、前台与客房主管：派工与验收权限对称，闭环才跑得通。
     * 客房服务员自身仍然不能验收（不能自己验自己做的房）。
     */
    @PostMapping("/tasks/{id}/inspect")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String inspect(@PathVariable Long id,
                          @RequestParam String action,
                          @RequestParam(required = false) String inspectRemark,
                          @AuthenticationPrincipal LoginUser loginUser,
                          RedirectAttributes ra) {
        try {
            if ("reject".equals(action)) {
                housekeepingService.reject(id, inspectRemark, operatorOf(loginUser));
                ra.addFlashAttribute("successMessage", "已打回重做");
            } else {
                housekeepingService.approve(id, inspectRemark, operatorOf(loginUser));
                ra.addFlashAttribute("successMessage", "验收通过，房间已可售");
            }
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/tasks/" + id;
    }

    // ---------- 迷你吧 / 客用品挂账 ----------

    @GetMapping("/minibar")
    public String minibar(@RequestParam(required = false) MinibarStatus status,
                          @AuthenticationPrincipal LoginUser loginUser,
                          Model model) {
        List<Reservation> inHouse = reservationService.findInHouse();
        model.addAttribute("items", minibarService.list(status));
        model.addAttribute("inHouse", inHouse);
        // 消费目录：前台快速选品，自动带出品名与单价（避免手输错价）
        model.addAttribute("catalog", consumableService.enabledList());
        model.addAttribute("statuses", MinibarStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("pendingCount", minibarService.countByStatus(MinibarStatus.PENDING));
        // 撤销按钮的可见性：管理员/前台可撤任意一笔，客房部只显示自己录的那几笔
        model.addAttribute("canUndoOthers", canUndoOthers(loginUser));
        model.addAttribute("currentName", loginUser == null ? null : loginUser.getRealName());
        model.addAttribute("active", "minibar");
        return "housekeeping/minibar";
    }

    @PostMapping("/minibar")
    public String addMinibar(@RequestParam Long roomId,
                             @RequestParam String itemName,
                             @RequestParam(required = false) Integer quantity,
                             @RequestParam(required = false) BigDecimal unitPrice,
                             @RequestParam(required = false) String note,
                             @AuthenticationPrincipal LoginUser loginUser,
                             RedirectAttributes ra) {
        try {
            minibarService.create(roomId, itemName, quantity, unitPrice, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已挂账，退房时自动并入账单");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/minibar";
    }

    @PostMapping("/minibar/{id}/delete")
    public String deleteMinibar(@PathVariable Long id,
                                @RequestParam(required = false) String reason,
                                @AuthenticationPrincipal LoginUser loginUser,
                                RedirectAttributes ra) {
        try {
            minibarService.delete(id, reason, canUndoOthers(loginUser), operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "挂账记录已取消");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/minibar";
    }

    // ---------- 权限辅助 ----------

    /** 可派工 / 可验收：管理员、前台、客房主管 */
    private boolean canDispatch(LoginUser loginUser) {
        return loginUser != null && (loginUser.isAdmin()
                || loginUser.getRole() == Role.RECEPTION
                || loginUser.getRole() == Role.HOUSEKEEPING_SUPERVISOR);
    }

    /** 可代替他人标记完成：管理员、前台、客房主管 */
    private boolean canOperateOthers(LoginUser loginUser) {
        return canDispatch(loginUser);
    }

    /** 该工单当前用户能否标记完成（本人，或具备代操作权限） */
    private boolean canOperate(LoginUser loginUser, com.hotel.hms.entity.HousekeepingTask task) {
        if (loginUser == null) {
            return false;
        }
        return canOperateOthers(loginUser) || loginUser.getId().equals(task.getAssignee().getId());
    }

    /** 管理员 / 前台可撤销他人录入的挂账；客房部（含主管）只能撤本人录入的 */
    private boolean canUndoOthers(LoginUser loginUser) {
        return loginUser != null
                && (loginUser.isAdmin() || loginUser.getRole() == Role.RECEPTION);
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
