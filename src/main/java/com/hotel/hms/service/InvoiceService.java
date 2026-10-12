package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.Invoice;
import com.hotel.hms.entity.InvoiceType;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 发票开具服务（第六批）。
 *
 * <h3>边界</h3>
 * 本系统<b>不接税控</b>，只登记票面信息并留痕：谁、什么时候、给哪张账单、开了多少钱。
 * 因此「开具」实质是登记，「作废」用软删除（保留流水可审计），
 * 累计开票额不得超过账单应收合计——这条校验才是这个功能真正的价值：
 * 防止同一张账单被重复开票。
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final BillRepository billRepository;
    private final SerialNumberService serialNumberService;
    private final OperationLogService operationLogService;

    /** 某账单已开票金额（不含作废） */
    @Transactional(readOnly = true)
    public BigDecimal issuedAmount(Long billId) {
        return nvl(invoiceRepository.sumIssuedAmount(billId));
    }

    /** 某账单还可开票的额度 = 应收 − 已开票 */
    @Transactional(readOnly = true)
    public BigDecimal remainingAmount(Long billId) {
        Bill bill = requireBill(billId);
        return nvl(bill.getTotalAmount()).subtract(issuedAmount(billId)).max(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public List<Invoice> listByBill(Long billId) {
        return invoiceRepository.findByBillIdOrderByIdDesc(billId);
    }

    @Transactional(readOnly = true)
    public Invoice getById(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new BusinessException("发票记录不存在"));
    }

    @Transactional(readOnly = true)
    public List<Invoice> activeByBill(Long billId) {
        return invoiceRepository.findByBillIdAndCancelledFalseOrderByIdDesc(billId);
    }

    @Transactional(readOnly = true)
    public Page<Invoice> search(String keyword, InvoiceType type, boolean includeCancelled, Pageable pageable) {
        return invoiceRepository.search(normalize(keyword), type, includeCancelled, pageable);
    }

    /**
     * 开具（登记）发票。
     *
     * <p>校验三件事：账单必须已结账、专票必须有税号、累计开票额不得超过应收。
     * 已结账才能开票是行业惯例——没结账就不知道最终住几晚、该开多少钱。
     */
    @Transactional
    public Invoice issue(Long billId, InvoiceType type, String title, String taxNo, String content,
                         BigDecimal amount, String buyerPhone, String buyerAddress,
                         String remark, String operator) {
        Bill bill = requireBill(billId);
        if (bill.getStatus() != BillStatus.SETTLED) {
            throw new BusinessException("账单尚未结账，不能开票。请先完成退房结算。");
        }
        InvoiceType invoiceType = type == null ? InvoiceType.VAT_NORMAL : type;

        String invoiceTitle = trim(title);
        if (invoiceTitle == null) {
            throw new BusinessException("请填写发票抬头");
        }
        String tax = trim(taxNo);
        if (invoiceType == InvoiceType.VAT_SPECIAL && tax == null) {
            throw new BusinessException("增值税专用发票必须填写纳税人识别号");
        }
        if (tax != null && tax.length() > 40) {
            throw new BusinessException("纳税人识别号过长（最多 40 位）");
        }

        BigDecimal value = nvl(amount).setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            throw new BusinessException("开票金额必须大于 0");
        }
        BigDecimal remaining = remainingAmount(billId);
        if (value.compareTo(remaining) > 0) {
            throw new BusinessException("开票金额超过可开额度：本单应收 ￥" + money(bill.getTotalAmount())
                    + "，已开 ￥" + money(issuedAmount(billId))
                    + "，最多还能开 ￥" + money(remaining));
        }

        Invoice invoice = new Invoice();
        invoice.setInvoiceNo(serialNumberService.nextInvoiceNo());
        invoice.setBill(bill);
        invoice.setType(invoiceType);
        invoice.setTitle(invoiceTitle);
        invoice.setTaxNo(tax);
        invoice.setContent(trim(content) == null ? "住宿费" : trim(content));
        invoice.setAmount(value);
        invoice.setBuyerPhone(trim(buyerPhone));
        invoice.setBuyerAddress(trim(buyerAddress));
        invoice.setOperator(operator);
        invoice.setRemark(trim(remark));
        invoice.setCancelled(false);

        Invoice saved = invoiceRepository.save(invoice);
        operationLogService.record("发票开具", "invoice", saved.getId(),
                saved.getInvoiceNo() + " / " + invoiceType.getShortLabel() + " / " + saved.getTitle()
                        + " / 账单 " + bill.getBillNo() + " / 金额 ￥" + money(value)
                        + (tax == null ? "" : " / 税号 " + tax), operator);
        return saved;
    }

    /** 作废发票（软删除，保留流水；作废后金额不再占用该账单的可开额度） */
    @Transactional
    public Invoice cancel(Long id, String reason, String operator) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new BusinessException("发票记录不存在"));
        if (Boolean.TRUE.equals(invoice.getCancelled())) {
            throw new BusinessException("该发票已作废，无需重复操作");
        }
        String note = trim(reason);
        if (note == null) {
            throw new BusinessException("作废发票必须填写原因");
        }
        invoice.setCancelled(true);
        invoice.setCancelledAt(java.time.LocalDateTime.now());
        invoice.setCancelReason(note);
        Invoice saved = invoiceRepository.save(invoice);
        operationLogService.record("发票作废", "invoice", saved.getId(),
                saved.getInvoiceNo() + " / " + saved.getTitle() + " / 金额 ￥" + money(saved.getAmount())
                        + " / 原因：" + note, operator);
        return saved;
    }

    private Bill requireBill(Long billId) {
        return billRepository.findDetailById(billId)
                .orElseThrow(() -> new BusinessException("账单不存在"));
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalize(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String money(BigDecimal value) {
        return nvl(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
