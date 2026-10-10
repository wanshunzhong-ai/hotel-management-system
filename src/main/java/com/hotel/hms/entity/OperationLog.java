package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 操作日志（第三批：审计留痕）。
 *
 * <p>记录「谁在何时改了什么」。酒店场景下最需要留痕的是会直接影响钱和可售性的动作：
 * 结账、反结账、折扣、房态变更、退房重算。覆盖这些写入点之后，
 * 出现账目争议时可以直接翻日志还原经过，而不是去翻数据库。
 *
 * <p>时间戳复用 {@link BaseEntity#getCreatedAt()}。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "operation_log",
        indexes = {
                @Index(name = "idx_oplog_created_at", columnList = "created_at"),
                @Index(name = "idx_oplog_action", columnList = "action"),
                @Index(name = "idx_oplog_target", columnList = "target_type, target_id")
        })
public class OperationLog extends BaseEntity {

    /** 动作名，如「办理入住」「结账」「反结账」 */
    @Column(nullable = false, length = 50)
    private String action;

    /** 对象类型：reservation / bill / room / customer / user */
    @Column(name = "target_type", length = 30)
    private String targetType;

    /** 对象主键 */
    @Column(name = "target_id")
    private Long targetId;

    /** 细节说明（含订单号、房号、金额等可核对信息） */
    @Column(length = 500)
    private String detail;

    /** 操作人姓名 */
    @Column(length = 50)
    private String operator;
}
