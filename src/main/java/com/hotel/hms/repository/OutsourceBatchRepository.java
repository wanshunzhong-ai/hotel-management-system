package com.hotel.hms.repository;

import com.hotel.hms.entity.OutsourceBatch;
import com.hotel.hms.entity.OutsourceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OutsourceBatchRepository extends JpaRepository<OutsourceBatch, Long> {

    List<OutsourceBatch> findAllByOrderByIdDesc();

    List<OutsourceBatch> findByStatusOrderByIdDesc(OutsourceStatus status);

    /**
     * 交接单详情查询，一次性抓取洗涤明细。
     *
     * <p>{@code items} 是 LAZY 集合，而本项目显式关闭了 OSIV（{@code open-in-view: false}），
     * 事务外拿到的是未初始化的代理；打印页模板一迭代就会
     * {@code LazyInitializationException}（页面直接 500）。
     * 与 {@code BillRepository#findDetailById} 同一范式，在查询里就把明细取出来。
     */
    @Query("select distinct b from OutsourceBatch b left join fetch b.items where b.id = :id")
    Optional<OutsourceBatch> findDetailById(@Param("id") Long id);

    boolean existsByBatchNo(String batchNo);

    long countByStatus(OutsourceStatus status);
}
