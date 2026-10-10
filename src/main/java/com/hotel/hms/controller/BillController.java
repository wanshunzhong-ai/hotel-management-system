package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.common.CsvExport;
import com.hotel.hms.common.Pagination;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.dto.CheckoutForm;
import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillItem;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.service.BillService;
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
import org.springframework.web.bind.annotation.PathVariable;
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
 * 账单与结算。
 */
@Controller
@RequestMapping("/bills")
@RequiredArgsConstructor
public class BillController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 排序白名单：键 → Sort。页面下拉的选项也由此生成，新增排序只需在这里加一行。 */
    private static final Map<String, Sort> SORTS = new LinkedHashMap<>();

    static {
        SORTS.put("latest", Sort.by(Sort.Direction.DESC, "id"));
        SORTS.put("settledDesc", Sort.by(Sort.Direction.DESC, "settledAt"));
        SORTS.put("amountDesc", Sort.by(Sort.Direction.DESC, "totalAmount"));
    }

    private static final Map<String, String> SORT_LABELS = new LinkedHashMap<>();

    static {
        SORT_LABELS.put("latest", "最新账单在前");
        SORT_LABELS.put("settledDesc", "结账时间倒序");
        SORT_LABELS.put("amountDesc", "应收金额从高到低");
    }

    private final BillService billService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) BillStatus status,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settledFrom,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settledTo,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = Pagination.pageSize(size);
        String sortKey = Pagination.sortKey(sort, SORTS, "latest");
        Page<Bill> result = Pagination.fetch(
                p -> billService.search(keyword, status, settledFrom, settledTo, p),
                Pagination.pageIndex(page), pageSize, SORTS.get(sortKey));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("keyword", keyword);
        filters.put("status", status);
        filters.put("settledFrom", settledFrom);
        filters.put("settledTo", settledTo);
        filters.put("sort", sortKey);
        Pagination.Bases bases = Pagination.bases("/bills", filters, pageSize);

        model.addAttribute("page", result);
        model.addAttribute("pageNumbers", Pagination.window(result));
        model.addAttribute("sizes", Pagination.SIZE_OPTIONS);
        model.addAttribute("pageBase", bases.page());
        model.addAttribute("sizeBase", bases.size());
        model.addAttribute("statuses", BillStatus.values());
        model.addAttribute("sortLabels", SORT_LABELS);
        model.addAttribute("sort", sortKey);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("settledFrom", settledFrom);
        model.addAttribute("settledTo", settledTo);
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

    /** 打印用视图：干净的小票版式，不含侧边栏与操作按钮 */
    @GetMapping("/{id}/print")
    public String print(@PathVariable Long id, Model model) {
        model.addAttribute("bill", billService.getDetail(id));
        model.addAttribute("active", "bills");
        return "bill/print";
    }

    /**
     * 导出账单 CSV（第三批 §3.7）。
     * <p>导出当前筛选条件下的<b>全部</b>结果，便于月结对账。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) BillStatus status,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settledFrom,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settledTo,
                                         @RequestParam(required = false) String sort) {
        String sortKey = Pagination.sortKey(sort, SORTS, "latest");
        List<Bill> bills = billService.search(keyword, status, settledFrom, settledTo,
                Pageable.unpaged(SORTS.get(sortKey))).getContent();

        List<String> headers = List.of("账单号", "订单号", "客户", "手机号", "房间", "住店区间",
                "房费", "其他消费", "折扣", "提前离店减免", "应收合计", "押金", "退房补付",
                "收款合计", "应退", "支付方式", "状态", "结账时间", "操作人");
        List<List<?>> rows = new ArrayList<>();
        for (Bill b : bills) {
            rows.add(List.of(
                    nz(b.getBillNo()),
                    nz(b.getReservation().getOrderNo()),
                    nz(b.getCustomer().getName()),
                    nz(b.getCustomer().getPhone()),
                    nz(b.getRoom().getRoomNo()),
                    b.getReservation().getCheckInDate() + " ~ " + b.getReservation().getCheckOutDate(),
                    money(b.getRoomAmount()), money(b.getOtherAmount()), money(b.getDiscountAmount()),
                    money(b.getEarlyCheckoutDiscount()), money(b.getTotalAmount()),
                    money(b.getDeposit()), money(b.getPaidAmount()), money(b.getReceivedAmount()),
                    money(b.getRefundAmount()),
                    b.getPayMethod() == null ? "" : b.getPayMethod().getLabel(),
                    b.getStatus().getLabel(),
                    b.getSettledAt() == null ? "" : b.getSettledAt().format(TS),
                    nz(b.getOperator())));
        }
        return CsvExport.download("账单明细_" + LocalDate.now().format(DAY) + ".csv", headers, rows);
    }

    @PostMapping("/{id}/items")
    public String addItem(@PathVariable Long id,
                          @RequestParam String itemName,
                          @RequestParam(required = false) BigDecimal unitPrice,
                          @RequestParam(required = false) Integer quantity,
                          @RequestParam(required = false) String remark,
                          @AuthenticationPrincipal LoginUser loginUser,
                          RedirectAttributes ra) {
        try {
            BillItem item = new BillItem();
            item.setItemName(itemName);
            item.setUnitPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice);
            item.setQuantity(quantity == null ? 1 : quantity);
            item.setRemark(remark);
            billService.addItem(id, item, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "消费项目已添加");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bills/" + id;
    }

    @PostMapping("/{id}/items/{itemId}/delete")
    public String removeItem(@PathVariable Long id,
                             @PathVariable Long itemId,
                             @AuthenticationPrincipal LoginUser loginUser,
                             RedirectAttributes ra) {
        try {
            billService.removeItem(id, itemId, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "消费项目已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bills/" + id;
    }

    /** 设置人工折扣 / 减免 */
    @PostMapping("/{id}/discount")
    public String discount(@PathVariable Long id,
                           @RequestParam(required = false) BigDecimal discountAmount,
                           @RequestParam(required = false) String reason,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            Bill bill = billService.applyDiscount(id, discountAmount, reason, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage",
                    "折扣已更新为 ￥" + bill.getDiscountAmount() + "，应收合计 ￥" + bill.getTotalAmount());
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bills/" + id;
    }

    /**
     * 反结账（仅 ADMIN）。
     * <p>方法级安全在这里是真正生效的——SecurityConfig 开了 {@code @EnableMethodSecurity}，
     * 这是项目里第一处 {@code @PreAuthorize}：把权限从 URL 层下沉到动作层，
     * 避免将来有人把 /bills/** 放开给前台时顺手把反结账也放出去。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/unsettle")
    public String unsettle(@PathVariable Long id,
                           @RequestParam(required = false) String reason,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            Bill bill = billService.unsettle(id, reason, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage",
                    "账单 " + bill.getBillNo() + " 已反结账，状态回到「待结账」，可修改后重新结账");
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
            Bill bill = billService.settle(id, form, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage",
                    "账单 " + bill.getBillNo() + " 结账完成：应收 ￥" + bill.getTotalAmount()
                            + "，收款合计 ￥" + bill.getReceivedAmount()
                            + (bill.getRefundAmount().signum() > 0 ? "，应退客人 ￥" + bill.getRefundAmount() : ""));
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bills/" + id;
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? null : loginUser.getRealName();
    }

    private String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }
}
