package com.hotel.hms.repository;

import com.hotel.hms.entity.MinibarItem;
import com.hotel.hms.entity.MinibarStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MinibarItemRepository extends JpaRepository<MinibarItem, Long> {

    List<MinibarItem> findByStatusOrderByIdDesc(MinibarStatus status);

    List<MinibarItem> findByReservationIdAndStatusOrderByIdAsc(Long reservationId, MinibarStatus status);

    long countByStatus(MinibarStatus status);
}
