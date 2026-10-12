package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.dto.BoardState;
import com.hotel.hms.dto.RoomBoardCell;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.service.ReservationService;
import com.hotel.hms.service.RoomService;
import com.hotel.hms.service.RoomTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
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
        model.addAttribute("statuses", RoomStatus.physical());
        model.addAttribute("changeableStatuses", RoomStatus.changeable());
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("roomTypeId", roomTypeId);
        model.addAttribute("floor", floor);
        model.addAttribute("active", "rooms");
        return "room/list";
    }

    /** 房态图（按楼层平铺，支持指定日期查看该晚的可售情况） */
    @GetMapping("/board")
    public String board(@RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        Model model) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<RoomBoardCell> cells = roomService.boardOn(day);

        Map<Integer, List<RoomBoardCell>> cellsByFloor = new LinkedHashMap<>();
        cells.stream()
                .sorted(Comparator.comparing((RoomBoardCell c) -> c.room().getFloor())
                        .thenComparing(c -> c.room().getRoomNo()))
                .forEach(c -> cellsByFloor.computeIfAbsent(c.room().getFloor(), k -> new ArrayList<>()).add(c));

        long sellable = cells.stream().filter(RoomBoardCell::sellable).count();
        long sold = cells.size() - sellable;

        model.addAttribute("cellsByFloor", cellsByFloor);
        model.addAttribute("cells", cells);
        model.addAttribute("boardDate", day);
        model.addAttribute("isToday", day.equals(LocalDate.now()));
        model.addAttribute("canGoBack", day.isAfter(LocalDate.now()));
        model.addAttribute("sellableCount", sellable);
        model.addAttribute("soldCount", sold);
        model.addAttribute("boardStates", BoardState.displayOrder());
        model.addAttribute("statuses", RoomStatus.physical());
        model.addAttribute("changeableStatuses", RoomStatus.changeable());
        model.addAttribute("active", "room-board");
        return "room/board";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        Room room = new Room();
        room.setStatus(RoomStatus.AVAILABLE);
        model.addAttribute("room", room);
        model.addAttribute("types", roomTypeService.findEnabled());
        model.addAttribute("statuses", RoomStatus.physical());
        model.addAttribute("changeableStatuses", RoomStatus.changeable());
        model.addAttribute("active", "rooms");
        return "room/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("room", roomService.getById(id));
        model.addAttribute("types", roomTypeService.findEnabled());
        model.addAttribute("statuses", RoomStatus.physical());
        model.addAttribute("changeableStatuses", RoomStatus.changeable());
        model.addAttribute("active", "rooms");
        return "room/form";
    }

    /**
     * 新增 / 修改客房。
     *
     * <p>这里额外加动作级注解是<b>双保险</b>：URL 层已在 {@code SecurityConfig} 里拦了
     * {@code /rooms/save}，但 matcher 是顺序匹配的，日后有人调整顺序就可能重新漏掉——
     * 权限声明贴在被保护的方法上更不容易被改坏。
     */
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
    @PostMapping("/save")
    public String save(@Valid Room room, BindingResult binding, Model model, RedirectAttributes ra) {
        if (binding.hasErrors()) {
            model.addAttribute("types", roomTypeService.findEnabled());
            model.addAttribute("statuses", RoomStatus.physical());
            model.addAttribute("changeableStatuses", RoomStatus.changeable());
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
                               @AuthenticationPrincipal LoginUser loginUser,
                               RedirectAttributes ra) {
        try {
            roomService.changeStatus(id, status, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "房态已更新为「" + status.getLabel() + "」");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : "/rooms");
    }

    @PostMapping("/{id}/clean")
    public String markClean(@PathVariable Long id,
                            @RequestParam(required = false) String redirectTo,
                            @AuthenticationPrincipal LoginUser loginUser,
                            RedirectAttributes ra) {
        try {
            reservationService.markRoomClean(id, operatorOf(loginUser));
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

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? null : loginUser.getRealName();
    }
}
