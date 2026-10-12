package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.RatePlan;
import com.hotel.hms.entity.RatePlanType;
import com.hotel.hms.service.RatePlanService;
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

import java.util.List;

/**
 * 房价方案维护（第六批：协议价 / 会员价）。
 *
 * <p>把「打几折」从口头约定变成可复用的方案：方案有编码、有类型，
 * 下单时选中方案即按方案计价，价格可预期、可追溯、可统计。
 */
@Controller
@RequestMapping("/rate-plans")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
public class RatePlanController {

    /** 会员等级选项（与客户建档保持一致） */
    private static final List<String> MEMBER_LEVELS = List.of("普通", "银卡", "金卡", "钻石");

    private final RatePlanService ratePlanService;

    @GetMapping
    public String list(Model model) {
        List<RatePlan> plans = ratePlanService.list();
        model.addAttribute("plans", plans);
        model.addAttribute("enabledCount", plans.stream()
                .filter(p -> Boolean.TRUE.equals(p.getEnabled())).count());
        model.addAttribute("types", RatePlanType.values());
        model.addAttribute("active", "rate-plans");
        return "rateplan/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        RatePlan plan = new RatePlan();
        plan.setType(RatePlanType.CORPORATE);
        plan.setEnabled(true);
        model.addAttribute("plan", plan);
        model.addAttribute("editing", false);
        prepareOptions(model);
        return "rateplan/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("plan", ratePlanService.getById(id));
        model.addAttribute("editing", true);
        prepareOptions(model);
        return "rateplan/form";
    }

    @PostMapping("/save")
    public String save(RatePlan form,
                       @RequestParam(required = false) Long id,
                       @AuthenticationPrincipal LoginUser loginUser,
                       RedirectAttributes ra) {
        try {
            RatePlan saved = ratePlanService.save(id, form, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage",
                    "房价方案「" + saved.getName() + "」已保存（" + saved.getType().getLabel()
                            + " / " + saved.getDiscountLabel() + "）");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return id == null ? "redirect:/rate-plans/new" : "redirect:/rate-plans/" + id + "/edit";
        }
        return "redirect:/rate-plans";
    }

    /** 启用 / 停用：停用后不能用于新订单，历史订单的价目快照不受影响 */
    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         @RequestParam boolean enabled,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            RatePlan saved = ratePlanService.toggle(id, enabled, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage",
                    "方案「" + saved.getName() + "」已" + (enabled ? "启用" : "停用"));
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/rate-plans";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            ratePlanService.delete(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "房价方案已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/rate-plans";
    }

    private void prepareOptions(Model model) {
        model.addAttribute("types", RatePlanType.values());
        model.addAttribute("memberLevels", MEMBER_LEVELS);
        model.addAttribute("active", "rate-plans");
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
