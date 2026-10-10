package com.hotel.hms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;

/**
 * 向所有视图注入公共变量。
 */
@ControllerAdvice
public class GlobalModelAttributes {

    @Value("${hotel.name:云栖大酒店}")
    private String hotelName;

    @ModelAttribute("hotelName")
    public String hotelName() {
        return hotelName;
    }

    @ModelAttribute("today")
    public LocalDate today() {
        return LocalDate.now();
    }
}
