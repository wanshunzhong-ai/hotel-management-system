package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.MaintenanceStatus;
import com.hotel.hms.service.MaintenanceService;
import com.hotel.hms.service.RoomService;
import lombok.RequiredArgsConstructor;
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

/**
 * 维修工单：报修 → 开工 → 完工 / 取消。房态联动见 {@link MaintenanceService}。
 */
@Controller
@RequestMapping("/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService maintenanceService;
    private final RoomService roomService;

    @GetMapping
    public String list(@RequestParam(required = false) MaintenanceStatus status, Model model) {
        model.addAttribute("orders", maintenanceService.list(status));
        model.addAttribute("rooms", roomService.findAll());
        model.addAttribute("statuses", MaintenanceStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("openCount", maintenanceService.countByStatus(MaintenanceStatus.OPEN)
                + maintenanceService.countByStatus(MaintenanceStatus.FIXING));
        model.addAttribute("active", "maintenance");
        return "maintenance/list";
    }

    @PostMapping
    public String report(@RequestParam Long roomId,
                         @RequestParam String description,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            maintenanceService.report(roomId, description, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "报修已登记");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/maintenance";
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id,
                        @RequestParam(required = false) String handler,
                        @AuthenticationPrincipal LoginUser loginUser,
                        RedirectAttributes ra) {
        try {
            maintenanceService.start(id, handler, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已开工");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/maintenance";
    }

    @PostMapping("/{id}/complete")
    public String complete(@PathVariable Long id,
                           @RequestParam(required = false) String result,
                           @RequestParam(required = false) BigDecimal cost,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            maintenanceService.complete(id, result, cost, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "维修完工，房间已转待清洁");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/maintenance";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            maintenanceService.cancel(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "工单已取消");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/maintenance";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
