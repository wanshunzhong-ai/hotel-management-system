package com.hotel.hms.repository;

import com.hotel.hms.entity.LostFound;
import com.hotel.hms.entity.LostFoundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LostFoundRepository extends JpaRepository<LostFound, Long> {

    List<LostFound> findByStatusOrderByIdDesc(LostFoundStatus status);

    long countByStatus(LostFoundStatus status);
}
