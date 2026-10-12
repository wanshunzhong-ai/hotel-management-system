package com.hotel.hms.repository;

import com.hotel.hms.entity.Invoice;
import com.hotel.hms.entity.InvoiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发票仓库（第六批）。
 */
@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    boolean existsByInvoiceNo(String invoiceNo);

    List<Invoice> findByBillIdAndCancelledFalseOrderByIdDesc(Long billId);

    List<Invoice> findByBillIdOrderByIdDesc(Long billId);

    /** 账单已开票金额（不含作废），用于「累计开票不得超过应收」的校验 */
    @Query("""
            select coalesce(sum(i.amount), 0) from Invoice i
            where i.bill.id = :billId and i.cancelled = false
            """)
    BigDecimal sumIssuedAmount(@Param("billId") Long billId);

    /** 发票查询（分页 + 多条件筛选） */
    @Query("""
            select i from Invoice i
            where (:keyword is null
                   or i.invoiceNo like concat('%', :keyword, '%')
                   or i.title like concat('%', :keyword, '%')
                   or i.taxNo like concat('%', :keyword, '%')
                   or i.bill.billNo like concat('%', :keyword, '%'))
              and (:type is null or i.type = :type)
              and (:includeCancelled = true or i.cancelled = false)
            """)
    Page<Invoice> search(@Param("keyword") String keyword,
                         @Param("type") InvoiceType type,
                         @Param("includeCancelled") boolean includeCancelled,
                         Pageable pageable);
}
