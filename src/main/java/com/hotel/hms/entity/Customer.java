package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 客户（住客）档案。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "customer", uniqueConstraints = @UniqueConstraint(name = "uk_customer_id_no", columnNames = "id_no"))
public class Customer extends BaseEntity {

    @NotBlank(message = "客户姓名不能为空")
    @Size(max = 50)
    @Column(nullable = false, length = 50)
    private String name;

    @NotNull(message = "请选择性别")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Gender gender = Gender.UNKNOWN;

    @NotNull(message = "请选择证件类型")
    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", nullable = false, length = 20)
    private IdType idType = IdType.ID_CARD;

    /** 证件号码 */
    @NotBlank(message = "证件号码不能为空")
    @Size(max = 40)
    @Column(name = "id_no", nullable = false, length = 40)
    private String idNo;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Column(nullable = false, length = 20)
    private String phone;

    @Size(max = 100)
    @Column(length = 100)
    private String email;

    @Size(max = 200)
    @Column(length = 200)
    private String address;

    /** 会员等级，如 普通 / 银卡 / 金卡 / 钻石 */
    @Size(max = 20)
    @Column(name = "member_level", length = 20)
    private String memberLevel = "普通";

    @Size(max = 200)
    @Column(length = 200)
    private String remark;

    /** 证件号脱敏展示 */
    public String getIdNoMasked() {
        if (idNo == null || idNo.length() < 6) {
            return idNo;
        }
        return idNo.substring(0, 3) + "***********" + idNo.substring(idNo.length() - 4);
    }
}
