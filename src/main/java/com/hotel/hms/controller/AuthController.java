package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 登录、个人中心与错误页。
 */
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                        @RequestParam(value = "logout", required = false) String logout,
                        @RequestParam(value = "expired", required = false) String expired,
                        Model model) {
        model.addAttribute("loginError", error != null);
        model.addAttribute("logout", logout != null);
        model.addAttribute("expired", expired != null);
        return "login";
    }

    @GetMapping("/error/403")
    public String forbidden() {
        return "error/403";
    }

    @GetMapping("/profile")
    public String profile(@AuthenticationPrincipal LoginUser loginUser, Model model) {
        model.addAttribute("user", loginUser.getUser());
        model.addAttribute("active", "profile");
        return "user/profile";
    }

    @PostMapping("/profile/password")
    public String changePassword(@AuthenticationPrincipal LoginUser loginUser,
                                 @RequestParam String oldPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 RedirectAttributes ra) {
        try {
            if (!newPassword.equals(confirmPassword)) {
                throw new BusinessException("两次输入的新密码不一致");
            }
            userService.changePassword(loginUser.getId(), oldPassword, newPassword);
            ra.addFlashAttribute("successMessage", "密码已更新，下次登录请使用新密码");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/profile";
    }
}
