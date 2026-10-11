package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.LostFoundStatus;
import com.hotel.hms.service.LostFoundService;
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

/**
 * 失物招领：拾遗登记 → 客人认领。
 */
@Controller
@RequestMapping("/lost-found")
@RequiredArgsConstructor
public class LostFoundController {

    private final LostFoundService lostFoundService;

    @GetMapping
    public String list(@RequestParam(required = false) LostFoundStatus status,
                       @RequestParam(required = false) String keyword,
                       Model model) {
        model.addAttribute("items", lostFoundService.list(status, keyword));
        model.addAttribute("statuses", LostFoundStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("keyword", keyword);
        model.addAttribute("openCount", lostFoundService.countByStatus(LostFoundStatus.OPEN));
        model.addAttribute("active", "lostfound");
        return "lostfound/list";
    }

    @PostMapping
    public String register(@RequestParam String itemName,
                           @RequestParam(required = false) String description,
                           @RequestParam(required = false) String location,
                           @RequestParam(required = false) String finder,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            lostFoundService.register(itemName, description, location,
                    (finder == null || finder.isBlank()) ? operatorOf(loginUser) : finder,
                    operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "失物已登记");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/lost-found";
    }

    @PostMapping("/{id}/claim")
    public String claim(@PathVariable Long id,
                        @RequestParam String claimant,
                        @RequestParam(required = false) String claimContact,
                        @AuthenticationPrincipal LoginUser loginUser,
                        RedirectAttributes ra) {
        try {
            lostFoundService.claim(id, claimant, claimContact, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "认领登记完成");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/lost-found";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
