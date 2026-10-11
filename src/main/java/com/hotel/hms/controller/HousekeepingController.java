package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.MinibarStatus;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.TaskStatus;
import com.hotel.hms.entity.TaskType;
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
 */
@Controller
@RequestMapping("/housekeeping")
@RequiredArgsConstructor
public class HousekeepingController {

    private final HousekeepingService housekeepingService;
    private final MinibarService minibarService;
    private final RoomService roomService;
    private final ReservationService reservationService;

    // ---------- 派工 / 验收 ----------

    @GetMapping("/tasks")
    public String tasks(@RequestParam(required = false) TaskStatus status,
                        @RequestParam(required = false) Long assigneeId,
                        Model model) {
        model.addAttribute("tasks", housekeepingService.list(status, assigneeId));
        model.addAttribute("staff", housekeepingService.listStaff());
        model.addAttribute("rooms", roomService.findAll());
        model.addAttribute("taskStatuses", TaskStatus.values());
        model.addAttribute("taskTypes", TaskType.values());
        model.addAttribute("status", status);
        model.addAttribute("assigneeId", assigneeId);
        model.addAttribute("pendingCount", housekeepingService.countByStatus(TaskStatus.PENDING));
        model.addAttribute("doneCount", housekeepingService.countByStatus(TaskStatus.DONE));
        model.addAttribute("active", "housekeeping");
        return "housekeeping/tasks";
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
    public String create(@RequestParam Long roomId,
                         @RequestParam(required = false) TaskType type,
                         @RequestParam Long assigneeId,
                         @RequestParam(required = false) String remark,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            housekeepingService.create(roomId, type, assigneeId, remark, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "派工单已创建");
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
            housekeepingService.markDone(id, loginUser.getId(), loginUser.isAdmin(), operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已标记完成，等待验收");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/tasks";
    }

    /** 验收：action=approve 通过（房间自动转空闲）/ reject 打回 */
    @PostMapping("/tasks/{id}/inspect")
    @PreAuthorize("hasRole('ADMIN')")
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
        return "redirect:/housekeeping/tasks";
    }

    // ---------- 迷你吧 / 客用品挂账 ----------

    @GetMapping("/minibar")
    public String minibar(@RequestParam(required = false) MinibarStatus status, Model model) {
        List<Reservation> inHouse = reservationService.findInHouse();
        model.addAttribute("items", minibarService.list(status));
        model.addAttribute("inHouse", inHouse);
        model.addAttribute("statuses", MinibarStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("pendingCount", minibarService.countByStatus(MinibarStatus.PENDING));
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
                                @AuthenticationPrincipal LoginUser loginUser,
                                RedirectAttributes ra) {
        try {
            minibarService.delete(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "挂账记录已取消");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/minibar";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
