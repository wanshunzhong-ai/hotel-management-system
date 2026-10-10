package com.hotel.hms.repository;

import com.hotel.hms.entity.RoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    List<RoomType> findAllByOrderByPriceAsc();

    List<RoomType> findByEnabledTrueOrderByPriceAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
