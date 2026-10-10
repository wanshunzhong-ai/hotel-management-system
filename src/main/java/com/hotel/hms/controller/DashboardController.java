package com.hotel.hms.controller;

import com.hotel.hms.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;

/**
 * 首页看板。
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("stats", dashboardService.build());
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("active", "dashboard");
        return "dashboard";
    }
}
