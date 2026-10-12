package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.OutsourceStatus;
import com.hotel.hms.service.LinenService;
import com.hotel.hms.service.OutsourceService;
import lombok.RequiredArgsConstructor;
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

import java.math.BigDecimal;
import java.util.List;

/**
 * 布草外送洗涤结算（第七批）。
 *
 * <p>把「送洗 → 收回 → 结算」三个节点固化成一张可打印的交接单，
 * 顺带把布草库存的「在库 / 洗涤中」三态与外包环节打通——
 * 过去领用只动了库存，没人记得这批是送哪家洗的、几件、多少钱。
 *
 * <p><b>整个控制器只对管理员、前台与客房主管开放</b>（第八批缺口 4）：
 * 交接单与结算金额是供应商成本数据，客房服务员不进——
 * 查看与操作同权限，URL 层与这里的类级注解双保险。
 */
@Controller
@RequestMapping("/laundry/outsourcing")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RECEPTION','HOUSEKEEPING_SUPERVISOR')")
public class OutsourceController {

    private final OutsourceService outsourceService;
    private final LinenService linenService;

    @GetMapping
    public String page(@RequestParam(required = false) OutsourceStatus status, Model model) {
        model.addAttribute("batches", outsourceService.list(status));
        model.addAttribute("statuses", OutsourceStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("linen", linenService.list());
        model.addAttribute("sentCount", outsourceService.countByStatus(OutsourceStatus.SENT));
        model.addAttribute("returnedCount", outsourceService.countByStatus(OutsourceStatus.RETURNED));
        model.addAttribute("settledCount", outsourceService.countByStatus(OutsourceStatus.SETTLED));
        model.addAttribute("active", "outsourcing");
        return "laundry/outsourcing";
    }

    /** 交接单（可打印，交给洗涤公司签字） */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("batch", outsourceService.get(id));
        model.addAttribute("active", "outsourcing");
        return "laundry/outsourcing-detail";
    }

    @PostMapping
    public String create(@RequestParam(required = false) String vendor,
                         @RequestParam(required = false) String remark,
                         @RequestParam(required = false) List<Long> linenId,
                         @RequestParam(required = false) List<Integer> quantity,
                         @RequestParam(required = false) List<BigDecimal> price,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            Long id = outsourceService.create(vendor, linenId, quantity, price, remark, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "外送批次已创建，布草已转「洗涤中」");
            return "redirect:/laundry/outsourcing/" + id;
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/laundry/outsourcing";
        }
    }

    @PostMapping("/{id}/return")
    public String markReturned(@PathVariable Long id,
                               @AuthenticationPrincipal LoginUser loginUser,
                               RedirectAttributes ra) {
        try {
            outsourceService.markReturned(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "布草已收回，库存回补在库");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/laundry/outsourcing/" + id;
    }

    @PostMapping("/{id}/settle")
    public String settle(@PathVariable Long id,
                         @RequestParam(required = false) BigDecimal amount,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            outsourceService.settle(id, amount, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "批次已结算");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/laundry/outsourcing/" + id;
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            outsourceService.cancel(id, reason, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已取消送出，数量退回在库");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/laundry/outsourcing/" + id;
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
