package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.common.CsvExport;
import com.hotel.hms.common.Pagination;
import com.hotel.hms.config.BookingPolicy;
import com.hotel.hms.config.LoginUser;
import com.hotel.hms.dto.CustomerOption;
import com.hotel.hms.dto.ReservationForm;
import com.hotel.hms.entity.Gender;
import com.hotel.hms.entity.IdType;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Room;
import com.hotel.hms.service.CustomerService;
import com.hotel.hms.service.ReservationService;
import com.hotel.hms.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 排序白名单：键 → Sort。页面下拉的选项由此生成，新增排序只需在这里加一行。 */
    private static final Map<String, Sort> SORTS = new LinkedHashMap<>();

    static {
        SORTS.put("latest", Sort.by(Sort.Direction.DESC, "id"));
        SORTS.put("checkInAsc", Sort.by(Sort.Direction.ASC, "checkInDate").and(Sort.by(Sort.Direction.ASC, "id")));
        SORTS.put("checkInDesc", Sort.by(Sort.Direction.DESC, "checkInDate").and(Sort.by(Sort.Direction.DESC, "id")));
        SORTS.put("roomNoAsc", Sort.by(Sort.Direction.ASC, "room.roomNo").and(Sort.by(Sort.Direction.ASC, "id")));
    }

    private static final Map<String, String> SORT_LABELS = new LinkedHashMap<>();

    static {
        SORT_LABELS.put("latest", "最新订单在前");
        SORT_LABELS.put("checkInAsc", "入住日期升序");
        SORT_LABELS.put("checkInDesc", "入住日期降序");
        SORT_LABELS.put("roomNoAsc", "房号升序");
    }

    /** 客户建档的会员等级选项（与 /customers/new 保持一致） */
    private static final List<String> MEMBER_LEVELS = List.of("普通", "银卡", "金卡", "钻石");

    private final ReservationService reservationService;
    private final CustomerService customerService;
    private final RoomService roomService;
    private final BookingPolicy bookingPolicy;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) ReservationStatus status,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInFrom,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInTo,
                       @RequestParam(required = false) Integer floor,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = Pagination.pageSize(size);
        String sortKey = Pagination.sortKey(sort, SORTS, "latest");
        Page<Reservation> result = Pagination.fetch(
                p -> reservationService.search(keyword, status, checkInFrom, checkInTo, floor, p),
                Pagination.pageIndex(page), pageSize, SORTS.get(sortKey));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("keyword", keyword);
        filters.put("status", status);
        filters.put("checkInFrom", checkInFrom);
        filters.put("checkInTo", checkInTo);
        filters.put("floor", floor);
        filters.put("sort", sortKey);
        Pagination.Bases bases = Pagination.bases("/reservations", filters, pageSize);

        model.addAttribute("page", result);
        model.addAttribute("pageNumbers", Pagination.window(result));
        model.addAttribute("sizes", Pagination.SIZE_OPTIONS);
        model.addAttribute("pageBase", bases.page());
        model.addAttribute("sizeBase", bases.size());
        model.addAttribute("statuses", ReservationStatus.values());
        model.addAttribute("floors", roomService.findAllFloors());
        model.addAttribute("sortLabels", SORT_LABELS);
        model.addAttribute("sort", sortKey);
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("checkInFrom", checkInFrom);
        model.addAttribute("checkInTo", checkInTo);
        model.addAttribute("floor", floor);
        model.addAttribute("active", "reservations");
        return "reservation/list";
    }

    /**
     * 导出订单 CSV（第三批 §3.7）。
     * <p>导出当前筛选条件下的全部结果，用于交接班与月度对账。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) ReservationStatus status,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInFrom,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInTo,
                                         @RequestParam(required = false) Integer floor,
                                         @RequestParam(required = false) String sort) {
        String sortKey = Pagination.sortKey(sort, SORTS, "latest");
        List<Reservation> rows = reservationService
                .search(keyword, status, checkInFrom, checkInTo, floor, Pageable.unpaged(SORTS.get(sortKey)))
                .getContent();

        List<String> headers = List.of("订单号", "客户", "手机号", "房间", "楼层", "房型",
                "入住日期", "离店日期", "实际离店", "晚数", "房价", "房费小计",
                "状态", "入住时间", "退房时间", "操作人", "备注");
        List<List<?>> data = new java.util.ArrayList<>();
        for (Reservation r : rows) {
            data.add(List.of(
                    nz(r.getOrderNo()),
                    nz(r.getCustomer().getName()),
                    nz(r.getCustomer().getPhone()),
                    nz(r.getRoom().getRoomNo()),
                    String.valueOf(r.getRoom().getFloor()),
                    nz(r.getRoom().getRoomType().getName()),
                    String.valueOf(r.getCheckInDate()),
                    String.valueOf(r.getCheckOutDate()),
                    r.getActualCheckOutDate() == null ? "" : String.valueOf(r.getActualCheckOutDate()),
                    r.getActualNightCount() + "",
                    money(r.getUnitPrice()),
                    money(r.getActualRoomAmount()),
                    r.getStatus().getLabel(),
                    r.getCheckInTime() == null ? "" : r.getCheckInTime().format(TS),
                    r.getCheckOutTime() == null ? "" : r.getCheckOutTime().format(TS),
                    nz(r.getOperator()),
                    nz(r.getRemark())));
        }
        return CsvExport.download("订单明细_" + LocalDate.now() + ".csv", headers, data);
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }

    private String money(java.math.BigDecimal value) {
        return (value == null ? java.math.BigDecimal.ZERO : value).toPlainString();
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("reservation", reservationService.getById(id));
        model.addAttribute("active", "reservations");
        return "reservation/detail";
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(required = false) Long customerId,
                             @RequestParam(required = false) Long roomId,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                             Model model) {
        ReservationForm form = new ReservationForm();
        form.setCheckInDate(checkIn == null ? LocalDate.now() : checkIn);
        form.setCheckOutDate(form.getCheckInDate().plusDays(1));
        form.setGuestCount(1);
        form.setCustomerId(customerId);
        form.setRoomId(roomId);
        model.addAttribute("form", form);
        prepareForm(model, form);
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
        prepareForm(model, form);
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
            prepareForm(model, form);
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
    public String cancel(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            reservationService.cancel(id, operatorOf(loginUser));
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

    /**
     * 办理退房。
     *
     * @param actualCheckOutDate 实际离店日期（第三批 §1.5）。提前离店时填早于计划离店的日期，
     *                           房费会按实际住夜数重算；不传则按今天。
     */
    @PostMapping("/{id}/checkout")
    public String checkOut(@PathVariable Long id,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate actualCheckOutDate,
                           @AuthenticationPrincipal LoginUser loginUser,
                           RedirectAttributes ra) {
        try {
            Long billId = reservationService.checkOut(id, actualCheckOutDate, operatorOf(loginUser));
            ra.addFlashAttribute("successMessage", "退房完成，账单已按实际住夜重算，请核对后结账");
            return "redirect:/bills/" + billId;
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/reservations/" + id;
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal LoginUser loginUser,
                         RedirectAttributes ra) {
        try {
            reservationService.delete(id, operatorOf(loginUser));
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

    /**
     * 准备预订表单所需的选项。
     *
     * <p>客户不再全量塞进 {@code <select>}（客户上千条时页面会非常重），
     * 改为前端按关键词远程搜索；这里只解析「当前已选客户」用于回填，
     * 以及内联新建弹窗需要的字典项。
     */
    private void prepareForm(Model model, ReservationForm form) {
        model.addAttribute("rooms", roomService.findAll());
        // 预订规则由配置驱动，避免前端写死数字与服务端校验不一致
        model.addAttribute("maxAdvanceDays", bookingPolicy.getMaxAdvanceDays());
        model.addAttribute("maxStayNights", bookingPolicy.getMaxStayNights());
        model.addAttribute("latestCheckIn", LocalDate.now().plusDays(bookingPolicy.getMaxAdvanceDays()));
        model.addAttribute("genders", Gender.values());
        model.addAttribute("idTypes", IdType.values());
        model.addAttribute("memberLevels", MEMBER_LEVELS);

        if (form != null && form.getCustomerId() != null) {
            try {
                model.addAttribute("selectedCustomer", CustomerOption.of(customerService.getById(form.getCustomerId())));
            } catch (BusinessException ignored) {
                // 客户已被删除：选择器留空，让用户重新选人即可
            }
        }
    }

    private String operatorOf(LoginUser loginUser) {
        return loginUser == null ? "系统" : loginUser.getRealName();
    }
}
