package com.hotel.hms.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 预订单表单。
 */
@Getter
@Setter
public class ReservationForm {

    /** 编辑时为订单 id，新建时为空 */
    private Long id;

    @NotNull(message = "请选择客户")
    private Long customerId;

    @NotNull(message = "请选择房间")
    private Long roomId;

    @NotNull(message = "请选择入住日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkInDate;

    @NotNull(message = "请选择离店日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkOutDate;

    @NotNull(message = "请填写入住人数")
    @Min(value = 1, message = "入住人数至少 1 人")
    private Integer guestCount = 1;

    private String remark;
}
