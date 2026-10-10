package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.service.ReservationService;
import com.hotel.hms.service.RoomService;
import com.hotel.hms.service.RoomTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客房管理。
 */
@Controller
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final RoomTypeService roomTypeService;
    private final ReservationService reservationService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) RoomStatus status,
                       @RequestParam(required = false) Long roomTypeId,
                       @RequestParam(required = false) Integer floor,
                       Model model) {
        model.addAttribute("rooms", roomService.search(keyword, status, roomTypeId, floor));
        model.addAttribute("types", roomTypeService.findAll());
        model.addAttribute("floors", roomService.findAllFloors());
        model.addAttribute("statuses", RoomStatus.values());
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("roomTypeId", roomTypeId);
        model.addAttribute("floor", floor);
        model.addAttribute("active", "rooms");
        return "room/list";
    }

    /** 房态图（按楼层平铺） */
    @GetMapping("/board")
    public String board(Model model) {
        List<Room> rooms = roomService.findAll();
        Map<Integer, List<Room>> roomsByFloor = new LinkedHashMap<>();
        rooms.stream()
                .sorted(Comparator.comparing(Room::getFloor).thenComparing(Room::getRoomNo))
                .forEach(r -> roomsByFloor.computeIfAbsent(r.getFloor(), k -> new ArrayList<>()).add(r));

        model.addAttribute("roomsByFloor", roomsByFloor);
        model.addAttribute("rooms", rooms);
        model.addAttribute("types", roomTypeService.findAll());
        model.addAttribute("statuses", RoomStatus.values());
        model.addAttribute("active", "room-board");
        return "room/board";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        Room room = new Room();
        room.setStatus(RoomStatus.AVAILABLE);
        model.addAttribute("room", room);
        model.addAttribute("types", roomTypeService.findEnabled());
        model.addAttribute("statuses", RoomStatus.values());
        model.addAttribute("active", "rooms");
        return "room/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("room", roomService.getById(id));
        model.addAttribute("types", roomTypeService.findEnabled());
        model.addAttribute("statuses", RoomStatus.values());
        model.addAttribute("active", "rooms");
        return "room/form";
    }

    @PostMapping("/save")
    public String save(@Valid Room room, BindingResult binding, Model model, RedirectAttributes ra) {
        if (binding.hasErrors()) {
            model.addAttribute("types", roomTypeService.findEnabled());
            model.addAttribute("statuses", RoomStatus.values());
            model.addAttribute("active", "rooms");
            return "room/form";
        }
        try {
            if (room.getId() == null) {
                roomService.create(room);
                ra.addFlashAttribute("successMessage", "客房已新增");
            } else {
                roomService.update(room.getId(), room);
                ra.addFlashAttribute("successMessage", "客房已更新");
            }
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return room.getId() == null ? "redirect:/rooms/new" : "redirect:/rooms/" + room.getId() + "/edit";
        }
        return "redirect:/rooms";
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable Long id,
                               @RequestParam RoomStatus status,
                               @RequestParam(required = false) String redirectTo,
                               RedirectAttributes ra) {
        try {
            roomService.changeStatus(id, status);
            ra.addFlashAttribute("successMessage", "房态已更新为「" + status.getLabel() + "」");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : "/rooms");
    }

    @PostMapping("/{id}/clean")
    public String markClean(@PathVariable Long id,
                            @RequestParam(required = false) String redirectTo,
                            RedirectAttributes ra) {
        try {
            reservationService.markRoomClean(id);
            ra.addFlashAttribute("successMessage", "房间已完成清洁，恢复空闲");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : "/rooms/board");
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            roomService.delete(id);
            ra.addFlashAttribute("successMessage", "客房已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/rooms";
    }
}
