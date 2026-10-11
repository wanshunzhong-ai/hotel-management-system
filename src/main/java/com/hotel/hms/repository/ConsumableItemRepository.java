package com.hotel.hms.repository;

import com.hotel.hms.entity.ConsumableItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConsumableItemRepository extends JpaRepository<ConsumableItem, Long> {

    List<ConsumableItem> findAllByOrderByCategoryAscItemNameAsc();

    /** 扫码页给住客看的清单：只含启用中的条目 */
    List<ConsumableItem> findByEnabledTrueOrderByCategoryAscItemNameAsc();

    boolean existsByItemName(String itemName);

    long countByEnabledTrue();
}
