package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.common.CsvExport;
import com.hotel.hms.common.Pagination;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.Bill;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.service.BillService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应收账款台账（第六批）。
 *
 * <p>口径：<b>未结账单</b>就是应收。协议单位月结的场景下，在账单上登记挂账单位与约定回款日，
 * 台账按账龄排序、逾期高亮；回款时走正常结账流程，账单转「已结账」后自动从台账消失。
 */
@Controller
@RequestMapping("/receivables")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
public class ReceivableController {

    private final BillRepository billRepository;
    private final BillService billService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String account,
                       @RequestParam(required = false, defaultValue = "false") boolean creditedOnly,
                       @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = Pagination.pageSize(size);
        LocalDate today = LocalDate.now();
        Page<Bill> result = Pagination.fetch(
                p -> billRepository.searchReceivables(normalize(keyword), normalize(account),
                        creditedOnly, overdueOnly, today, p),
                Pagination.pageIndex(page), pageSize, Sort.by(Sort.Direction.ASC, "id"));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("keyword", keyword);
        filters.put("account", account);
        filters.put("creditedOnly", creditedOnly);
        filters.put("overdueOnly", overdueOnly);
        Pagination.Bases bases = Pagination.bases("/receivables", filters, pageSize);

        model.addAttribute("page", result);
        model.addAttribute("pageNumbers", Pagination.window(result));
        model.addAttribute("sizes", Pagination.SIZE_OPTIONS);
        model.addAttribute("pageBase", bases.page());
        model.addAttribute("sizeBase", bases.size());
        model.addAttribute("accounts", billRepository.findCreditAccounts());
        model.addAttribute("keyword", keyword);
        model.addAttribute("account", account);
        model.addAttribute("creditedOnly", creditedOnly);
        model.addAttribute("overdueOnly", overdueOnly);
        model.addAttribute("totalUnpaid", billRepository.sumUnpaidTotal());
        model.addAttribute("totalOverdue", billRepository.sumOverdueTotal(today));
        model.addAttribute("unpaidCount", billRepository.countByStatus(com.hotel.hms.entity.BillStatus.UNPAID));
        model.addAttribute("active", "receivables");
        return "receivable/list";
    }

    /** 导出应收台账 CSV */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String account,
                                         @RequestParam(required = false, defaultValue = "false") boolean creditedOnly,
                                         @RequestParam(required = false, defaultValue = "false") boolean overdueOnly) {
        LocalDate today = LocalDate.now();
        List<Bill> rows = billRepository.searchReceivables(normalize(keyword), normalize(account),
                creditedOnly, overdueOnly, today, org.springframework.data.domain.Pageable.unpaged()).getContent();

        List<String> headers = List.of("账单号", "订单号", "客户", "手机号", "房间", "退房时间",
                "应收合计", "已收押金", "挂账单位", "约定回款日", "账龄(天)", "是否逾期", "状态", "备注");
        List<List<?>> data = new ArrayList<>();
        for (Bill b : rows) {
            data.add(List.of(
                    nz(b.getBillNo()),
                    nz(b.getReservation().getOrderNo()),
                    nz(b.getCustomer().getName()),
                    nz(b.getCustomer().getPhone()),
                    nz(b.getRoom().getRoomNo()),
                    b.getReservation().getCheckOutTime() == null ? ""
                            : b.getReservation().getCheckOutTime().toString().replace('T', ' '),
                    money(b.getTotalAmount()),
                    money(b.getDeposit()),
                    nz(b.getCreditAccount()),
                    b.getCreditDueDate() == null ? "" : b.getCreditDueDate().toString(),
                    String.valueOf(b.getAgingDays()),
                    b.isOverdue() ? "是" : "否",
                    b.getStatus().getLabel(),
                    nz(b.getRemark())));
        }
        return CsvExport.download("应收账款_" + today + ".csv", headers, data);
    }

    /** 登记 / 修改挂账单位与约定回款日 */
    @PostMapping("/{billId}/credit")
    public String register(@PathVariable Long billId,
                           @RequestParam(required = false) String creditAccount,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate creditDueDate,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            Bill saved = billService.registerCredit(billId, creditAccount, creditDueDate, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", saved.isCredited()
                    ? "已登记挂账单位「" + saved.getCreditAccount() + "」"
                            + (saved.getCreditDueDate() == null ? "" : "，约定回款日 " + saved.getCreditDueDate())
                    : "已取消挂账登记，该账单转回散客欠款");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/receivables";
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }
}
