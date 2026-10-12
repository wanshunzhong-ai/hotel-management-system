package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.ConsumableCategory;
import com.hotel.hms.entity.Room;
import com.hotel.hms.service.ConsumableService;
import com.hotel.hms.service.QrCodeService;
import com.hotel.hms.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客房扫码消费：消费项目目录维护 + 房间二维码发放。
 *
 * <p>二维码内容指向免登录页 {@code /scan/{token}}，
 * 因此这里只做「发码 / 重置」，真正的入账校验在扫码页那条链路上。
 */
@Controller
@RequestMapping("/consumables")
@RequiredArgsConstructor
public class ConsumableController {

    private final ConsumableService consumableService;
    private final QrCodeService qrCodeService;
    private final RoomService roomService;

    @GetMapping
    public String page(Model model) {
        Map<Long, String> qrUrls = new LinkedHashMap<>();
        Map<Long, String> staffUrls = new LinkedHashMap<>();
        for (Room room : roomService.findAll()) {
            String token = consumableService.tokenOf(room);
            qrUrls.put(room.getId(), scanUrl(token));
            staffUrls.put(room.getId(), staffScanUrl(token));
        }
        model.addAttribute("items", consumableService.list());
        model.addAttribute("categories", ConsumableCategory.values());
        model.addAttribute("rooms", roomService.findAll());
        model.addAttribute("qrUrls", qrUrls);
        model.addAttribute("staffUrls", staffUrls);
        model.addAttribute("enabledCount", consumableService.countEnabled());
        model.addAttribute("active", "consumables");
        return "consumables/list";
    }

    // ---------------- 目录 ----------------

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
    public String create(@RequestParam String itemName,
                         @RequestParam(required = false) ConsumableCategory category,
                         @RequestParam(required = false) BigDecimal unitPrice,
                         @RequestParam(required = false) String note,
                         @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes ra) {
        try {
            consumableService.create(itemName, category, unitPrice, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "消费项目已新增");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/consumables";
    }

    @PostMapping("/{id}/update")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) ConsumableCategory category,
                         @RequestParam(required = false) BigDecimal unitPrice,
                         @RequestParam(required = false) Boolean enabled,
                         @RequestParam(required = false) String note,
                         @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes ra) {
        try {
            consumableService.update(id, category, unitPrice, enabled, note, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "消费项目已更新");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/consumables";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes ra) {
        try {
            consumableService.delete(id, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "消费项目已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/consumables";
    }

    // ---------------- 二维码 ----------------

    /** 重置二维码：旧码立即失效（打印在门牌上的旧码作废） */
    @PostMapping("/rooms/{roomId}/reset-token")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTION')")
    public String resetToken(@PathVariable Long roomId,
                             @AuthenticationPrincipal LoginUser loginUser, RedirectAttributes ra) {
        try {
            consumableService.resetRoomToken(roomId, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "二维码已重置，旧二维码立即失效");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/consumables";
    }

    /** 二维码图片（服务端渲染 PNG） */
    @GetMapping("/rooms/{roomId}/qr.png")
    public ResponseEntity<byte[]> qr(@PathVariable Long roomId,
                                     @RequestParam(defaultValue = "320") int size) {
        Room room = roomService.getById(roomId);
        String token = consumableService.tokenOf(room);
        byte[] png = qrCodeService.png(scanUrl(token), size);
        // 令牌可重置，因此不让浏览器缓存
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    /**
     * 员工码（第七批）：同一把房间令牌，但指向<b>需登录</b>的客房部操作台 {@code /hk/scan/{token}}。
     *
     * <p>与对客二维码的区别只在落地页：住客码进免登录点单页，员工码进需登录的现场操作台。
     * 令牌本身共用，因此重置一次，两张码同时失效——不会再出现「客人那页作废了、员工那页还能用」。
     */
    @GetMapping("/rooms/{roomId}/staff-qr.png")
    public ResponseEntity<byte[]> staffQr(@PathVariable Long roomId,
                                          @RequestParam(defaultValue = "240") int size) {
        Room room = roomService.getById(roomId);
        String token = consumableService.tokenOf(room);
        byte[] png = qrCodeService.png(staffScanUrl(token), size);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    // ---------------- 内部 ----------------

    private String scanUrl(String token) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/scan/" + token)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private String staffScanUrl(String token) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/hk/scan/" + token)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
