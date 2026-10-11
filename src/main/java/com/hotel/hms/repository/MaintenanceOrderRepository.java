package com.hotel.hms.repository;

import com.hotel.hms.entity.MaintenanceOrder;
import com.hotel.hms.entity.MaintenanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaintenanceOrderRepository extends JpaRepository<MaintenanceOrder, Long> {

    List<MaintenanceOrder> findByStatusOrderByIdDesc(MaintenanceStatus status);

    long countByStatus(MaintenanceStatus status);
}
