package com.hotel.hms.service;

import com.hotel.hms.dto.BoardState;
import com.hotel.hms.dto.BoardView;
import com.hotel.hms.dto.RoomBoardCell;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 房态大屏数据装配。
 *
 * <p>复用 {@link RoomService#boardOn(LocalDate)} 的判定口径（与预订时的可订房查询一致），
 * 只在外面套一层「统计 + 按楼层分组 + 前端渲染所需的扁平字段」。
 * 大屏是只读视图，不做任何写回。
 */
@Service
@RequiredArgsConstructor
public class BoardService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd");

    private final RoomService roomService;

    @Transactional(readOnly = true)
    public BoardView snapshot(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<RoomBoardCell> cells = roomService.boardOn(day);

        Map<Integer, List<BoardView.Room>> byFloor = new TreeMap<>();
        for (RoomBoardCell cell : cells) {
            byFloor.computeIfAbsent(cell.room().getFloor(), k -> new ArrayList<>()).add(toRoom(cell));
        }
        List<BoardView.Floor> floors = byFloor.entrySet().stream()
                .map(e -> new BoardView.Floor(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(BoardView.Floor::floor))
                .toList();

        return new BoardView(java.time.LocalDateTime.now().format(TIME), day.toString(),
                stats(cells), floors);
    }

    /**
     * 统计口径：
     * <ul>
     *     <li>{@code sellable} / {@code sold} 与房态一览页完全一致（可售 = 未维修且该晚无有效订单）；</li>
     *     <li>{@code occupancy} = 已售 / 可售 的百分比（如 8.1），可售为 0 时记 0.0；</li>
     *     <li>各状态计数按 {@link BoardState} 逐项统计，前端直接取用。</li>
     * </ul>
     */
    private Map<String, Object> stats(List<RoomBoardCell> cells) {
        long sellable = cells.stream().filter(RoomBoardCell::sellable).count();
        long total = cells.size();
        long sold = total - sellable;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("sellable", sellable);
        stats.put("sold", sold);
        for (BoardState state : BoardState.displayOrder()) {
            stats.put(state.name(), cells.stream().filter(c -> c.state() == state).count());
        }
        // 出租率按「已售 / 可售」的百分比给出，保留一位小数；可售为 0 时记 0.0，避免除零
        double occupancy = sellable == 0 ? 0d : Math.round(sold * 1000.0 / sellable) / 10d;
        stats.put("occupancy", occupancy);
        return stats;
    }

    private BoardView.Room toRoom(RoomBoardCell cell) {
        BoardState state = cell.state();
        String guest = null;
        String orderNo = null;
        String period = null;
        if (cell.occupancy() != null) {
            guest = cell.occupancy().getCustomer().getName();
            orderNo = cell.occupancy().getOrderNo();
            period = cell.occupancy().getCheckInDate().format(SHORT) + " → "
                    + cell.occupancy().getCheckOutDate().format(SHORT);
        }
        return new BoardView.Room(cell.room().getRoomNo(), cell.room().getRoomType().getName(),
                state.name(), state.getLabel(), state.getColor(), guest, orderNo, period);
    }
}
