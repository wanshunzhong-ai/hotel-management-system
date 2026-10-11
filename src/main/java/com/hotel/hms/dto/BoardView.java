package com.hotel.hms.dto;

import java.util.List;
import java.util.Map;

/**
 * 房态大屏的一次快照。
 *
 * <p>前端每 10 秒拉一次 {@code /api/board} 拿到这个结构重绘，
 * 因此顶层刻意做成「统计 + 按楼层分组」两块，避免前端再做二次聚合。
 *
 * @param serverTime 服务器时间（大屏顶部时钟以它为准，避免各终端时间不同步）
 * @param date       快照对应的营业日期
 * @param stats      统计数字，键见 {@code BoardService}；计数为 Long，出租率为 Double（百分比，如 8.1）
 * @param floors     按楼层分组的房间格子
 */
public record BoardView(String serverTime, String date, Map<String, Object> stats, List<Floor> floors) {

    /**
     * @param floor 楼层
     * @param rooms 该楼层全部房间
     */
    public record Floor(int floor, List<Room> rooms) {
    }

    /**
     * @param roomNo   房号
     * @param type     房型名
     * @param state    营业状态枚举名（前端据此上色）
     * @param label    营业状态中文
     * @param color    Bootstrap 色板后缀
     * @param guest    占用客人姓名，空房为 null
     * @param orderNo  订单号，空房为 null
     * @param period   入住区间文案，空房为 null
     */
    public record Room(String roomNo, String type, String state, String label, String color,
                       String guest, String orderNo, String period) {
    }
}
