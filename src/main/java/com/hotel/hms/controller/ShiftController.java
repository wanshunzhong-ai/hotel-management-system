package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.service.ShiftService;
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
 * 交接班对账（第六批）。
 */
@Controller
@RequestMapping("/shift")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
public class ShiftController {

    private final ShiftService shiftService;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("summary", shiftService.preview());
        model.addAttribute("history", shiftService.history());
        model.addAttribute("active", "shift");
        return "shift/index";
    }

    /** 确认交班：把本班账目固化成快照 */
    @PostMapping("/handover")
    public String handover(@RequestParam(required = false) String receivedBy,
                           @RequestParam(required = false) String remark,
                           @RequestParam(required = false, defaultValue = "false") boolean cashVerified,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            var saved = shiftService.handover(receivedBy, cashVerified, remark, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage",
                    "交班完成：" + saved.getShiftNo() + "，净收款 ￥" + saved.getTotalReceived()
                            + "（现金 ￥" + saved.getCashAmount() + "），接班人 " + saved.getReceivedBy());
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/shift";
    }

    /** 打印用视图：交班单 */
    @GetMapping("/{id}/print")
    public String print(@PathVariable Long id, Model model) {
        model.addAttribute("record", shiftService.getById(id));
        model.addAttribute("active", "shift");
        return "shift/print";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
