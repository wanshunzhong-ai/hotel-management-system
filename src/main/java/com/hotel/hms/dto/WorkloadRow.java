package com.hotel.hms.dto;

/**
 * 客房部工作量统计的一行（按人聚合）。
 *
 * @param staffId       员工 id
 * @param staffName     姓名
 * @param roleLabel     角色
 * @param assigned      期间派出给他的工单数
 * @param done          其中已完成（已标记完成，含待验收/通过/打回）
 * @param approved      验收通过数
 * @param rejected      当前处于「被打回」的数
 * @param avgMinutes    平均耗时（分钟，完成时间 − 派工时间；无样本为 null）
 * @param completionRate 完成率（%）
 */
public record WorkloadRow(
        Long staffId,
        String staffName,
        String roleLabel,
        int assigned,
        int done,
        int approved,
        int rejected,
        Long avgMinutes,
        int completionRate
) {
    /** 是否期间无任何派工 */
    public boolean isIdle() {
        return assigned == 0;
    }

    /** 平均耗时的可读文案 */
    public String getAvgText() {
        if (avgMinutes == null) {
            return "—";
        }
        if (avgMinutes < 60) {
            return avgMinutes + " 分钟";
        }
        return (avgMinutes / 60) + " 小时 " + (avgMinutes % 60) + " 分";
    }
}
