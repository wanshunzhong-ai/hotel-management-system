package com.hotel.hms.repository;

import com.hotel.hms.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    List<User> findAllByOrderByIdAsc();

    long countByEnabledTrue();

    @Query("""
            select u from User u
            where (:keyword is null
                   or lower(u.username) like lower(concat('%', :keyword, '%'))
                   or lower(u.realName) like lower(concat('%', :keyword, '%')))
            order by u.id asc
            """)
    List<User> search(@Param("keyword") String keyword);
}
