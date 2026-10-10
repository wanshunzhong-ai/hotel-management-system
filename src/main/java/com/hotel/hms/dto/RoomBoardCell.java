package com.hotel.hms.dto;

import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.Room;

import java.time.LocalDate;

/**
 * 房态图上的一个单元格：某间房在某个日期的营业状态。
 *
 * @param room      房间（含房型，价格用于展示）
 * @param date      该单元格对应的日期（代表这一天<b>晚上</b>）
 * @param state     营业状态
 * @param occupancy 该晚占用这间房的预订单；没有则为 {@code null}
 */
public record RoomBoardCell(Room room, LocalDate date, BoardState state, Reservation occupancy) {

    /** 该晚是否可售 */
    public boolean sellable() {
        return state.isSellable();
    }

    /** 有占用订单时，展示用的一句话摘要（客人 + 订单号） */
    public String occupancySummary() {
        if (occupancy == null) {
            return null;
        }
        return occupancy.getCustomer().getName() + " · " + occupancy.getOrderNo();
    }
}
