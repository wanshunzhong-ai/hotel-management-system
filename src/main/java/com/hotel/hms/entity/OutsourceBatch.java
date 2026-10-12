package com.hotel.hms.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 布草外送洗涤批次（与洗涤公司的交接单）。
 *
 * <p>解决「外包环节无账」：过去布草领用只把数量从在库挪到洗涤中，
 * 但没人知道这批布草是送哪家洗的、洗了几件、多少钱、结没结。
 * 批次单把这些一次记清，并把数量流转与库存三态打通：
 * 送出时在库 → 洗涤中，收回时洗涤中 → 在库。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "outsource_batch",
        uniqueConstraints = @UniqueConstraint(name = "uk_outsource_no", columnNames = "batch_no"),
        indexes = @Index(name = "idx_outsource_status", columnList = "status"))
public class OutsourceBatch extends BaseEntity {

    /** 批次号，如 WX20261011-001 */
    @NotBlank
    @Size(max = 40)
    @Column(name = "batch_no", nullable = false, length = 40)
    private String batchNo;

    /** 洗涤公司 */
    @Size(max = 100)
    @Column(length = 100)
    private String vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutsourceStatus status = OutsourceStatus.SENT;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** 送出总件数 */
    @Column(name = "total_qty", nullable = false)
    private Integer totalQty = 0;

    /** 结算总金额 */
    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Size(max = 200)
    @Column(length = 200)
    private String remark;

    @Column(length = 50)
    private String operator;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OutsourceItem> items = new ArrayList<>();

    public void addItem(OutsourceItem item) {
        item.setBatch(this);
        this.items.add(item);
    }

    /** 未收回 / 未结算的批次不能再改明细 */
    public boolean isEditable() {
        return status == OutsourceStatus.SENT;
    }
}
