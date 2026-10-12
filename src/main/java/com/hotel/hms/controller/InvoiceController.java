package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.common.Pagination;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.Invoice;
import com.hotel.hms.entity.InvoiceType;
import com.hotel.hms.service.BillService;
import com.hotel.hms.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发票管理（第六批）。
 */
@Controller
@RequestMapping("/invoices")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final BillService billService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) InvoiceType type,
                       @RequestParam(required = false, defaultValue = "false") boolean includeCancelled,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = Pagination.pageSize(size);
        Page<Invoice> result = Pagination.fetch(
                p -> invoiceService.search(keyword, type, includeCancelled, p),
                Pagination.pageIndex(page), pageSize, Sort.by(Sort.Direction.DESC, "id"));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("keyword", keyword);
        filters.put("type", type);
        filters.put("includeCancelled", includeCancelled);
        Pagination.Bases bases = Pagination.bases("/invoices", filters, pageSize);

        model.addAttribute("page", result);
        model.addAttribute("pageNumbers", Pagination.window(result));
        model.addAttribute("sizes", Pagination.SIZE_OPTIONS);
        model.addAttribute("pageBase", bases.page());
        model.addAttribute("sizeBase", bases.size());
        model.addAttribute("types", InvoiceType.values());
        model.addAttribute("keyword", keyword);
        model.addAttribute("type", type);
        model.addAttribute("includeCancelled", includeCancelled);
        model.addAttribute("active", "invoices");
        return "invoice/list";
    }

    /** 从账单详情开票 */
    @PostMapping
    public String issue(@RequestParam Long billId,
                        @RequestParam(required = false) InvoiceType type,
                        @RequestParam(required = false) String title,
                        @RequestParam(required = false) String taxNo,
                        @RequestParam(required = false) String content,
                        @RequestParam(required = false) BigDecimal amount,
                        @RequestParam(required = false) String buyerPhone,
                        @RequestParam(required = false) String buyerAddress,
                        @RequestParam(required = false) String remark,
                        @AuthenticationPrincipal LoginUser loginUser,
                        RedirectAttributes ra) {
        try {
            Invoice saved = invoiceService.issue(billId, type, title, taxNo, content, amount,
                    buyerPhone, buyerAddress, remark, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage",
                    "发票已开具：" + saved.getInvoiceNo() + " / " + saved.getTitle()
                            + " / ￥" + saved.getAmount());
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bills/" + billId;
    }

    /** 作废发票（软删除，保留流水；作废后额度释放） */
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         @RequestParam(required = false) Long billId,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            Invoice saved = invoiceService.cancel(id, reason, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage",
                    "发票 " + saved.getInvoiceNo() + " 已作废，该账单的可开额度已恢复");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return billId == null ? "redirect:/invoices" : "redirect:/bills/" + billId;
    }

    /** 打印用发票视图 */
    @GetMapping("/{id}/print")
    public String print(@PathVariable Long id, Model model) {
        model.addAttribute("invoice", invoiceService.getById(id));
        model.addAttribute("active", "invoices");
        return "invoice/print";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
