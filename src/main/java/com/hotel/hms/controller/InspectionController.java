package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.InspectionResult;
import com.hotel.hms.entity.RoomInspection;
import com.hotel.hms.service.HousekeepingService;
import com.hotel.hms.service.InspectionService;
import com.hotel.hms.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 查房评分：四项打分 → 合格线判定 → 不合格自动派整改单。
 */
@Controller
@RequestMapping("/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;
    private final HousekeepingService housekeepingService;
    private final RoomService roomService;

    @GetMapping
    public String page(@RequestParam(required = false) InspectionResult result, Model model) {
        model.addAttribute("inspections", inspectionService.list(result));
        model.addAttribute("results", InspectionResult.values());
        model.addAttribute("result", result);
        model.addAttribute("rooms", roomService.findAll());
        model.addAttribute("staff", housekeepingService.listStaff());
        model.addAttribute("passCount", inspectionService.countByResult(InspectionResult.PASS));
        model.addAttribute("failCount", inspectionService.countByResult(InspectionResult.FAIL));
        model.addAttribute("averageScore", inspectionService.averageScore());
        model.addAttribute("passScore", InspectionResult.PASS_SCORE);
        model.addAttribute("active", "inspections");
        return "inspection/list";
    }

    @PostMapping
    public String create(@RequestParam Long roomId,
                         @RequestParam(required = false) Integer scoreHygiene,
                         @RequestParam(required = false) Integer scoreFacility,
                         @RequestParam(required = false) Integer scoreLinen,
                         @RequestParam(required = false) Integer scoreAmenity,
                         @RequestParam(required = false) String remark,
                         @RequestParam(required = false) Long assigneeId,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            RoomInspection saved = inspectionService.create(roomId, scoreHygiene, scoreFacility,
                    scoreLinen, scoreAmenity, remark, assigneeId, operatorOf(loginUser));
            if (saved.getResult() == InspectionResult.FAIL) {
                if (saved.getRectifiedTaskId() != null) {
                    ra.addFlashAttribute("errorMessage", "查房不合格（总分 " + saved.getTotalScore()
                            + "），已自动生成整改派工单 #" + saved.getRectifiedTaskId());
                } else {
                    ra.addFlashAttribute("errorMessage", "查房不合格（总分 " + saved.getTotalScore()
                            + "），但没有可派工的客房服务员，请手工派工");
                }
            } else {
                ra.addFlashAttribute("successMessage",
                        "查房完成：总分 " + saved.getTotalScore() + "（" + saved.getGradeLabel() + "）");
            }
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/inspections";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
