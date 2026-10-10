package com.hotel.hms.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 全局异常处理：把异常转成用户可读的错误提示。
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：回到来源页面并提示原因 */
    @ExceptionHandler(BusinessException.class)
    public String handleBusiness(BusinessException ex,
                                 HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {
        log.warn("业务异常: {}", ex.getMessage());
        redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        return "redirect:" + backTo(request);
    }

    /**
     * 权限不足：直接渲染 403 页并返回 <b>真实的 403 状态码</b>。
     *
     * <p>早前这里是 {@code redirect:/error/403}，结果是「方法级 {@code @PreAuthorize} 拒绝」
     * 返回 302→200，而「URL 级 hasRole 拒绝」返回 403，同一个语义两种表现；
     * 前端与自动化测试都无法可靠判断。统一成 403，和
     * {@code SecurityConfig} 里的 {@code accessDeniedPage("/error/403")} 保持一致。
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleAccessDenied(AccessDeniedException ex) {
        log.warn("权限不足: {}", ex.getMessage());
        return "error/403";
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public String handleDataIntegrity(DataIntegrityViolationException ex,
                                      HttpServletRequest request,
                                      RedirectAttributes redirectAttributes) {
        log.warn("数据完整性冲突: {}", ex.getMostSpecificCause().getMessage());
        redirectAttributes.addFlashAttribute("errorMessage", "数据存在关联或重复，操作被拒绝。");
        return "redirect:" + backTo(request);
    }

    @ExceptionHandler(Exception.class)
    public String handleOthers(Exception ex, Model model) {
        log.error("系统异常", ex);
        model.addAttribute("message", "系统开小差了：" + ex.getMessage());
        return "error";
    }

    /** 简单回退到来源页，取不到就回首页 */
    private String backTo(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            int idx = referer.indexOf("://");
            if (idx > 0) {
                int pathStart = referer.indexOf('/', idx + 3);
                if (pathStart > 0) {
                    return referer.substring(pathStart);
                }
            }
        }
        return "/dashboard";
    }
}
