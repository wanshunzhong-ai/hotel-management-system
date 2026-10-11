package com.hotel.hms.repository;

import com.hotel.hms.entity.LaundryOrder;
import com.hotel.hms.entity.LaundryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LaundryOrderRepository extends JpaRepository<LaundryOrder, Long> {

    List<LaundryOrder> findByStatusOrderByIdDesc(LaundryStatus status);

    long countByStatus(LaundryStatus status);

    /** 退房并入账单用：某订单下「已收件 / 洗涤中」的客衣单（不含已送回、已取消） */
    List<LaundryOrder> findByReservationIdAndStatusInOrderByIdAsc(Long reservationId, List<LaundryStatus> statuses);

    boolean existsByRoomIdAndStatusIn(Long roomId, List<LaundryStatus> statuses);
}
