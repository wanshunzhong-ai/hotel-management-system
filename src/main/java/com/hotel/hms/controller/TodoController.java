package com.hotel.hms.controller;

import com.hotel.hms.config.LoginUser;
import com.hotel.hms.dto.TodoItem;
import com.hotel.hms.service.TodoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内待办中心（第七批）。
 *
 * <p>把「要停工单 / 待验收 / 查房不合格整改 / 物资预警 / 维修积压 / 失物滞留」
 * 这些原本要靠人逐个模块刷的事情集中到一页，侧栏还带数字角标。
 * 所有条目都是实时派生的，不做已读未读，因此永远不会出现「消息说有事、实际早做完了」。
 */
@Controller
@RequestMapping("/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    @GetMapping
    public String page(@AuthenticationPrincipal LoginUser loginUser, Model model) {
        List<TodoItem> items = todoService.list(loginUser);
        long total = items.stream().mapToLong(TodoItem::count).sum();
        long urgent = items.stream().filter(TodoItem::isUrgent).mapToLong(TodoItem::count).sum();
        model.addAttribute("items", items);
        model.addAttribute("total", total);
        model.addAttribute("urgent", urgent);
        model.addAttribute("now", LocalDateTime.now());
        model.addAttribute("active", "todos");
        return "todo/list";
    }
}
