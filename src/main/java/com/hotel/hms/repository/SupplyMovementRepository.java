package com.hotel.hms.repository;

import com.hotel.hms.entity.SupplyMovement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupplyMovementRepository extends JpaRepository<SupplyMovement, Long> {

    List<SupplyMovement> findAllByOrderByIdDesc(Pageable pageable);

    List<SupplyMovement> findByItemIdOrderByIdDesc(Long itemId);

    long countByItemId(Long itemId);
}
