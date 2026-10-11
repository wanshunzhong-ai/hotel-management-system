package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.common.RandomToken;
import com.hotel.hms.entity.ConsumableCategory;
import com.hotel.hms.entity.ConsumableItem;
import com.hotel.hms.entity.Room;
import com.hotel.hms.repository.ConsumableItemRepository;
import com.hotel.hms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 客房消费目录 + 房间二维码令牌。
 *
 * <p>目录回答「这间房能消费什么、多少钱」；令牌回答「扫码的人是不是站在这间房门口」。
 * 两者都不含权限判定——真正的入账校验（房间必须有在住订单）在
 * {@link MinibarService#create} 里，这里只负责把房间定位出来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumableService {

    private final ConsumableItemRepository consumableItemRepository;
    private final RoomRepository roomRepository;
    private final OperationLogService operationLogService;

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ConsumableItem> list() {
        return consumableItemRepository.findAllByOrderByCategoryAscItemNameAsc();
    }

    /** 扫码页可见的清单 */
    @Transactional(readOnly = true)
    public List<ConsumableItem> enabledList() {
        return consumableItemRepository.findByEnabledTrueOrderByCategoryAscItemNameAsc();
    }

    @Transactional(readOnly = true)
    public long countEnabled() {
        return consumableItemRepository.countByEnabledTrue();
    }

    @Transactional
    public Long create(String itemName, ConsumableCategory category, BigDecimal unitPrice,
                       String note, String operator) {
        String name = requireName(itemName);
        if (consumableItemRepository.existsByItemName(name)) {
            throw new BusinessException("消费项目「" + name + "」已存在");
        }
        ConsumableItem item = new ConsumableItem();
        item.setItemName(name);
        item.setCategory(category == null ? ConsumableCategory.MINIBAR : category);
        item.setUnitPrice(requirePrice(unitPrice));
        item.setEnabled(true);
        item.setNote(note);
        ConsumableItem saved = consumableItemRepository.save(item);
        operationLogService.record("消费目录新增", "consumable_item", saved.getId(),
                name + " / ￥" + saved.getUnitPrice() + "（" + saved.getCategory().getLabel() + "）", operator);
        return saved.getId();
    }

    @Transactional
    public void update(Long id, ConsumableCategory category, BigDecimal unitPrice, Boolean enabled,
                       String note, String operator) {
        ConsumableItem item = get(id);
        item.setCategory(category == null ? item.getCategory() : category);
        item.setUnitPrice(requirePrice(unitPrice));
        item.setEnabled(enabled == null ? item.getEnabled() : enabled);
        item.setNote(note);
        consumableItemRepository.save(item);
        operationLogService.record("消费目录修改", "consumable_item", id,
                item.getItemName() + " / ￥" + item.getUnitPrice()
                        + " / " + (Boolean.TRUE.equals(item.getEnabled()) ? "上架" : "下架"), operator);
    }

    @Transactional
    public void delete(Long id, String operator) {
        ConsumableItem item = get(id);
        consumableItemRepository.delete(item);
        operationLogService.record("消费目录删除", "consumable_item", id, item.getItemName(), operator);
    }

    // ------------------------------------------------------------------
    // 房间二维码令牌
    // ------------------------------------------------------------------

    /** 按令牌找房间（扫码页入口） */
    @Transactional(readOnly = true)
    public Optional<Room> roomByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return roomRepository.findByScanToken(token);
    }

    /**
     * 为所有还没有令牌的房间补发（启动时调用，幂等）。
     *
     * <p>老库里的房间是在二维码功能之前建的，{@code scan_token} 为空；
     * 补发保证每间房都能立刻打出可用的二维码。
     *
     * @return 本次补发的房间数
     */
    @Transactional
    public int ensureRoomTokens() {
        List<Room> missing = roomRepository.findAllByOrderByRoomNoAsc().stream()
                .filter(r -> r.getScanToken() == null || r.getScanToken().isBlank())
                .toList();
        for (Room room : missing) {
            room.setScanToken(uniqueToken());
        }
        roomRepository.saveAll(missing);
        return missing.size();
    }

    /** 重置某房间的二维码令牌：旧二维码立即失效 */
    @Transactional
    public String resetRoomToken(Long roomId, String operator) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("房间不存在"));
        room.setScanToken(uniqueToken());
        roomRepository.save(room);
        operationLogService.record("重置房间二维码", "room", roomId,
                room.getRoomNo() + " 二维码已重置，旧二维码立即失效", operator);
        return room.getScanToken();
    }

    /** 确保某房间有令牌（页面按需生成），返回令牌 */
    @Transactional
    public String tokenOf(Room room) {
        if (room.getScanToken() != null && !room.getScanToken().isBlank()) {
            return room.getScanToken();
        }
        String token = uniqueToken();
        Room fresh = roomRepository.findById(room.getId()).orElseThrow();
        fresh.setScanToken(token);
        roomRepository.save(fresh);
        room.setScanToken(token);
        return token;
    }

    private String uniqueToken() {
        for (int i = 0; i < 5; i++) {
            String token = RandomToken.next();
            if (!roomRepository.existsByScanToken(token)) {
                return token;
            }
        }
        throw new BusinessException("二维码令牌生成失败，请重试");
    }

    // ------------------------------------------------------------------

    private ConsumableItem get(Long id) {
        return consumableItemRepository.findById(id)
                .orElseThrow(() -> new BusinessException("消费项目不存在"));
    }

    private String requireName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException("请填写品名");
        }
        return itemName.trim();
    }

    private BigDecimal requirePrice(BigDecimal unitPrice) {
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        if (price.signum() < 0) {
            throw new BusinessException("单价不能为负数");
        }
        return price.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
