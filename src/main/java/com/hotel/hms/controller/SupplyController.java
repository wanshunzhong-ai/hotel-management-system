package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.SupplyCategory;
import com.hotel.hms.entity.SupplyMovementType;
import com.hotel.hms.service.SupplyService;
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
 * 客房耗材出入库台账（第七批）。
 *
 * <p>权限分工：台账建档 / 删档 / 改参数属「定标准」，限管理员 / 前台 / 客房主管；
 * 日常的入库、领用、报废是客房部现场动作，客房服务员也能做——
 * 让领用必须回主管处登记，等于逼着一线用纸笔记。
 */
@Controller
@RequestMapping("/supplies")
@RequiredArgsConstructor
public class SupplyController {

    private final SupplyService supplyService;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("items", supplyService.list());
        model.addAttribute("recent", supplyService.recentMovements());
        model.addAttribute("categories", SupplyCategory.values());
        model.addAttribute("movementTypes", SupplyMovementType.values());
        model.addAttribute("belowMin", supplyService.countBelowMin());
        model.addAttribute("active", "supplies");
        return "supply/list";
    }

    /** 单个耗材的流水明细 */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        var item = supplyService.get(id);
        model.addAttribute("item", item);
        model.addAttribute("movements", supplyService.movementsOf(id));
        model.addAttribute("movementTypes", SupplyMovementType.values());
        model.addAttribute("active", "supplies");
        return "supply/detail";
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String create(@RequestParam String itemName,
                         @RequestParam(required = false) SupplyCategory category,
                         @RequestParam(required = false) String unit,
                         @RequestParam(required = false) Integer stock,
                         @RequestParam(required = false) Integer minStock,
                         @RequestParam(required = false) BigDecimal unitCost,
                         @RequestParam(required = false) String note,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            supplyService.createItem(itemName, category, unit, stock, minStock, unitCost, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "耗材已建档并入库");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/supplies";
    }

    @PostMapping("/{id}/update")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) Integer minStock,
                         @RequestParam(required = false) BigDecimal unitCost,
                         @RequestParam(required = false) Boolean enabled,
                         @RequestParam(required = false) String unit,
                         @RequestParam(required = false) String note,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            supplyService.updateItem(id, minStock, unitCost, enabled, unit, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "耗材参数已更新");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/supplies";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            supplyService.deleteItem(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "耗材已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/supplies";
    }

    /** 出入库：入库 / 领用 / 报废 / 盘点调整 */
    @PostMapping("/{id}/move")
    public String move(@PathVariable Long id,
                       @RequestParam SupplyMovementType type,
                       @RequestParam Integer quantity,
                       @RequestParam(required = false) BigDecimal unitCost,
                       @RequestParam(required = false) String target,
                       @RequestParam(required = false) String remark,
                       @AuthenticationPrincipal LoginUser loginUser,
                       RedirectAttributes ra) {
        try {
            supplyService.move(id, type, quantity, unitCost, target, remark, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已登记" + type.getLabel());
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/supplies";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
