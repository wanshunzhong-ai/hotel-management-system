package com.hotel.hms.controller;

import com.hotel.hms.common.Pagination;
import com.hotel.hms.entity.OperationLog;
import com.hotel.hms.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作日志查询（第三批：审计留痕）。
 *
 * <p>仅管理员可见，访问权限在 {@code SecurityConfig} 里收敛。
 */
@Controller
@RequestMapping("/logs")
@RequiredArgsConstructor
public class OperationLogController {

    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final OperationLogService operationLogService;

    @GetMapping
    public String list(@RequestParam(required = false) String action,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = Pagination.pageSize(size);
        Page<OperationLog> result = Pagination.fetch(
                p -> operationLogService.search(action, keyword, from, to, p),
                Pagination.pageIndex(page), pageSize, LATEST_FIRST);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("action", action);
        filters.put("keyword", keyword);
        filters.put("from", from);
        filters.put("to", to);
        Pagination.Bases bases = Pagination.bases("/logs", filters, pageSize);

        model.addAttribute("page", result);
        model.addAttribute("pageNumbers", Pagination.window(result));
        model.addAttribute("sizes", Pagination.SIZE_OPTIONS);
        model.addAttribute("pageBase", bases.page());
        model.addAttribute("sizeBase", bases.size());
        model.addAttribute("actions", operationLogService.distinctActions());
        model.addAttribute("action", action);
        model.addAttribute("keyword", keyword);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("active", "logs");
        return "log/list";
    }
}
