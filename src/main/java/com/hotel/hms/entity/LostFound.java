package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 失物招领：拾遗登记 → 客人认领。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "lost_found", indexes = @Index(name = "idx_lost_status", columnList = "status"))
public class LostFound extends BaseEntity {

    /** 物品名称 */
    @NotBlank(message = "请填写物品名称")
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    /** 物品描述 */
    @Size(max = 300)
    @Column(length = 300)
    private String description;

    /** 拾获地点 */
    @Size(max = 100)
    @Column(length = 100)
    private String location;

    /** 拾获人 */
    @Size(max = 50)
    @Column(length = 50)
    private String finder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LostFoundStatus status = LostFoundStatus.OPEN;

    /** 认领人 */
    @Size(max = 50)
    @Column(length = 50)
    private String claimant;

    /** 认领人联系方式 */
    @Size(max = 50)
    @Column(name = "claim_contact", length = 50)
    private String claimContact;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;
}
