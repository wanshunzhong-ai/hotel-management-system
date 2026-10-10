package com.hotel.hms.repository;

import com.hotel.hms.entity.Customer;
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
