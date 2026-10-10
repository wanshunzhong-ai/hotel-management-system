package com.hotel.hms.repository;

import com.hotel.hms.entity.Role;
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

    /** 指定角色的启用账号数（用于「必须保留至少一个管理员」校验，走 SQL 计数而非全表加载） */
    long countByRoleAndEnabledTrue(Role role);

    @Query("""
            select u from User u
            where (:keyword is null
                   or lower(u.username) like lower(concat('%', :keyword, '%'))
                   or lower(u.realName) like lower(concat('%', :keyword, '%')))
            order by u.id asc
            """)
    List<User> search(@Param("keyword") String keyword);
}
