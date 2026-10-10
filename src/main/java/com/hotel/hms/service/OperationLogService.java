package com.hotel.hms.service;

import com.hotel.hms.config.LoginUser;
import com.hotel.hms.entity.OperationLog;
import com.hotel.hms.repository.OperationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志服务（第三批：审计留痕）。
 *
 * <p>写入采用<b>默认事务传播</b>，与调用方的业务事务同生共死——这样日志里不会出现
 * 「记了一笔但业务其实回滚了」的假记录。因此在调用方 {@code record(...)} 之前
 * 应先完成全部业务校验。
 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private static final int DETAIL_MAX = 500;

    private final OperationLogRepository operationLogRepository;

    /** 写一条操作日志 */
    @Transactional
    public void record(String action, String targetType, Long targetId, String detail, String operator) {
        OperationLog log = new OperationLog();
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(truncate(detail));
        log.setOperator(operator == null || operator.isBlank() ? currentOperator() : operator);
        operationLogRepository.save(log);
    }

    /** 写一条操作日志，操作人自动取当前登录用户 */
    @Transactional
    public void record(String action, String targetType, Long targetId, String detail) {
        record(action, targetType, targetId, detail, null);
    }

    /**
     * 日志检索。
     *
     * @param to 日期上限（含），内部转成次日 00:00 的开区间上界
     */
    @Transactional(readOnly = true)
    public Page<OperationLog> search(String action, String keyword,
                                     LocalDate from, LocalDate to, Pageable pageable) {
        LocalDateTime fromTs = from == null ? null : from.atStartOfDay();
        LocalDateTime toTs = to == null ? null : to.plusDays(1).atStartOfDay();
        return operationLogRepository.search(
                action == null || action.isBlank() ? null : action,
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                fromTs, toTs, pageable);
    }

    @Transactional(readOnly = true)
    public List<String> distinctActions() {
        return operationLogRepository.distinctActions();
    }

    /** 当前登录用户姓名；无登录态（如启动期任务）时返回「系统」 */
    public static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "系统";
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof LoginUser loginUser) {
            return loginUser.getRealName();
        }
        return auth.getName();
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
    }
}
