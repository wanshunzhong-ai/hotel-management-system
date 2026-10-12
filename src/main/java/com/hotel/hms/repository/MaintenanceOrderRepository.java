package com.hotel.hms.repository;

import com.hotel.hms.entity.MaintenanceOrder;
import com.hotel.hms.entity.MaintenanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface MaintenanceOrderRepository extends JpaRepository<MaintenanceOrder, Long> {

    List<MaintenanceOrder> findByStatusOrderByIdDesc(MaintenanceStatus status);

    long countByStatus(MaintenanceStatus status);

    /** 待办中心：未完工且报修时间早于阈值的工单数（积压提醒） */
    @Query("select count(m) from MaintenanceOrder m where m.status in :statuses and m.createdAt < :before")
    long countOverdue(@Param("statuses") Collection<MaintenanceStatus> statuses,
                      @Param("before") LocalDateTime before);
}
