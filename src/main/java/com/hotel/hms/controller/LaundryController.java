package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.LaundryCategory;
import com.hotel.hms.entity.LaundryStatus;
import com.hotel.hms.entity.LinenCategory;
import com.hotel.hms.service.LaundryService;
import com.hotel.hms.service.LinenService;
import com.hotel.hms.service.ReservationService;
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

/**
 * 洗衣与布草：客衣 / 布草收件 → 洗涤 → 送回；布草库存在库 / 洗涤中 / 报废三态管理。
 */
@Controller
@RequestMapping("/laundry")
@RequiredArgsConstructor
public class LaundryController {

    private final LaundryService laundryService;
    private final LinenService linenService;
    private final ReservationService reservationService;

    @GetMapping
    public String page(@RequestParam(required = false) LaundryStatus status, Model model) {
        model.addAttribute("orders", laundryService.list(status));
        model.addAttribute("statuses", LaundryStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("categories", LaundryCategory.values());
        model.addAttribute("linenCategories", LinenCategory.values());
        model.addAttribute("linen", linenService.list());
        model.addAttribute("linenBelowMin", linenService.countBelowMin());
        model.addAttribute("inHouse", reservationService.findInHouse());
        model.addAttribute("receivedCount", laundryService.countByStatus(LaundryStatus.RECEIVED));
        model.addAttribute("washingCount", laundryService.countByStatus(LaundryStatus.WASHING));
        model.addAttribute("active", "laundry");
        return "laundry/list";
    }

    // ---------------- 洗衣单 ----------------

    @PostMapping
    public String create(@RequestParam(required = false) LaundryCategory category,
                         @RequestParam(required = false) Long roomId,
                         @RequestParam String itemName,
                         @RequestParam(required = false) Integer quantity,
                         @RequestParam(required = false) BigDecimal unitPrice,
                         @RequestParam(required = false) String note,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            laundryService.create(category, roomId, itemName, quantity, unitPrice, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "洗衣单已收件");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/laundry";
    }

    @PostMapping("/{id}/wash")
    public String wash(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser,
                       RedirectAttributes ra) {
        return act(() -> laundryService.startWashing(id, operatorOf(loginUser)), "已转入洗涤", ra);
    }

    @PostMapping("/{id}/return")
    public String returned(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        return act(() -> laundryService.markReturned(id, operatorOf(loginUser)), "洗衣单已送回", ra);
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        return act(() -> laundryService.cancel(id, operatorOf(loginUser)), "洗衣单已取消", ra);
    }

    // ---------------- 布草库存 ----------------

    @PostMapping("/linen")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
    public String addLinen(@RequestParam String itemName,
                           @RequestParam(required = false) LinenCategory category,
                           @RequestParam(required = false) Integer inStock,
                           @RequestParam(required = false) Integer minStock,
                           @RequestParam(required = false) String note,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            linenService.create(itemName, category, inStock, minStock, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "布草品名已入库");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/laundry";
    }

    @PostMapping("/linen/{id}/issue")
    public String issue(@PathVariable Long id, @RequestParam Integer quantity,
                        @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes ra) {
        return act(() -> linenService.issue(id, quantity, operatorOf(loginUser)), "已领用出库", ra);
    }

    @PostMapping("/linen/{id}/back")
    public String back(@PathVariable Long id, @RequestParam Integer quantity,
                       @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes ra) {
        return act(() -> linenService.back(id, quantity, operatorOf(loginUser)), "已洗涤回库", ra);
    }

    @PostMapping("/linen/{id}/scrap")
    public String scrap(@PathVariable Long id, @RequestParam Integer quantity,
                        @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes ra) {
        return act(() -> linenService.scrap(id, quantity, operatorOf(loginUser)), "已登记报废", ra);
    }

    @PostMapping("/linen/{id}/update")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
    public String updateLinen(@PathVariable Long id,
                              @RequestParam(required = false) Integer minStock,
                              @RequestParam(required = false) String note,
                              @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes ra) {
        return act(() -> linenService.update(id, minStock, note, operatorOf(loginUser)), "布草参数已更新", ra);
    }

    // ---------------- 内部 ----------------

    private String act(Runnable action, String success, RedirectAttributes ra) {
        try {
            action.run();
            ra.addFlashAttribute("successMessage", success);
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/laundry";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
