package com.hotel.hms.repository;

import com.hotel.hms.entity.SupplyItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplyItemRepository extends JpaRepository<SupplyItem, Long> {

    List<SupplyItem> findAllByOrderByCategoryAscItemNameAsc();

    List<SupplyItem> findByEnabledTrueOrderByCategoryAscItemNameAsc();

    Optional<SupplyItem> findByItemName(String itemName);

    boolean existsByItemName(String itemName);

    /** 低于安全库存的品名数 */
    @Query("select count(s) from SupplyItem s where s.enabled = true and s.stock < s.minStock")
    long countBelowMin();
}
