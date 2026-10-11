package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.MaintenanceOrder;
import com.hotel.hms.entity.MaintenanceStatus;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.repository.MaintenanceOrderRepository;
import com.hotel.hms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 维修工单服务：报修 → 开工 → 完工 / 取消。
 *
 * <p>房态联动：空闲/待清洁的房间报修 → 自动置「维修中」；完工或取消 → 置「待清洁」。
 * 在住房间报修不改房态（客人还住着）。
 */
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final MaintenanceOrderRepository orderRepository;
    private final RoomRepository roomRepository;
    private final OperationLogService operationLogService;

    @Transactional(readOnly = true)
    public List<MaintenanceOrder> list(MaintenanceStatus status) {
        if (status != null) {
            return orderRepository.findByStatusOrderByIdDesc(status);
        }
        return orderRepository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "id"));
    }

    public long countByStatus(MaintenanceStatus status) {
        return orderRepository.countByStatus(status);
    }

    /** 报修 */
    @Transactional
    public Long report(Long roomId, String description, String operator) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("房间不存在"));
        if (description == null || description.isBlank()) {
            throw new BusinessException("请描述故障情况");
        }
        MaintenanceOrder order = new MaintenanceOrder();
        order.setRoom(room);
        order.setDescription(description.trim());
        order.setStatus(MaintenanceStatus.OPEN);
        order.setOperator(operator);
        MaintenanceOrder saved = orderRepository.save(order);

        boolean statusChanged = false;
        if (room.getStatus() == RoomStatus.AVAILABLE || room.getStatus() == RoomStatus.CLEANING) {
            room.setStatus(RoomStatus.MAINTENANCE);
            roomRepository.save(room);
            statusChanged = true;
        }
        operationLogService.record("报修", "maintenance_order", saved.getId(),
                room.getRoomNo() + "：" + saved.getDescription()
                        + (statusChanged ? "（房间已置维修中）" : "（在住房间，房态不变）"),
                operator);
        return saved.getId();
    }

    /** 开工 */
    @Transactional
    public void start(Long id, String handler, String operator) {
        MaintenanceOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException("维修工单不存在"));
        if (order.getStatus() != MaintenanceStatus.OPEN) {
            throw new BusinessException("仅「待处理」的工单可以开工");
        }
        order.setStatus(MaintenanceStatus.FIXING);
        order.setHandler(handler);
        order.setStartedAt(LocalDateTime.now());
        operationLogService.record("开工", "maintenance_order", id,
                order.getRoom().getRoomNo() + " 维修开工" + (handler == null || handler.isBlank() ? "" : "，维修人：" + handler),
                operator);
    }

    /** 完工 */
    @Transactional
    public void complete(Long id, String result, BigDecimal cost, String operator) {
        MaintenanceOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException("维修工单不存在"));
        if (order.getStatus() != MaintenanceStatus.OPEN && order.getStatus() != MaintenanceStatus.FIXING) {
            throw new BusinessException("仅未完工的工单可以完工");
        }
        order.setStatus(MaintenanceStatus.DONE);
        order.setResult(result);
        order.setCost(cost == null ? BigDecimal.ZERO : cost);
        order.setFinishedAt(LocalDateTime.now());
        Room room = order.getRoom();
        boolean statusChanged = false;
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            room.setStatus(RoomStatus.CLEANING);
            roomRepository.save(room);
            statusChanged = true;
        }
        operationLogService.record("维修完工", "maintenance_order", id,
                room.getRoomNo() + " 完工：" + (result == null || result.isBlank() ? "无备注" : result)
                        + (statusChanged ? "（房间已转待清洁）" : ""),
                operator);
    }

    /** 取消（误报 / 无需维修） */
    @Transactional
    public void cancel(Long id, String operator) {
        MaintenanceOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException("维修工单不存在"));
        if (order.getStatus() != MaintenanceStatus.OPEN && order.getStatus() != MaintenanceStatus.FIXING) {
            throw new BusinessException("仅未完工的工单可以取消");
        }
        order.setStatus(MaintenanceStatus.CANCELLED);
        order.setFinishedAt(LocalDateTime.now());
        Room room = order.getRoom();
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            room.setStatus(RoomStatus.CLEANING);
            roomRepository.save(room);
        }
        operationLogService.record("维修取消", "maintenance_order", id,
                room.getRoomNo() + " 工单取消", operator);
    }
}
