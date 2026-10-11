package com.hotel.hms.repository;

import com.hotel.hms.entity.LinenStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LinenStockRepository extends JpaRepository<LinenStock, Long> {

    List<LinenStock> findAllByOrderByCategoryAscItemNameAsc();

    Optional<LinenStock> findByItemName(String itemName);

    boolean existsByItemName(String itemName);

    /** 低于安全库存的品名数（放在 SQL 里算，避免全表捞出后应用层过滤） */
    @Query("select count(s) from LinenStock s where s.inStock < s.minStock")
    long countBelowMin();
}
