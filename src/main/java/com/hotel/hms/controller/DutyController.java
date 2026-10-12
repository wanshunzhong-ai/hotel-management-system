package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.DayShift;
import com.hotel.hms.service.DutyService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;

/**
 * 客房部排班与工作量（第七批）。
 *
 * <p>页面一次给足两块信息：上半是选定日期的三班次排班，
 * 下半是选定区间的按人工作量——「今天谁值班」与「这周谁干得多」在同一个页面回答。
 *
 * <p><b>整个控制器只对管理员、前台与客房主管开放</b>（第八批缺口 1）：
 * 排班表与按人工作量统计（派工数 / 完成率 / 平均耗时）是管理数据，
 * 客房服务员不该看到同事的考核口径——URL 层与这里的类级注解双保险。
 */
@Controller
@RequestMapping("/housekeeping/schedule")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
public class DutyController {

    private final DutyService dutyService;

    @GetMapping
    public String page(@RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       Model model) {
        LocalDate day = date == null ? LocalDate.now() : date;
        LocalDate start = from == null ? day : from;
        LocalDate end = to == null ? start : to;

        model.addAttribute("date", day);
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("rotation", dutyService.rotationOf(day));
        model.addAttribute("shifts", DayShift.values());
        model.addAttribute("staff", dutyService.listStaff());
        java.util.List<com.hotel.hms.dto.WorkloadRow> workload = dutyService.workload(start, end);
        model.addAttribute("workload", workload);
        model.addAttribute("totalAssigned", workload.stream()
                .mapToInt(com.hotel.hms.dto.WorkloadRow::assigned).sum());
        model.addAttribute("active", "schedule");
        return "housekeeping/schedule";
    }

    @PostMapping
    public String schedule(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
                           @RequestParam(required = false) DayShift shift,
                           @RequestParam Long staffId,
                           @RequestParam(required = false) String remark,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            dutyService.schedule(workDate, shift, staffId, remark, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "排班已登记");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/schedule?date=" + workDate;
    }

    @PostMapping("/{id}/delete")
    public String unschedule(@PathVariable Long id,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @AuthenticationPrincipal LoginUser loginUser,
                             RedirectAttributes ra) {
        try {
            dutyService.unschedule(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已取消该排班");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/housekeeping/schedule" + (date == null ? "" : "?date=" + date);
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
