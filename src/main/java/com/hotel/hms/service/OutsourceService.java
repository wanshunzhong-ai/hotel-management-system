package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.LinenStock;
import com.hotel.hms.entity.OutsourceBatch;
import com.hotel.hms.entity.OutsourceItem;
import com.hotel.hms.entity.OutsourceStatus;
import com.hotel.hms.repository.LinenStockRepository;
import com.hotel.hms.repository.OutsourceBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 布草外送洗涤结算服务。
 *
 * <h3>三个动作 + 一次库存联动</h3>
 * <ol>
 *     <li><b>送出</b>：建批次单，逐品名把数量从布草「在库」转入「洗涤中」（与领用口径一致）；</li>
 *     <li><b>收回</b>：洗涤中 → 在库；</li>
 *     <li><b>结算</b>：记下金额与收款方，收口（必须收回后才能结算）。</li>
 * </ol>
 *
 * <p>与 {@link LinenService#issue} 的关系：领用是「自己拿去洗」，外送是「交给洗涤公司」。
 * 两条路径的库存流转一样（在库 → 洗涤中），但外送多了一张能对账的交接单。
 */
@Service
@RequiredArgsConstructor
public class OutsourceService {

    private final OutsourceBatchRepository batchRepository;
    private final LinenStockRepository linenStockRepository;
    private final SerialNumberService serialNumberService;
    private final OperationLogService operationLogService;

    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OutsourceBatch> list(OutsourceStatus status) {
        return status == null ? batchRepository.findAllByOrderByIdDesc()
                : batchRepository.findByStatusOrderByIdDesc(status);
    }

    /**
     * 取批次（含洗涤明细）。
     *
     * <p>走 {@code findDetailById} 的 fetch join，而不是 {@code findById}——
     * 交接单详情页与打印页都要遍历 {@code batch.items}，
     * 而 OSIV 已关闭，惰性集合在事务外取不到（见 {@code OutsourceBatchRepository#findDetailById}）。
     */
    @Transactional(readOnly = true)
    public OutsourceBatch get(Long id) {
        return batchRepository.findDetailById(id)
                .orElseThrow(() -> new BusinessException("外送批次不存在"));
    }

    @Transactional(readOnly = true)
    public long countByStatus(OutsourceStatus status) {
        return batchRepository.countByStatus(status);
    }

    /**
     * 送出：建批次单并锁定布草库存。
     *
     * <p>逐品名校验在库够不够，任一行不足就整体失败（事务回滚）——
     * 半送半不送会让批次单与库存对不上。
     */
    @Transactional
    public Long create(String vendor, List<Long> linenIds, List<Integer> quantities,
                       List<BigDecimal> prices, String remark, String operator) {
        if (linenIds == null || linenIds.isEmpty()) {
            throw new BusinessException("请至少添加一条送洗明细");
        }
        int size = linenIds.size();
        if (quantities == null || quantities.size() != size) {
            throw new BusinessException("送洗明细的数量与品名不匹配");
        }

        OutsourceBatch batch = new OutsourceBatch();
        batch.setBatchNo(serialNumberService.nextOutsourceNo());
        batch.setVendor(vendor);
        batch.setStatus(OutsourceStatus.SENT);
        batch.setSentAt(LocalDateTime.now());
        batch.setRemark(remark);
        batch.setOperator(operator);

        int totalQty = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OutsourceItem> items = new ArrayList<>();
        List<LinenStock> touched = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            Long linenId = linenIds.get(i);
            if (linenId == null) {
                continue;
            }
            Integer qty = quantities.get(i);
            if (qty == null || qty < 1) {
                throw new BusinessException("第 " + (i + 1) + " 行数量必须大于 0");
            }
            int row = i + 1;   // 供 lambda 捕获（for 循环变量 i 不是 effectively final）
            LinenStock stock = linenStockRepository.findById(linenId)
                    .orElseThrow(() -> new BusinessException("第 " + row + " 行的布草品名不存在"));
            int inStock = nz(stock.getInStock());
            if (qty > inStock) {
                throw new BusinessException(stock.getItemName() + " 在库仅 " + inStock + " 件，无法送洗 " + qty + " 件");
            }
            // 在库 → 洗涤中
            stock.setInStock(inStock - qty);
            stock.setInWash(nz(stock.getInWash()) + qty);
            touched.add(stock);

            BigDecimal price = prices == null || prices.size() <= i || prices.get(i) == null
                    ? BigDecimal.ZERO : prices.get(i);
            if (price.signum() < 0) {
                throw new BusinessException(stock.getItemName() + " 的洗涤单价不能为负数");
            }
            OutsourceItem item = new OutsourceItem();
            item.setLinenStockId(stock.getId());
            item.setItemName(stock.getItemName());
            item.setQuantity(qty);
            item.setUnitPrice(price.setScale(2, RoundingMode.HALF_UP));
            item.recalculate();
            batch.addItem(item);
            items.add(item);

            totalQty += qty;
            totalAmount = totalAmount.add(item.getAmount());
        }
        if (items.isEmpty()) {
            throw new BusinessException("请至少添加一条送洗明细");
        }
        linenStockRepository.saveAll(touched);

        batch.setTotalQty(totalQty);
        batch.setTotalAmount(totalAmount);
        OutsourceBatch saved = batchRepository.save(batch);
        operationLogService.record("布草外送", "outsource_batch", saved.getId(),
                saved.getBatchNo() + " → " + (vendor == null || vendor.isBlank() ? "未填洗涤公司" : vendor)
                        + "，" + items.size() + " 个品名 / 共 " + totalQty + " 件 / ￥" + totalAmount, operator);
        return saved.getId();
    }

    /** 收回：洗涤中 → 在库 */
    @Transactional
    public void markReturned(Long id, String operator) {
        OutsourceBatch batch = get(id);
        if (batch.getStatus() != OutsourceStatus.SENT) {
            throw new BusinessException("批次当前为「" + batch.getStatus().getLabel() + "」，不能重复收回");
        }
        for (OutsourceItem item : batch.getItems()) {
            if (item.getLinenStockId() == null) {
                continue;
            }
            LinenStock stock = linenStockRepository.findById(item.getLinenStockId()).orElse(null);
            if (stock == null) {
                continue;
            }
            int qty = item.getQuantity() == null ? 0 : item.getQuantity();
            stock.setInWash(Math.max(0, nz(stock.getInWash()) - qty));
            stock.setInStock(nz(stock.getInStock()) + qty);
            linenStockRepository.save(stock);
        }
        batch.setStatus(OutsourceStatus.RETURNED);
        batch.setReturnedAt(LocalDateTime.now());
        batchRepository.save(batch);
        operationLogService.record("布草收回", "outsource_batch", id,
                batch.getBatchNo() + " 已收回，共 " + batch.getTotalQty() + " 件回补在库", operator);
    }

    /** 结算：与洗涤公司核对金额后收口 */
    @Transactional
    public void settle(Long id, BigDecimal amount, String operator) {
        OutsourceBatch batch = get(id);
        if (batch.getStatus() == OutsourceStatus.SETTLED) {
            throw new BusinessException("该批次已结算");
        }
        if (batch.getStatus() != OutsourceStatus.RETURNED) {
            throw new BusinessException("布草尚未收回，不能结算");
        }
        if (amount != null) {
            if (amount.signum() < 0) {
                throw new BusinessException("结算金额不能为负数");
            }
            batch.setTotalAmount(amount.setScale(2, RoundingMode.HALF_UP));
        }
        batch.setStatus(OutsourceStatus.SETTLED);
        batch.setSettledAt(LocalDateTime.now());
        batchRepository.save(batch);
        operationLogService.record("布草结算", "outsource_batch", id,
                batch.getBatchNo() + " 与 " + (batch.getVendor() == null ? "洗涤公司" : batch.getVendor())
                        + " 结算 ￥" + batch.getTotalAmount(), operator);
    }

    /** 取消送出（仅未收回、未结算可取消）：数量退回在库 */
    @Transactional
    public void cancel(Long id, String reason, String operator) {
        OutsourceBatch batch = get(id);
        if (batch.getStatus() != OutsourceStatus.SENT) {
            throw new BusinessException("仅「已送出」的批次可以取消");
        }
        for (OutsourceItem item : batch.getItems()) {
            if (item.getLinenStockId() == null) {
                continue;
            }
            LinenStock stock = linenStockRepository.findById(item.getLinenStockId()).orElse(null);
            if (stock == null) {
                continue;
            }
            int qty = item.getQuantity() == null ? 0 : item.getQuantity();
            stock.setInWash(Math.max(0, nz(stock.getInWash()) - qty));
            stock.setInStock(nz(stock.getInStock()) + qty);
            linenStockRepository.save(stock);
        }
        batch.setStatus(OutsourceStatus.RETURNED);
        batch.setReturnedAt(LocalDateTime.now());
        batch.setRemark((batch.getRemark() == null ? "" : batch.getRemark() + " ")
                + "[已取消送出：" + (reason == null || reason.isBlank() ? "未填原因" : reason) + "]");
        batchRepository.save(batch);
        operationLogService.record("布草外送取消", "outsource_batch", id,
                batch.getBatchNo() + " 已取消送出，数量退回在库（"
                        + (reason == null || reason.isBlank() ? "未填原因" : reason) + "）", operator);
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
