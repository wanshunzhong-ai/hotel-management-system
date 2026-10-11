package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.LinenCategory;
import com.hotel.hms.entity.LinenStock;
import com.hotel.hms.repository.LinenStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 布草库存服务：在库 / 洗涤中 / 报废 三态互转 + 安全库存预警。
 *
 * <p>数量守恒：领用与报废都在「当前所在状态」上做减法并校验够不够，
 * 绝不出现负数——脏数据比一次报错更麻烦。
 */
@Service
@RequiredArgsConstructor
public class LinenService {

    private final LinenStockRepository linenStockRepository;
    private final OperationLogService operationLogService;

    @Transactional(readOnly = true)
    public List<LinenStock> list() {
        return linenStockRepository.findAllByOrderByCategoryAscItemNameAsc();
    }

    @Transactional(readOnly = true)
    public long countBelowMin() {
        return linenStockRepository.countBelowMin();
    }

    /** 新购入库 / 新增品名 */
    @Transactional
    public Long create(String itemName, LinenCategory category, Integer inStock, Integer minStock,
                       String note, String operator) {
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException("请填写布草品名");
        }
        String name = itemName.trim();
        if (linenStockRepository.existsByItemName(name)) {
            throw new BusinessException("布草「" + name + "」已存在，请直接调整数量");
        }
        LinenStock stock = new LinenStock();
        stock.setItemName(name);
        stock.setCategory(category == null ? LinenCategory.BEDDING : category);
        stock.setInStock(nonNeg(inStock, "在库数量"));
        stock.setInWash(0);
        stock.setScrapped(0);
        stock.setMinStock(nonNeg(minStock, "安全库存"));
        stock.setNote(note);
        LinenStock saved = linenStockRepository.save(stock);
        operationLogService.record("布草入库", "linen_stock", saved.getId(),
                name + " 入库 " + saved.getInStock() + " 件（安全库存 " + saved.getMinStock() + "）", operator);
        return saved.getId();
    }

    /** 调整安全库存与备注 */
    @Transactional
    public void update(Long id, Integer minStock, String note, String operator) {
        LinenStock stock = get(id);
        stock.setMinStock(nonNeg(minStock, "安全库存"));
        stock.setNote(note);
        linenStockRepository.save(stock);
        operationLogService.record("布草参数", "linen_stock", id,
                stock.getItemName() + " 安全库存调整为 " + stock.getMinStock(), operator);
    }

    /** 领用出库：在库 → 洗涤中 */
    @Transactional
    public void issue(Long id, Integer quantity, String operator) {
        LinenStock stock = get(id);
        int qty = requirePositive(quantity);
        int inStock = nz(stock.getInStock());
        if (qty > inStock) {
            throw new BusinessException("在库仅 " + inStock + " 件，无法领用 " + qty + " 件");
        }
        stock.setInStock(inStock - qty);
        stock.setInWash(nz(stock.getInWash()) + qty);
        linenStockRepository.save(stock);
        operationLogService.record("布草领用", "linen_stock", id,
                stock.getItemName() + " 领用 " + qty + " 件送洗，在库余 " + stock.getInStock(), operator);
    }

    /** 洗涤回库：洗涤中 → 在库 */
    @Transactional
    public void back(Long id, Integer quantity, String operator) {
        LinenStock stock = get(id);
        int qty = requirePositive(quantity);
        int inWash = nz(stock.getInWash());
        if (qty > inWash) {
            throw new BusinessException("洗涤中仅 " + inWash + " 件，无法回库 " + qty + " 件");
        }
        stock.setInWash(inWash - qty);
        stock.setInStock(nz(stock.getInStock()) + qty);
        linenStockRepository.save(stock);
        operationLogService.record("布草回库", "linen_stock", id,
                stock.getItemName() + " 洗涤回库 " + qty + " 件，在库 " + stock.getInStock(), operator);
    }

    /** 报废：在库 → 报废（不可逆） */
    @Transactional
    public void scrap(Long id, Integer quantity, String operator) {
        LinenStock stock = get(id);
        int qty = requirePositive(quantity);
        int inStock = nz(stock.getInStock());
        if (qty > inStock) {
            throw new BusinessException("在库仅 " + inStock + " 件，无法报废 " + qty + " 件");
        }
        stock.setInStock(inStock - qty);
        stock.setScrapped(nz(stock.getScrapped()) + qty);
        linenStockRepository.save(stock);
        operationLogService.record("布草报废", "linen_stock", id,
                stock.getItemName() + " 报废 " + qty + " 件，累计报废 " + stock.getScrapped(), operator);
    }

    // ------------------------------------------------------------------

    private LinenStock get(Long id) {
        return linenStockRepository.findById(id)
                .orElseThrow(() -> new BusinessException("布草记录不存在"));
    }

    private int requirePositive(Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BusinessException("数量必须大于 0");
        }
        return quantity;
    }

    private Integer nonNeg(Integer value, String field) {
        int v = value == null ? 0 : value;
        if (v < 0) {
            throw new BusinessException(field + "不能为负数");
        }
        return v;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
