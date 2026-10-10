package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.dto.CheckoutForm;
import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillItem;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.service.BillService;
import lombok.RequiredArgsConstructor;
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

/**
 * 账单与结算。
 */
@Controller
@RequestMapping("/bills")
@RequiredArgsConstructor
public class BillController {

    private final BillService billService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) BillStatus status,
                       Model model) {
        model.addAttribute("bills", billService.search(keyword, status));
        model.addAttribute("statuses", BillStatus.values());
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("active", "bills");
        return "bill/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Bill bill = billService.getDetail(id);
        model.addAttribute("bill", bill);
        model.addAttribute("payMethods", PayMethod.values());
        model.addAttribute("suggestedPaid",
                bill.getTotalAmount().subtract(bill.getDeposit()).max(BigDecimal.ZERO));
        model.addAttribute("active", "bills");
        return "bill/detail";
    }

    @PostMapping("/{id}/items")
    public String addItem(@PathVariable Long id,
                          @RequestParam String itemName,
                          @RequestParam(required = false) BigDecimal unitPrice,
                          @RequestParam(required = false) Integer quantity,
                          @RequestParam(required = false) String remark,
                          RedirectAttributes ra) {
        try {
            BillItem item = new BillItem();
            item.setItemName(itemName);
            item.setUnitPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice);
            item.setQuantity(quantity == null ? 1 : quantity);
            item.setRemark(remark);
            billService.addItem(id, item);
            ra.addFlashAttribute("successMessage", "消费项目已添加");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bills/" + id;
    }

    @PostMapping("/{id}/items/{itemId}/delete")
    public String removeItem(@PathVariable Long id,
                             @PathVariable Long itemId,
                             RedirectAttributes ra) {
        try {
            billService.removeItem(id, itemId);
            ra.addFlashAttribute("successMessage", "消费项目已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bills/" + id;
    }

    @PostMapping("/{id}/settle")
    public String settle(@PathVariable Long id,
                         CheckoutForm form,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            Bill bill = billService.settle(id, form, loginUser == null ? "系统" : loginUser.getRealName());
            ra.addFlashAttribute("successMessage",
                    "账单 " + bill.getBillNo() + " 结账完成，实收 ￥" + bill.getPaidAmount());
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bills/" + id;
    }
}
