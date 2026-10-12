package com.hotel.hms.config;

import com.hotel.hms.service.TodoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 全局模型增强：给每个页面注入侧栏「待办」角标数字。
 *
 * <p>角标要在<b>所有</b>页面都能显示，逐个 Controller 加属性既啰嗦又容易漏，
 * 所以在 {@code @ControllerAdvice} 里统一注入。
 *
 * <p>用 {@link SecurityContextHolder} 而不是 {@code @AuthenticationPrincipal} 取登录态：
 * {@code @ModelAttribute} 方法的参数解析走的是模型属性处理器，不保证支持
 * {@code @AuthenticationPrincipal}，直接读上下文更稳。未登录（如登录页、住客扫码页）时返回 0。
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final TodoService todoService;

    @ModelAttribute("todoCount")
    public long todoCount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof LoginUser user)) {
            return 0L;
        }
        try {
            return todoService.countFor(user);
        } catch (RuntimeException ex) {
            // 角标属于「锦上添花」，绝不能因为它让整个页面 500
            return 0L;
        }
    }
}
