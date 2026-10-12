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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 客房部排班：某天某个班次由谁当值。
 *
 * <p>唯一约束定在（日期 + 班次 + 人员）上——同一个人同一天同一班次排两次没有意义，
 * 但一个人可以在同一天排两个班次（加班），不同人当然可以同班。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "duty_schedule",
        uniqueConstraints = @UniqueConstraint(name = "uk_duty_date_shift_staff",
                columnNames = {"work_date", "shift", "staff_id"}),
        indexes = {
                @Index(name = "idx_duty_date", columnList = "work_date"),
                @Index(name = "idx_duty_staff", columnList = "staff_id")
        })
public class DutySchedule extends BaseEntity {

    @NotNull(message = "请选择排班日期")
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DayShift shift = DayShift.MORNING;

    /** 当值人员（客房服务员或客房主管） */
    @NotNull(message = "请选择当值人员")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private User staff;

    @Size(max = 200)
    @Column(length = 200)
    private String remark;

    /** 排班操作人 */
    @Column(length = 50)
    private String operator;
}
