package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.MinibarStatus;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.entity.TaskStatus;
import com.hotel.hms.service.ConsumableService;
import com.hotel.hms.service.HousekeepingService;
import com.hotel.hms.service.LostFoundService;
import com.hotel.hms.service.MaintenanceService;
import com.hotel.hms.service.MinibarService;
import com.hotel.hms.service.ReservationService;
import com.hotel.hms.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客房部现场操作台（第七批）——给手机用的「扫码即到岗」入口。
 *
 * <h3>为什么要有这一页</h3>
 * 客房部在房间里发现马桶漏水、或捡到客人落下的充电器时，过去必须走回楼层电脑
 * 打开 {@code /maintenance} 或 {@code /lost-found} 再从头选房——现场作业效率很低。
 * 这里用房间二维码当入口：扫一下（或手输房号）直接落到这间房的快捷动作台，
 * 报修、登记失物、挂账、改房态都在一个页面上，按钮按拇指大小排布。
 *
 * <h3>安全边界（重要）</h3>
 * 与住客扫码页 {@code /scan/**} 是<b>两条完全不同的路径</b>：
 * <ul>
 *     <li>{@code /scan/**} 免登录，只能给住客挂账，且必须房间有在住订单；</li>
 *     <li>{@code /hk/**} 必须登录，且 {@code SecurityConfig} 把它限给客房相关角色——
 *         否则任何人都能用房间二维码替客房部报修、登记失物，现场数据就脏了。</li>
 * </ul>
 */
@Controller
@RequestMapping("/hk")
@RequiredArgsConstructor
public class StaffScanController {

    private final RoomService roomService;
    private final ConsumableService consumableService;
    private final ReservationService reservationService;
    private final HousekeepingService housekeepingService;
    private final MaintenanceService maintenanceService;
    private final LostFoundService lostFoundService;
    private final MinibarService minibarService;

    /** 入口页：手输房号 + 房间宫格 */
    @GetMapping
    public String entry(@RequestParam(required = false) String roomNo,
                        @AuthenticationPrincipal LoginUser loginUser,
                        Model model) {
        if (roomNo != null && !roomNo.isBlank()) {
            Room room = roomService.findAll().stream()
                    .filter(r -> roomNo.trim().equalsIgnoreCase(r.getRoomNo()))
                    .findFirst()
                    .orElse(null);
            if (room != null) {
                return "redirect:/hk/room/" + room.getId();
            }
            model.addAttribute("errorMessage", "没有找到房号「" + roomNo.trim() + "」");
        }
        model.addAttribute("rooms", roomService.findAll());
        model.addAttribute("myTasks", loginUser == null ? List.of()
                : housekeepingService.list(null, loginUser.getId()).stream()
                        .filter(t -> t.getStatus() == TaskStatus.PENDING
                                || t.getStatus() == TaskStatus.REJECTED)
                        .toList());
        model.addAttribute("active", "hk");
        return "housekeeping/room-entry";
    }

    /** 扫房间二维码直达该房操作台 */
    @GetMapping("/scan/{token}")
    public String byToken(@PathVariable String token, RedirectAttributes ra) {
        return consumableService.roomByToken(token)
                .map(room -> "redirect:/hk/room/" + room.getId())
                .orElseGet(() -> {
                    ra.addFlashAttribute("errorMessage", "二维码无效，请重新扫描或手输房号");
                    return "redirect:/hk";
                });
    }

    /** 房间操作台 */
    @GetMapping("/room/{id}")
    public String console(@PathVariable Long id,
                          @AuthenticationPrincipal LoginUser loginUser,
                          Model model) {
        Room room = roomService.getById(id);
        List<Reservation> inHouse = reservationService.findInHouse().stream()
                .filter(r -> r.getRoom() != null && r.getRoom().getId().equals(id))
                .toList();
        model.addAttribute("room", room);
        model.addAttribute("inHouse", inHouse.isEmpty() ? null : inHouse.get(0));
        model.addAttribute("catalog", consumableService.enabledList());
        // 快捷切换只提供「变更房态」允许写入的状态——「已入住」由办理入住产生（第八批缺口 6）
        model.addAttribute("physicalStatuses", RoomStatus.changeable());
        model.addAttribute("token", consumableService.tokenOf(room));
        model.addAttribute("myTasks", loginUser == null ? List.of()
                : housekeepingService.list(null, loginUser.getId()).stream()
                        .filter(t -> t.getRoom().getId().equals(id))
                        .filter(t -> t.getStatus() == TaskStatus.PENDING
                                || t.getStatus() == TaskStatus.REJECTED)
                        .toList());
        model.addAttribute("pendingItems", minibarService.list(MinibarStatus.PENDING).stream()
                .filter(m -> m.getRoom() != null && m.getRoom().getId().equals(id))
                .toList());
        model.addAttribute("active", "hk");
        return "housekeeping/room-console";
    }

    // ---------- 快捷动作：全部回到本房操作台，不打断现场作业 ----------

    @PostMapping("/room/{id}/report")
    public String report(@PathVariable Long id,
                         @RequestParam String description,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            maintenanceService.report(id, description, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "报修已提交，房态已同步");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/hk/room/" + id;
    }

    @PostMapping("/room/{id}/found")
    public String found(@PathVariable Long id,
                        @RequestParam String itemName,
                        @RequestParam(required = false) String description,
                        @AuthenticationPrincipal LoginUser loginUser,
                        RedirectAttributes ra) {
        try {
            Room room = roomService.getById(id);
            String finder = operatorOf(loginUser);
            lostFoundService.register(itemName, description, room.getRoomNo(), finder, finder);
            ra.addFlashAttribute("successMessage", "失物已登记：" + room.getRoomNo());
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/hk/room/" + id;
    }

    @PostMapping("/room/{id}/minibar")
    public String minibar(@PathVariable Long id,
                          @RequestParam String itemName,
                          @RequestParam(required = false) Integer quantity,
                          @RequestParam(required = false) BigDecimal unitPrice,
                          @AuthenticationPrincipal LoginUser loginUser,
                          RedirectAttributes ra) {
        try {
            minibarService.create(id, itemName, quantity, unitPrice, "现场扫码挂账", operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已挂账，退房时自动并入账单");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/hk/room/" + id;
    }

    @PostMapping("/room/{id}/status")
    public String status(@PathVariable Long id,
                         @RequestParam RoomStatus status,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            roomService.changeStatus(id, status, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "房态已更新为「" + status.getLabel() + "」");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/hk/room/" + id;
    }

    /** 移动端一键标记本房最新的待完成工单为完成（需检查单已全勾） */
    @PostMapping("/room/{id}/task-done")
    public String taskDone(@PathVariable Long id,
                           @RequestParam Long taskId,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            boolean canOperateOthers = loginUser != null && (loginUser.isAdmin()
                    || loginUser.getRole() == com.hotel.hms.entity.Role.RECEPTION
                    || loginUser.getRole() == com.hotel.hms.entity.Role.HOUSEKEEPING_SUPERVISOR);
            housekeepingService.markDone(taskId, loginUser.getId(), canOperateOthers, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "已标记完成，等待验收");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/hk/room/" + id;
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
