package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 系统用户（员工账号）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_user", uniqueConstraints = @UniqueConstraint(name = "uk_user_username", columnNames = "username"))
public class User extends BaseEntity {

    /** 登录账号 */
    @NotBlank(message = "登录账号不能为空")
    @Size(max = 50, message = "登录账号不超过 50 个字符")
    @Column(nullable = false, length = 50)
    private String username;

    /** BCrypt 加密后的密码 */
    @Column(nullable = false, length = 100)
    private String password;

    /** 真实姓名 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名不超过 50 个字符")
    @Column(name = "real_name", nullable = false, length = 50)
    private String realName;

    /** 角色 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.RECEPTION;

    @Size(max = 20)
    @Column(length = 20)
    private String phone;

    /** 是否启用 */
    @Column(nullable = false)
    private Boolean enabled = true;

    @Size(max = 200)
    @Column(length = 200)
    private String remark;

    /** Spring Security 使用的权限串 */
    public String getAuthority() {
        return "ROLE_" + role.name();
    }
}
