package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.ChecklistCategory;
import com.hotel.hms.entity.TaskType;
import com.hotel.hms.service.ChecklistService;
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

/**
 * 清洁标准检查单模板维护（第七批）。
 *
 * <p>模板回答「一间房该按什么标准做」，是服务员作业与主管验收的<b>共同语言</b>。
 * 维护权保留给管理员 / 前台 / 客房主管——一线服务员只执行不制定标准。
 */
@Controller
@RequestMapping("/checklists")
@RequiredArgsConstructor
public class ChecklistController {

    private final ChecklistService checklistService;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("grouped", checklistService.groupedItems());
        model.addAttribute("items", checklistService.listItems());
        model.addAttribute("categories", ChecklistCategory.values());
        model.addAttribute("taskTypes", TaskType.values());
        model.addAttribute("enabledCount", checklistService.countEnabled());
        model.addAttribute("active", "checklists");
        return "checklist/list";
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String create(@RequestParam String itemName,
                         @RequestParam(required = false) ChecklistCategory category,
                         @RequestParam(required = false) TaskType taskType,
                         @RequestParam(required = false) Integer sortOrder,
                         @RequestParam(required = false) String note,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            checklistService.createItem(itemName, category, taskType, sortOrder, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "检查项已新增，此后新派工单按新标准生成清单");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/checklists";
    }

    @PostMapping("/{id}/update")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) ChecklistCategory category,
                         @RequestParam(required = false) TaskType taskType,
                         @RequestParam(required = false) Integer sortOrder,
                         @RequestParam(required = false) Boolean enabled,
                         @RequestParam(required = false) String note,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            checklistService.updateItem(id, category, taskType, sortOrder, enabled, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "检查项已更新（不影响已生成的清单）");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/checklists";
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String toggle(@PathVariable Long id,
                         @RequestParam(required = false) Boolean enabled,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            checklistService.updateItem(id, null, null, null, enabled, null, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", Boolean.TRUE.equals(enabled) ? "检查项已启用" : "检查项已停用");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/checklists";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            checklistService.deleteItem(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "检查项已删除（历史派工单的清单不受影响）");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/checklists";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
