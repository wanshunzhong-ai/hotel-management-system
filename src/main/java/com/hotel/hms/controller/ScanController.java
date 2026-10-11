package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.ChargeSource;
import com.hotel.hms.entity.ConsumableItem;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Room;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.service.ConsumableService;
import com.hotel.hms.service.MinibarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

/**
 * 住客扫码下单（<b>免登录</b>）。
 *
 * <h3>安全边界</h3>
 * <ul>
 *     <li>入口靠 32 位 Base62 随机令牌（约 190 bit 熵）{@code /scan/{token}}，令牌即钥匙，不可枚举；</li>
 *     <li>免登录不等于任意下单：房间必须<b>真的有在住订单</b>，
 *         否则 {@link MinibarService#create} 会直接拒绝；</li>
 *     <li>下单只产生「待入账」挂账，前台在迷你吧页可见、可取消，
 *         退房时才真正并入账单——住客无法直接改账单金额。</li>
 * </ul>
 */
@Controller
@RequestMapping("/scan")
@RequiredArgsConstructor
public class ScanController {

    private final ConsumableService consumableService;
    private final MinibarService minibarService;
    private final ReservationRepository reservationRepository;

    @GetMapping("/{token}")
    public String page(@PathVariable String token,
                       @RequestParam(required = false) String ok,
                       Model model) {
        Optional<Room> found = consumableService.roomByToken(token);
        if (found.isEmpty()) {
            model.addAttribute("invalidToken", true);
            return "scan/index";
        }
        Room room = found.get();
        List<Reservation> inHouse = reservationRepository
                .findByRoomIdAndStatusIn(room.getId(), List.of(ReservationStatus.CHECKED_IN));

        model.addAttribute("room", room);
        model.addAttribute("token", token);
        model.addAttribute("inHouse", !inHouse.isEmpty());
        model.addAttribute("guestName", inHouse.isEmpty() ? null : inHouse.get(0).getCustomer().getName());
        model.addAttribute("orderNo", inHouse.isEmpty() ? null : inHouse.get(0).getOrderNo());
        model.addAttribute("ok", ok != null);
        // 扫码页只展示启用中的项目，价格即目录价
        List<ConsumableItem> items = consumableService.enabledList();
        model.addAttribute("items", items);
        return "scan/index";
    }

    @PostMapping("/{token}")
    public String submit(@PathVariable String token,
                         @RequestParam Long itemId,
                         @RequestParam(required = false, defaultValue = "1") Integer quantity,
                         RedirectAttributes ra) {
        Optional<Room> found = consumableService.roomByToken(token);
        if (found.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "二维码无效，请重新扫描房间内的二维码");
            return "redirect:/scan/" + token;
        }
        Optional<ConsumableItem> item = consumableService.enabledList().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst();
        if (item.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "所选商品已下架，请重新选择");
            return "redirect:/scan/" + token;
        }
        int qty = quantity == null || quantity < 1 ? 1 : Math.min(quantity, 99);
        try {
            minibarService.create(found.get().getId(), item.get().getItemName(), qty,
                    item.get().getUnitPrice(), "住客扫码下单", "住客扫码", ChargeSource.SCAN);
            ra.addFlashAttribute("successMessage",
                    item.get().getItemName() + " × " + qty + " 已下单，退房时一并结算");
            return "redirect:/scan/" + token + "?ok=1";
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/scan/" + token;
        }
    }
}
