package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.SupplyCategory;
import com.hotel.hms.entity.SupplyItem;
import com.hotel.hms.entity.SupplyMovement;
import com.hotel.hms.entity.SupplyMovementType;
import com.hotel.hms.repository.SupplyItemRepository;
import com.hotel.hms.repository.SupplyMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 客房耗材出入库服务。
 *
 * <h3>不变量</h3>
 * <ul>
 *     <li>库存永不为负：领用 / 报废 / 盘亏都会先校验够不够，不够直接报错，不留脏数据；</li>
 *     <li>每一次库存变动都有流水：{@code stockAfter} 记录变动后的快照，
 *         即使日后有人手工改了库存，也能从流水看出断点在哪；
 *     </li>
 *     <li>入库记供货单价（成本），领用记去向（房间号 / 领用人），报废记原因。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SupplyService {

    private static final int RECENT_LIMIT = 60;

    private final SupplyItemRepository supplyItemRepository;
    private final SupplyMovementRepository supplyMovementRepository;
    private final OperationLogService operationLogService;

    // ------------------------------------------------------------------
    // 台账
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SupplyItem> list() {
        return supplyItemRepository.findAllByOrderByCategoryAscItemNameAsc();
    }

    @Transactional(readOnly = true)
    public List<SupplyItem> enabledList() {
        return supplyItemRepository.findByEnabledTrueOrderByCategoryAscItemNameAsc();
    }

    @Transactional(readOnly = true)
    public long countBelowMin() {
        return supplyItemRepository.countBelowMin();
    }

    @Transactional(readOnly = true)
    public SupplyItem get(Long id) {
        return supplyItemRepository.findById(id)
                .orElseThrow(() -> new BusinessException("耗材不存在"));
    }

    /**
     * 新建品名（首次入库）。
     * <p>「新增品名」与「入库」是同一个动作：建一条台账 + 记一笔入库流水，
     * 避免出现「有品名没库存、库存又从哪儿来」的悬空记录。
     */
    @Transactional
    public Long createItem(String itemName, SupplyCategory category, String unit, Integer stock,
                           Integer minStock, BigDecimal unitCost, String note, String operator) {
        String name = requireName(itemName);
        if (supplyItemRepository.existsByItemName(name)) {
            throw new BusinessException("耗材「" + name + "」已存在，请直接做入库");
        }
        SupplyItem item = new SupplyItem();
        item.setItemName(name);
        item.setCategory(category == null ? SupplyCategory.AMENITY : category);
        item.setUnit(unit);
        item.setStock(0);
        item.setMinStock(nonNeg(minStock, "安全库存"));
        item.setUnitCost(price(unitCost));
        item.setEnabled(true);
        item.setNote(note);
        SupplyItem saved = supplyItemRepository.save(item);

        int init = nonNeg(stock, "初始库存");
        if (init > 0) {
            apply(saved, SupplyMovementType.IN, init, saved.getUnitCost(), null, "建档初始入库", operator);
        }
        operationLogService.record("耗材建档", "supply_item", saved.getId(),
                saved.getDisplayName() + " 初始库存 " + saved.getStock()
                        + "（安全库存 " + saved.getMinStock() + "）", operator);
        return saved.getId();
    }

    @Transactional
    public void updateItem(Long id, Integer minStock, BigDecimal unitCost, Boolean enabled,
                           String unit, String note, String operator) {
        SupplyItem item = get(id);
        item.setMinStock(nonNeg(minStock, "安全库存"));
        item.setUnitCost(price(unitCost));
        item.setEnabled(enabled == null ? item.getEnabled() : enabled);
        item.setUnit(unit);
        item.setNote(note);
        supplyItemRepository.save(item);
        operationLogService.record("耗材参数", "supply_item", id,
                item.getDisplayName() + " 安全库存 " + item.getMinStock()
                        + " / 参考单价 ￥" + item.getUnitCost()
                        + " / " + (Boolean.TRUE.equals(item.getEnabled()) ? "启用" : "停用"), operator);
    }

    @Transactional
    public void deleteItem(Long id, String operator) {
        SupplyItem item = get(id);
        if (supplyMovementRepository.countByItemId(id) > 0) {
            throw new BusinessException("该耗材已有出入库流水，不能删除；如需停用请改为「停用」");
        }
        supplyItemRepository.delete(item);
        operationLogService.record("耗材删除", "supply_item", id, item.getDisplayName(), operator);
    }

    // ------------------------------------------------------------------
    // 出入库
    // ------------------------------------------------------------------

    /**
     * 记一笔出入库。
     *
     * @param type     入库 / 领用 / 报废 / 盘点调整
     * @param quantity 数量。入库 / 领用 / 报废要求为正数；盘点调整可正可负（盘亏为负）
     */
    @Transactional
    public Long move(Long itemId, SupplyMovementType type, Integer quantity,
                     BigDecimal unitCost, String target, String remark, String operator) {
        SupplyItem item = get(itemId);
        if (!Boolean.TRUE.equals(item.getEnabled())) {
            throw new BusinessException("该耗材已停用，无法出入库");
        }
        SupplyMovementType t = type == null ? SupplyMovementType.OUT : type;
        int qty = quantity == null ? 0 : quantity;
        if (t == SupplyMovementType.ADJUST) {
            if (qty == 0) {
                throw new BusinessException("盘点调整数量不能为 0");
            }
        } else if (qty < 1) {
            throw new BusinessException("数量必须大于 0");
        }
        return apply(item, t, qty, unitCost == null ? item.getUnitCost() : unitCost, target, remark, operator);
    }

    @Transactional(readOnly = true)
    public List<SupplyMovement> recentMovements() {
        return supplyMovementRepository.findAllByOrderByIdDesc(PageRequest.of(0, RECENT_LIMIT));
    }

    @Transactional(readOnly = true)
    public List<SupplyMovement> movementsOf(Long itemId) {
        return supplyMovementRepository.findByItemIdOrderByIdDesc(itemId);
    }

    // ------------------------------------------------------------------

    /** 真正改库存并写流水（建档时的初始入库也走这里，保证口径一致） */
    private Long apply(SupplyItem item, SupplyMovementType type, int qty,
                       BigDecimal unitCost, String target, String remark, String operator) {
        int before = item.getStock() == null ? 0 : item.getStock();
        int delta = switch (type) {
            case IN -> qty;
            case OUT, SCRAP -> -qty;
            case ADJUST -> qty;
        };
        int after = before + delta;
        if (after < 0) {
            String action = type == SupplyMovementType.SCRAP ? "报废" : "领用";
            throw new BusinessException(action + " " + Math.abs(delta) + " " + unit(item)
                    + " 超出当前库存（余 " + before + "）");
        }
        item.setStock(after);
        supplyItemRepository.save(item);

        SupplyMovement movement = new SupplyMovement();
        movement.setItem(item);
        movement.setType(type);
        movement.setQuantity(qty);
        movement.setStockAfter(after);
        movement.setUnitCost(unitCost == null ? BigDecimal.ZERO : unitCost);
        movement.setTarget(target);
        movement.setRemark(remark);
        movement.setOperator(operator);
        movement.setMovedAt(LocalDateTime.now());
        SupplyMovement saved = supplyMovementRepository.save(movement);

        operationLogService.record("耗材" + type.getLabel(), "supply_item", item.getId(),
                item.getItemName() + " " + type.getLabel() + " " + qty + " " + unit(item)
                        + "，库存 " + before + " → " + after
                        + (target == null || target.isBlank() ? "" : "，去向：" + target)
                        + (remark == null || remark.isBlank() ? "" : "（" + remark + "）"), operator);
        return saved.getId();
    }

    private String unit(SupplyItem item) {
        return item.getUnit() == null || item.getUnit().isBlank() ? "件" : item.getUnit();
    }

    private String requireName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException("请填写耗材品名");
        }
        return itemName.trim();
    }

    private Integer nonNeg(Integer value, String field) {
        int v = value == null ? 0 : value;
        if (v < 0) {
            throw new BusinessException(field + "不能为负数");
        }
        return v;
    }

    private BigDecimal price(BigDecimal value) {
        BigDecimal p = value == null ? BigDecimal.ZERO : value;
        if (p.signum() < 0) {
            throw new BusinessException("单价不能为负数");
        }
        return p.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
