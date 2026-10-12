package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.common.CsvExport;
import com.hotel.hms.common.Pagination;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.entity.PaymentRecord;
import com.hotel.hms.entity.PaymentType;
import com.hotel.hms.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收款流水与押金收退（第六批）。
 *
 * <p>押金不再是账单上一个手工数字：每一笔收取 / 退还都在这里落成流水，
 * 账单上的押金、结账时的可抵扣额、交接班的当班收款汇总，全部由流水推导。
 */
@Controller
@RequestMapping("/payments")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
public class PaymentController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Map<String, Sort> SORTS = new LinkedHashMap<>();

    static {
        SORTS.put("latest", Sort.by(Sort.Direction.DESC, "id"));
        SORTS.put("earliest", Sort.by(Sort.Direction.ASC, "id"));
        SORTS.put("amountDesc", Sort.by(Sort.Direction.DESC, "amount"));
    }

    private static final Map<String, String> SORT_LABELS = new LinkedHashMap<>();

    static {
        SORT_LABELS.put("latest", "最新流水在前");
        SORT_LABELS.put("earliest", "最早流水在前");
        SORT_LABELS.put("amountDesc", "金额从高到低");
    }

    private final PaymentService paymentService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) PaymentType type,
                       @RequestParam(required = false) PayMethod method,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = Pagination.pageSize(size);
        String sortKey = Pagination.sortKey(sort, SORTS, "latest");
        Page<PaymentRecord> result = Pagination.fetch(
                p -> paymentService.search(keyword, type, method, from, to, p),
                Pagination.pageIndex(page), pageSize, SORTS.get(sortKey));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("keyword", keyword);
        filters.put("type", type);
        filters.put("method", method);
        filters.put("from", from);
        filters.put("to", to);
        filters.put("sort", sortKey);
        Pagination.Bases bases = Pagination.bases("/payments", filters, pageSize);

        model.addAttribute("page", result);
        model.addAttribute("pageNumbers", Pagination.window(result));
        model.addAttribute("sizes", Pagination.SIZE_OPTIONS);
        model.addAttribute("pageBase", bases.page());
        model.addAttribute("sizeBase", bases.size());
        model.addAttribute("types", PaymentType.values());
        model.addAttribute("methods", PayMethod.values());
        model.addAttribute("sortLabels", SORT_LABELS);
        model.addAttribute("sort", sortKey);
        model.addAttribute("keyword", keyword);
        model.addAttribute("type", type);
        model.addAttribute("method", method);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("active", "payments");
        return "payment/list";
    }

    /** 导出收款流水 CSV，用于财务对账 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) PaymentType type,
                                         @RequestParam(required = false) PayMethod method,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String sort) {
        String sortKey = Pagination.sortKey(sort, SORTS, "latest");
        List<PaymentRecord> rows = paymentService
                .search(keyword, type, method, from, to, Pageable.unpaged(SORTS.get(sortKey)))
                .getContent();

        List<String> headers = List.of("发生时间", "类型", "订单号", "客户", "房间", "账单号",
                "金额", "方向", "支付方式", "操作人", "备注");
        List<List<?>> data = new ArrayList<>();
        for (PaymentRecord p : rows) {
            data.add(List.of(
                    p.getOccurredAt() == null ? "" : p.getOccurredAt().format(TS),
                    p.getType().getLabel(),
                    nz(p.getReservation().getOrderNo()),
                    nz(p.getReservation().getCustomer().getName()),
                    nz(p.getReservation().getRoom().getRoomNo()),
                    p.getBill() == null ? "" : nz(p.getBill().getBillNo()),
                    money(p.getAmount()),
                    p.getType().isIncome() ? "收款" : "退款",
                    p.getPayMethod() == null ? "" : p.getPayMethod().getLabel(),
                    nz(p.getOperator()),
                    nz(p.getRemark())));
        }
        return CsvExport.download("收款流水_" + LocalDate.now() + ".csv", headers, data);
    }

    /** 收取押金 */
    @PostMapping("/deposit")
    public String deposit(@RequestParam Long reservationId,
                          @RequestParam(required = false) BigDecimal amount,
                          @RequestParam(required = false) PayMethod payMethod,
                          @RequestParam(required = false) String remark,
                          @AuthenticationPrincipal LoginUser loginUser,
                          RedirectAttributes ra) {
        return act(() -> paymentService.depositIn(reservationId, amount, payMethod, remark, operatorOf(loginUser)),
                "押金已收取", ra, reservationId);
    }

    /** 收取预订金 */
    @PostMapping("/booking")
    public String booking(@RequestParam Long reservationId,
                          @RequestParam(required = false) BigDecimal amount,
                          @RequestParam(required = false) PayMethod payMethod,
                          @RequestParam(required = false) String remark,
                          @AuthenticationPrincipal LoginUser loginUser,
                          RedirectAttributes ra) {
        return act(() -> paymentService.bookingDeposit(reservationId, amount, payMethod, remark, operatorOf(loginUser)),
                "预订金已收取", ra, reservationId);
    }

    /** 退还押金 */
    @PostMapping("/refund")
    public String refund(@RequestParam Long reservationId,
                         @RequestParam(required = false) BigDecimal amount,
                         @RequestParam(required = false) PayMethod payMethod,
                         @RequestParam(required = false) String remark,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        return act(() -> paymentService.depositRefund(reservationId, amount, payMethod, remark, operatorOf(loginUser)),
                "押金已退还", ra, reservationId);
    }

    private String act(java.util.function.Supplier<PaymentRecord> action, String successMessage,
                       RedirectAttributes ra, Long reservationId) {
        try {
            PaymentRecord record = action.get();
            ra.addFlashAttribute("successMessage",
                    successMessage + " ￥" + money(record.getAmount())
                            + "（" + record.getType().getLabel() + "）");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/reservations/" + reservationId;
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }

    private String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }
}
