package com.hotel.hms.dto;

/**
 * 待办中心的一条待办。
 *
 * @param label 标题，如「我的待办工单」
 * @param count 数量
 * @param level 紧急度（Bootstrap 色板后缀：danger / warning / info / success）
 * @param icon  Bootstrap 图标名
 * @param link  点击跳转
 * @param hint  一句话说明（口径）
 */
public record TodoItem(String label, long count, String level, String icon, String link, String hint) {

    /** 是否需要红色高亮：待办数 &gt; 0 且紧急度为 danger */
    public boolean isUrgent() {
        return count > 0 && "danger".equals(level);
    }

    /** 只在有内容时才展示 */
    public boolean isVisible() {
        return count > 0;
    }
}
