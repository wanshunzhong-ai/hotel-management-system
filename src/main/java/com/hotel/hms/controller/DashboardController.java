package com.hotel.hms.controller;

import com.hotel.hms.entity.Reservation;
import com.hotel.hms.service.DashboardService;
import com.hotel.hms.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.util.List;

/**
 * 首页看板。
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final ReservationService reservationService;

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("stats", dashboardService.build());
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("active", "dashboard");
        return "dashboard";
    }

    /**
     * 今日交接班名单打印视图（第三批 §3.7）。
     * <p>前台早班/晚班交接时直接打印这一页：今日预抵、今日预离、在住一览。
     */
    @GetMapping("/dashboard/print/today")
    public String printToday(Model model) {
        LocalDate today = LocalDate.now();
        List<Reservation> arrivals = reservationService.findArrivalsOn(today);
        List<Reservation> departures = reservationService.findDeparturesOn(today);
        List<Reservation> inHouse = reservationService.findInHouse();

        model.addAttribute("date", today);
        model.addAttribute("arrivals", arrivals);
        model.addAttribute("departures", departures);
        model.addAttribute("inHouse", inHouse);
        model.addAttribute("active", "dashboard");
        return "dashboard-print";
    }
}
