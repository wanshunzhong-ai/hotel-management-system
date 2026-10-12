package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.dto.WorkloadRow;
import com.hotel.hms.entity.DayShift;
import com.hotel.hms.entity.DutySchedule;
import com.hotel.hms.entity.HousekeepingTask;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.TaskStatus;
import com.hotel.hms.entity.User;
import com.hotel.hms.repository.DutyScheduleRepository;
import com.hotel.hms.repository.HousekeepingTaskRepository;
import com.hotel.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客房部排班与工作量服务。
 *
 * <p>两块能力各自独立：
 * <ul>
 *     <li><b>排班</b>：某天哪个班次谁当值（{@link DutySchedule}），回答「今天该有几个人」；</li>
 *     <li><b>工作量</b>：按人统计期间内的派工与完成情况（{@link WorkloadRow}），回答「谁干得多、干得快」。</li>
 * </ul>
 *
 * <p>工作量口径以<b>派工时间</b>归属区间（{@code createdAt} 落在 [from, to+1) 内），
 * 平均耗时 = 完成时间 − 派工时间。这样「今天派出去的单」不会被跨日完成摊到两天。
 */
@Service
@RequiredArgsConstructor
public class DutyService {

    private final DutyScheduleRepository dutyScheduleRepository;
    private final HousekeepingTaskRepository taskRepository;
    private final UserRepository userRepository;
    private final OperationLogService operationLogService;

    // ------------------------------------------------------------------
    // 排班
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DutySchedule> byDate(LocalDate date) {
        return dutyScheduleRepository.findByWorkDateOrderByShiftAscIdAsc(date);
    }

    /** 按班次分组的当日排班（页面按三班次分列展示） */
    @Transactional(readOnly = true)
    public Map<DayShift, List<DutySchedule>> rotationOf(LocalDate date) {
        Map<DayShift, List<DutySchedule>> map = new EnumMap<>(DayShift.class);
        for (DayShift shift : DayShift.values()) {
            map.put(shift, new ArrayList<>());
        }
        for (DutySchedule schedule : byDate(date)) {
            map.computeIfAbsent(schedule.getShift(), k -> new ArrayList<>()).add(schedule);
        }
        return map;
    }

    /** 可排班人员：启用的客房服务员 + 客房主管 */
    @Transactional(readOnly = true)
    public List<User> listStaff() {
        return userRepository.findAllByOrderByIdAsc().stream()
                .filter(u -> u.getRole() == Role.HOUSEKEEPING
                        || u.getRole() == Role.HOUSEKEEPING_SUPERVISOR)
                .filter(u -> Boolean.TRUE.equals(u.getEnabled()))
                .toList();
    }

    @Transactional
    public Long schedule(LocalDate date, DayShift shift, Long staffId, String remark, String operator) {
        if (date == null) {
            throw new BusinessException("请选择排班日期");
        }
        User staff = userRepository.findById(staffId)
                .orElseThrow(() -> new BusinessException("员工不存在"));
        if (staff.getRole() != Role.HOUSEKEEPING && staff.getRole() != Role.HOUSEKEEPING_SUPERVISOR) {
            throw new BusinessException("只能给客房服务员或客房主管排班");
        }
        if (!Boolean.TRUE.equals(staff.getEnabled())) {
            throw new BusinessException("该账号已停用，无法排班");
        }
        DayShift s = shift == null ? DayShift.MORNING : shift;
        if (dutyScheduleRepository.existsByWorkDateAndShiftAndStaffId(date, s, staffId)) {
            throw new BusinessException(staff.getRealName() + " 在 " + date + " 的" + s.getLabel() + "已排过班");
        }
        DutySchedule schedule = new DutySchedule();
        schedule.setWorkDate(date);
        schedule.setShift(s);
        schedule.setStaff(staff);
        schedule.setRemark(remark);
        schedule.setOperator(operator);
        DutySchedule saved = dutyScheduleRepository.save(schedule);
        operationLogService.record("排班", "duty_schedule", saved.getId(),
                date + " " + s.getLabel() + " → " + staff.getRealName()
                        + (remark == null || remark.isBlank() ? "" : "（" + remark + "）"), operator);
        return saved.getId();
    }

    @Transactional
    public void unschedule(Long id, String operator) {
        DutySchedule schedule = dutyScheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException("排班记录不存在"));
        dutyScheduleRepository.delete(schedule);
        operationLogService.record("取消排班", "duty_schedule", id,
                schedule.getWorkDate() + " " + schedule.getShift().getLabel()
                        + " " + schedule.getStaff().getRealName() + " 已取消", operator);
    }

    // ------------------------------------------------------------------
    // 工作量
    // ------------------------------------------------------------------

    /**
     * 期间工作量：按人聚合。
     *
     * <p>返回顺序按「派出数降序、姓名升序」——最忙的排在前面，方便主管一眼看到。
     * 没有任何派工的人也保留一行（{@link WorkloadRow#isIdle()}），否则「谁在闲着」看不出来。
     */
    @Transactional(readOnly = true)
    public List<WorkloadRow> workload(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        LocalDateTime fromTs = start.atStartOfDay();
        LocalDateTime toTs = end.plusDays(1).atStartOfDay();
        List<HousekeepingTask> tasks = taskRepository.findByCreatedAtBetweenOrderByIdAsc(fromTs, toTs);

        Map<Long, Acc> accs = new LinkedHashMap<>();
        // 先给所有客房部人员建零行（含排班里出现但不属于客房部的人？—— 不会，排班已限制角色）
        for (User staff : listStaff()) {
            accs.put(staff.getId(), new Acc(staff));
        }
        for (HousekeepingTask task : tasks) {
            User assignee = task.getAssignee();
            if (assignee == null) {
                continue;
            }
            Acc acc = accs.computeIfAbsent(assignee.getId(), k -> new Acc(assignee));
            acc.assigned++;
            if (task.getDoneAt() != null) {
                acc.done++;
                acc.durationMinutes += Duration.between(task.getCreatedAt(), task.getDoneAt()).toMinutes();
                acc.durationSamples++;
            }
            if (task.getStatus() == TaskStatus.APPROVED) {
                acc.approved++;
            }
            if (task.getStatus() == TaskStatus.REJECTED) {
                acc.rejected++;
            }
        }

        List<WorkloadRow> rows = new ArrayList<>();
        for (Acc acc : accs.values()) {
            Long avg = acc.durationSamples == 0 ? null
                    : Math.round((double) acc.durationMinutes / acc.durationSamples);
            int rate = acc.assigned == 0 ? 0 : (int) Math.round(acc.done * 100.0 / acc.assigned);
            rows.add(new WorkloadRow(acc.user.getId(), acc.user.getRealName(),
                    acc.user.getRole().getLabel(), acc.assigned, acc.done,
                    acc.approved, acc.rejected, avg, rate));
        }
        rows.sort((a, b) -> {
            int byAssigned = Integer.compare(b.assigned(), a.assigned());
            if (byAssigned != 0) {
                return byAssigned;
            }
            return a.staffName().compareTo(b.staffName());
        });
        return rows;
    }

    private static final class Acc {
        private final User user;
        private int assigned;
        private int done;
        private int approved;
        private int rejected;
        private long durationMinutes;
        private int durationSamples;

        private Acc(User user) {
            this.user = user;
        }
    }
}
