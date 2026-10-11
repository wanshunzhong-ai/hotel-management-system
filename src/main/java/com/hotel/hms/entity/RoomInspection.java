package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 查房评分记录：四项各 0–25 分，总分 100。
 *
 * <h3>四项口径</h3>
 * <ul>
 *     <li>卫生（{@code scoreHygiene}）：地面、桌面、卫浴等清洁度</li>
 *     <li>设施（{@code scoreFacility}）：电器、灯具、门锁、水压等是否正常</li>
 *     <li>布草（{@code scoreLinen}）：床品、巾类是否洁净无破损</li>
 *     <li>客用品（{@code scoreAmenity}）：一次性用品、迷你吧、茶饮是否齐备</li>
 * </ul>
 *
 * <h3>不合格的联动</h3>
 * 总分 &lt; {@link InspectionResult#PASS_SCORE} 时：
 * 房间物理状态置「待清洁」（在住/维修房不动，避免把有客人的房间标成待清洁），
 * 并自动生成一张整改派工单，{@code rectifiedTaskId} 记录该派工单 id。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "room_inspection", indexes = {
        @Index(name = "idx_inspection_result", columnList = "result"),
        @Index(name = "idx_inspection_room", columnList = "room_id")
})
public class RoomInspection extends BaseEntity {

    @NotNull(message = "请选择房间")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /** 查房时该房间的营业订单；空房查房为空 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Min(value = 0, message = "卫生得分不能为负")
    @Max(value = 25, message = "卫生得分不能超过 25")
    @Column(name = "score_hygiene", nullable = false)
    private Integer scoreHygiene = 25;

    @Min(value = 0, message = "设施得分不能为负")
    @Max(value = 25, message = "设施得分不能超过 25")
    @Column(name = "score_facility", nullable = false)
    private Integer scoreFacility = 25;

    @Min(value = 0, message = "布草得分不能为负")
    @Max(value = 25, message = "布草得分不能超过 25")
    @Column(name = "score_linen", nullable = false)
    private Integer scoreLinen = 25;

    @Min(value = 0, message = "客用品得分不能为负")
    @Max(value = 25, message = "客用品得分不能超过 25")
    @Column(name = "score_amenity", nullable = false)
    private Integer scoreAmenity = 25;

    /** 总分 = 四项之和 */
    @Column(name = "total_score", nullable = false)
    private Integer totalScore = 100;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InspectionResult result = InspectionResult.PASS;

    /** 不合格时自动生成的整改派工单 */
    @Column(name = "rectified_task_id")
    private Long rectifiedTaskId;

    @Size(max = 500)
    @Column(length = 500)
    private String remark;

    /** 查房人 */
    @Column(length = 50)
    private String inspector;

    @Column(name = "inspected_at")
    private LocalDateTime inspectedAt;

    public int recalculate() {
        int total = nz(scoreHygiene) + nz(scoreFacility) + nz(scoreLinen) + nz(scoreAmenity);
        this.totalScore = total;
        this.result = InspectionResult.of(total);
        return total;
    }

    /** 等级文案：≥90 优秀 / ≥80 合格 / 其余不合格 */
    public String getGradeLabel() {
        int total = totalScore == null ? 0 : totalScore;
        if (total >= 90) {
            return "优秀";
        }
        if (total >= InspectionResult.PASS_SCORE) {
            return "合格";
        }
        return "不合格";
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
