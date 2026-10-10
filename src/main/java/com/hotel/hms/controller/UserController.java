package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.User;
import com.hotel.hms.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 员工账号管理（仅管理员）。
 */
@Controller
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword, Model model) {
        model.addAttribute("users", userService.search(keyword));
        model.addAttribute("keyword", keyword);
        model.addAttribute("active", "users");
        return "user/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("user", new User());
        model.addAttribute("roles", Role.values());
        model.addAttribute("active", "users");
        return "user/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("user", userService.getById(id));
        model.addAttribute("roles", Role.values());
        model.addAttribute("active", "users");
        return "user/form";
    }

    @PostMapping("/save")
    public String save(@Valid User user,
                       BindingResult binding,
                       @RequestParam(required = false) String rawPassword,
                       Model model,
                       RedirectAttributes ra) {
        if (binding.hasErrors()) {
            model.addAttribute("roles", Role.values());
            model.addAttribute("active", "users");
            return "user/form";
        }
        try {
            if (user.getId() == null) {
                userService.create(user, rawPassword);
                ra.addFlashAttribute("successMessage", "账号已创建");
            } else {
                userService.update(user.getId(), user, rawPassword);
                ra.addFlashAttribute("successMessage", "账号已更新");
            }
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return user.getId() == null ? "redirect:/users/new" : "redirect:/users/" + user.getId() + "/edit";
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id,
                                @RequestParam String rawPassword,
                                RedirectAttributes ra) {
        try {
            userService.resetPassword(id, rawPassword);
            ra.addFlashAttribute("successMessage", "密码已重置");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            userService.delete(id, loginUser.getId());
            ra.addFlashAttribute("successMessage", "账号已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/users";
    }
}
