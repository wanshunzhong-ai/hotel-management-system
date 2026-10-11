package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.LostFound;
import com.hotel.hms.entity.LostFoundStatus;
import com.hotel.hms.repository.LostFoundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 失物招领服务：拾遗登记 → 客人认领。
 */
@Service
@RequiredArgsConstructor
public class LostFoundService {

    private final LostFoundRepository lostFoundRepository;
    private final OperationLogService operationLogService;

    @Transactional(readOnly = true)
    public List<LostFound> list(LostFoundStatus status, String keyword) {
        List<LostFound> source = status == null
                ? lostFoundRepository.findAll(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "id"))
                : lostFoundRepository.findByStatusOrderByIdDesc(status);
        if (keyword == null || keyword.isBlank()) {
            return source;
        }
        String kw = keyword.trim().toLowerCase();
        return source.stream()
                .filter(f -> (f.getItemName() != null && f.getItemName().toLowerCase().contains(kw))
                        || (f.getDescription() != null && f.getDescription().toLowerCase().contains(kw))
                        || (f.getLocation() != null && f.getLocation().toLowerCase().contains(kw))
                        || (f.getClaimant() != null && f.getClaimant().toLowerCase().contains(kw)))
                .toList();
    }

    public long countByStatus(LostFoundStatus status) {
        return lostFoundRepository.countByStatus(status);
    }

    /** 拾遗登记 */
    @Transactional
    public Long register(String itemName, String description, String location,
                         String finder, String operator) {
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException("请填写物品名称");
        }
        LostFound found = new LostFound();
        found.setItemName(itemName.trim());
        found.setDescription(description);
        found.setLocation(location);
        found.setFinder(finder);
        found.setStatus(LostFoundStatus.OPEN);
        LostFound saved = lostFoundRepository.save(found);
        operationLogService.record("失物登记", "lost_found", saved.getId(),
                saved.getItemName() + (location == null || location.isBlank() ? "" : "，拾于" + location),
                operator);
        return saved.getId();
    }

    /** 认领 */
    @Transactional
    public void claim(Long id, String claimant, String claimContact, String operator) {
        if (claimant == null || claimant.isBlank()) {
            throw new BusinessException("请填写认领人");
        }
        LostFound found = lostFoundRepository.findById(id)
                .orElseThrow(() -> new BusinessException("失物记录不存在"));
        if (found.getStatus() != LostFoundStatus.OPEN) {
            throw new BusinessException("该物品已被认领");
        }
        found.setStatus(LostFoundStatus.CLAIMED);
        found.setClaimant(claimant.trim());
        found.setClaimContact(claimContact);
        found.setClaimedAt(LocalDateTime.now());
        operationLogService.record("失物认领", "lost_found", id,
                found.getItemName() + " 被认领，认领人：" + found.getClaimant(), operator);
    }
}
