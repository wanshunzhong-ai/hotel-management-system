package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.dto.ReservationForm;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Room;
import com.hotel.hms.service.CustomerService;
import com.hotel.hms.service.ReservationService;
import com.hotel.hms.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预订单管理：预订、改单、取消、入住、退房。
 */
@Controller
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final CustomerService customerService;
    private final RoomService roomService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) ReservationStatus status,
                       Model model) {
        model.addAttribute("reservations", reservationService.search(keyword, status));
        model.addAttribute("statuses", ReservationStatus.values());
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("active", "reservations");
        return "reservation/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("reservation", reservationService.getById(id));
        model.addAttribute("active", "reservations");
        return "reservation/detail";
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(required = false) Long customerId, Model model) {
        ReservationForm form = new ReservationForm();
        form.setCheckInDate(LocalDate.now());
        form.setCheckOutDate(LocalDate.now().plusDays(1));
        form.setGuestCount(1);
        form.setCustomerId(customerId);
        model.addAttribute("form", form);
        prepareForm(model);
        model.addAttribute("active", "reservations");
        return "reservation/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Reservation reservation = reservationService.getById(id);
        ReservationForm form = new ReservationForm();
        form.setId(reservation.getId());
        form.setCustomerId(reservation.getCustomer().getId());
        form.setRoomId(reservation.getRoom().getId());
        form.setCheckInDate(reservation.getCheckInDate());
        form.setCheckOutDate(reservation.getCheckOutDate());
        form.setGuestCount(reservation.getGuestCount());
        form.setRemark(reservation.getRemark());
        model.addAttribute("form", form);
        model.addAttribute("reservation", reservation);
        prepareForm(model);
        model.addAttribute("active", "reservations");
        return "reservation/form";
    }

    @PostMapping("/save")
    public String save(@Valid ReservationForm form,
                       BindingResult binding,
                       @AuthenticationPrincipal LoginUser loginUser,
                       Model model,
                       RedirectAttributes ra) {
        if (binding.hasErrors()) {
            prepareForm(model);
            model.addAttribute("active", "reservations");
            return "reservation/form";
        }
        try {
            if (form.getRemark() != null && form.getRemark().isBlank()) {
                form.setRemark(null);
            }
            Reservation saved = (form.getId() == null)
                    ? reservationService.create(form, operatorOf(loginUser))
                    : reservationService.update(form.getId(), form, operatorOf(loginUser));
            if (form.getId() != null) {
                model.addAttribute("reservation", saved);
            }
            ra.addFlashAttribute("successMessage", "订单 " + saved.getOrderNo() + " 已保存");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return form.getId() == null ? "redirect:/reservations/new"
                    : "redirect:/reservations/" + form.getId() + "/edit";
        }
        return "redirect:/reservations";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        try {
            reservationService.cancel(id);
            ra.addFlashAttribute("successMessage", "订单已取消");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/reservations";
    }

    @PostMapping("/{id}/checkin")
    public String checkIn(@PathVariable Long id,
                          @AuthenticationPrincipal LoginUser loginUser,
                          RedirectAttributes ra) {
        try {
            reservationService.checkIn(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "入住办理成功，房间已转为「已入住」");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/reservations";
    }

    @PostMapping("/{id}/checkout")
    public String checkOut(@PathVariable Long id,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            Long billId = reservationService.checkOut(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "退房完成，账单已生成，请核对后结账");
            return "redirect:/bills/" + billId;
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/reservations";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            reservationService.delete(id);
            ra.addFlashAttribute("successMessage", "订单已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/reservations";
    }

    /** 按日期区间查询可预订房间（供预订表单动态刷新） */
    @GetMapping("/available-rooms")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> availableRooms(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {

        if (!checkOut.isAfter(checkIn)) {
            return ResponseEntity.ok(List.of());
        }
        List<Map<String, Object>> result = roomService.findAvailableBetween(checkIn, checkOut).stream()
                .map(this::toOption)
                .toList();
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toOption(Room room) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", room.getId());
        map.put("roomNo", room.getRoomNo());
        map.put("floor", room.getFloor());
        map.put("typeName", room.getRoomType().getName());
        map.put("bedType", room.getRoomType().getBedType());
        map.put("capacity", room.getRoomType().getCapacity());
        map.put("price", room.getRoomType().getPrice());
        return map;
    }

    private void prepareForm(Model model) {
        model.addAttribute("customers", customerService.search(null));
        model.addAttribute("rooms", roomService.findAll());
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
