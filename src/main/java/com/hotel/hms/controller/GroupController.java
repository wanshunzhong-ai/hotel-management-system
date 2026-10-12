package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

/**
 * 团队入住（第六批）。
 *
 * <h3>要解决的问题</h3>
 * 会议团 / 旅行团到店时，十几间房要一间间点「办理入住」，前台手忙脚乱还容易漏；
 * 团里谁住哪间、团费结没结，事后也理不清。这里提供两件事：
 * <ol>
 *     <li><b>批量入住</b>：把同一个团的订单一次性办理入住，逐间成功/失败都能看到；</li>
 *     <li><b>团名归档</b>：给订单打统一团名，随时按团查看成员与房号。</li>
 * </ol>
 * 逐间办理而不是「全成功或全失败」：房间状态可能有异常（维修、已占用），
 * 不应该因为一间房有问题就让整团都住不进去。
 */
@Controller
@RequestMapping("/groups")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
public class GroupController {

    private final ReservationService reservationService;

    /**
     * 团队入住工作台。
     *
     * @param date  预抵日期（默认今天），列出该日尚未办理入住的订单，便于勾选建团 / 批量入住
     * @param group 已建团的团名，选中后列出该团全部成员
     */
    @GetMapping("/checkin")
    public String checkin(@RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam(required = false) String group,
                          Model model) {
        LocalDate day = date == null ? LocalDate.now() : date;
        model.addAttribute("pendingArrivals", reservationService.findPendingArrivalsOn(day));
        model.addAttribute("groups", reservationService.listGroupNames());
        model.addAttribute("members", group == null || group.isBlank()
                ? List.of() : reservationService.findByGroupName(group));
        model.addAttribute("date", day);
        model.addAttribute("group", group);
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("active", "groups");
        return "group/checkin";
    }

    /** 给勾选的订单统一打团名（传空则移出团队） */
    @PostMapping("/assign")
    public String assign(@RequestParam(required = false) List<Long> ids,
                         @RequestParam(required = false) String groupName,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "请先勾选需要归团的订单");
            return redirect(date, groupName);
        }
        String operator = operatorOf(loginUser);
        int ok = 0;
        int fail = 0;
        for (Long id : ids) {
            try {
                reservationService.assignGroup(id, groupName, operator);
                ok++;
            } catch (BusinessException ex) {
                fail++;
            }
        }
        if (ok > 0) {
            ra.addFlashAttribute("successMessage",
                    groupName == null || groupName.isBlank()
                            ? "已移出团队的订单 " + ok + " 单" + (fail > 0 ? "，失败 " + fail + " 单" : "")
                            : "已归入团队「" + groupName + "」" + ok + " 单"
                            + (fail > 0 ? "，失败 " + fail + " 单" : ""));
        }
        if (fail > 0 && ok == 0) {
            ra.addFlashAttribute("errorMessage", "全部 " + fail + " 单操作失败，请检查订单状态");
        }
        return redirect(date, groupName);
    }

    /**
     * 批量办理入住。
     *
     * <p>逐间办理，任何一间失败都不影响其余房间——团队到店时最怕「一间房有问题整团卡住」。
     * 结果按成功 / 失败计数反馈，失败的订单仍留在待办列表里，可单独处理。
     */
    @PostMapping("/checkin/batch")
    public String batchCheckIn(@RequestParam(required = false) List<Long> ids,
                               @RequestParam(required = false) String groupName,
                               @RequestParam(required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                               @AuthenticationPrincipal LoginUser loginUser,
                               RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "请先勾选需要办理入住的订单");
            return redirect(date, groupName);
        }
        String operator = operatorOf(loginUser);
        int ok = 0;
        int fail = 0;
        StringBuilder failures = new StringBuilder();
        for (Long id : ids) {
            try {
                reservationService.checkIn(id, operator);
                ok++;
            } catch (BusinessException ex) {
                fail++;
                if (failures.length() < 160) {
                    failures.append(failures.length() == 0 ? "" : "；").append(ex.getMessage());
                }
            }
        }
        if (ok > 0) {
            ra.addFlashAttribute("successMessage",
                    "批量入住完成：成功 " + ok + " 单" + (fail > 0 ? "，失败 " + fail + " 单（见错误提示）" : ""));
        }
        if (fail > 0) {
            ra.addFlashAttribute("errorMessage",
                    "有 " + fail + " 间未能入住：" + failures);
        }
        return redirect(date, groupName);
    }

    private String redirect(LocalDate date, String groupName) {
        StringBuilder url = new StringBuilder("redirect:/groups/checkin");
        boolean hasQuery = false;
        if (date != null) {
            url.append("?date=").append(date);
            hasQuery = true;
        }
        if (groupName != null && !groupName.isBlank()) {
            url.append(hasQuery ? '&' : '?').append("group=")
                    .append(java.net.URLEncoder.encode(groupName, java.nio.charset.StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
