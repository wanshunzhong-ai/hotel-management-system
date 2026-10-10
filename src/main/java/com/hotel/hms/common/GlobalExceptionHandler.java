package com.hotel.hms.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex) {
        log.warn("权限不足: {}", ex.getMessage());
        return "redirect:/error/403";
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
