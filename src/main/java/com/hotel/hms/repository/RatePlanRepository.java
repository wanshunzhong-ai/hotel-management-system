package com.hotel.hms.repository;

import com.hotel.hms.entity.RatePlan;
import com.hotel.hms.entity.RatePlanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 房价方案仓库（第六批：协议价 / 会员价）。
 */
@Repository
public interface RatePlanRepository extends JpaRepository<RatePlan, Long> {

    boolean existsByCode(String code);

    /** 同编码但不含自身（编辑时的唯一性校验） */
    boolean existsByCodeAndIdNot(String code, Long id);

    List<RatePlan> findAllByOrderByIdAsc();

    List<RatePlan> findByEnabledTrueOrderByIdAsc();

    List<RatePlan> findByTypeAndEnabledTrueOrderByIdAsc(RatePlanType type);

    /** 按会员等级匹配启用的会员价方案 */
    Optional<RatePlan> findFirstByTypeAndMemberLevelAndEnabledTrue(RatePlanType type, String memberLevel);

    long countByEnabledTrue();
}
