package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.RoomType;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 房型管理。
 */
@Controller
@RequestMapping("/room-types")
@RequiredArgsConstructor
public class RoomTypeController {

    private final RoomTypeService roomTypeService;
    private final RoomService roomService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("types", roomTypeService.findAll());
        model.addAttribute("active", "room-types");
        return "roomtype/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("roomType", new RoomType());
        model.addAttribute("active", "room-types");
        return "roomtype/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("roomType", roomTypeService.getById(id));
        model.addAttribute("active", "room-types");
        return "roomtype/form";
    }

    @PostMapping("/save")
    public String save(@Valid RoomType roomType,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes ra) {
        if (binding.hasErrors()) {
            model.addAttribute("roomType", roomType);
            model.addAttribute("active", "room-types");
            return "roomtype/form";
        }
        try {
            if (roomType.getId() == null) {
                roomTypeService.create(roomType);
                ra.addFlashAttribute("successMessage", "房型已新增");
            } else {
                roomTypeService.update(roomType.getId(), roomType);
                ra.addFlashAttribute("successMessage", "房型已更新");
            }
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return roomType.getId() == null ? "redirect:/room-types/new" : "redirect:/room-types/" + roomType.getId() + "/edit";
        }
        return "redirect:/room-types";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            roomTypeService.delete(id);
            ra.addFlashAttribute("successMessage", "房型已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/room-types";
    }

    @GetMapping("/{id}/rooms")
    public String rooms(@PathVariable Long id, Model model) {
        model.addAttribute("roomType", roomTypeService.getById(id));
        model.addAttribute("rooms", roomService.findAll().stream()
                .filter(r -> r.getRoomType().getId().equals(id))
                .toList());
        model.addAttribute("active", "room-types");
        return "roomtype/rooms";
    }
}
