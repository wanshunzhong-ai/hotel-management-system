package com.hotel.hms.repository;

import com.hotel.hms.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByIdNo(String idNo);

    boolean existsByIdNo(String idNo);

    boolean existsByIdNoAndIdNot(String idNo, Long id);

    List<Customer> findAllByOrderByIdDesc();

    /** 客户选择器在无关键词时展示的「最近建档」几条 */
    List<Customer> findTop10ByOrderByIdDesc();

    @Query("""
            select c from Customer c
            where (:keyword is null
                   or c.name like concat('%', :keyword, '%')
                   or c.phone like concat('%', :keyword, '%')
                   or c.idNo like concat('%', :keyword, '%'))
            """)
    Page<Customer> searchPage(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 预订页客户选择器的远程搜索。
     *
     * <p>排序刻意做成「手机号前缀命中 &gt; 姓名前缀命中 &gt; 其它模糊命中」：
     * 前台接电话时拿到的是手机号，输入前几位就能把本人顶到第一条，
     * 比单纯按 id 倒序找得快。证件号只做前缀匹配（输入末位没意义）。
     *
     * <p>返回条数用 {@link Pageable} 限制（调用方固定取前 20 条），避免全量回传。
     */
    @Query("""
            select c from Customer c
            where c.name like concat('%', :keyword, '%')
               or c.phone like concat('%', :keyword, '%')
               or c.idNo like concat(:keyword, '%')
            order by case
                         when c.phone like concat(:keyword, '%') then 0
                         when c.name like concat(:keyword, '%') then 1
                         else 2
                     end,
                     c.id desc
            """)
    List<Customer> searchForPicker(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
            select c from Customer c
            where (:keyword is null
                   or c.name like concat('%', :keyword, '%')
                   or c.phone like concat('%', :keyword, '%')
                   or c.idNo like concat('%', :keyword, '%'))
            order by c.id desc
            """)
    List<Customer> search(@Param("keyword") String keyword);
}
