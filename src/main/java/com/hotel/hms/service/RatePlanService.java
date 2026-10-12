package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.RatePlan;
import com.hotel.hms.entity.RatePlanType;
import com.hotel.hms.repository.RatePlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 房价方案服务（第六批：协议价 / 会员价）。
 *
 * <h3>为什么要把折扣做成「方案」</h3>
 * 之前给客人优惠只有两个办法：前台口头算个数、或者干脆事后在账单上抹零。
 * 前者不可复现（换个人上班就报不出同一个价），后者无法在预订阶段体现，
 * 客人到店才知道要多少钱。把折扣固化成带编码的方案后，价格可预期、可追溯、可统计。
 */
@Service
@RequiredArgsConstructor
public class RatePlanService {

    private final RatePlanRepository ratePlanRepository;
    private final OperationLogService operationLogService;

    @Transactional(readOnly = true)
    public List<RatePlan> list() {
        return ratePlanRepository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public List<RatePlan> enabled() {
        return ratePlanRepository.findByEnabledTrueOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public RatePlan getById(Long id) {
        return ratePlanRepository.findById(id)
                .orElseThrow(() -> new BusinessException("房价方案不存在"));
    }

    /** 按方案 id 取方案；{@code id} 为空时返回 {@code null}（表示按门市价计价） */
    @Transactional(readOnly = true)
    public RatePlan resolve(Long id) {
        if (id == null) {
            return null;
        }
        return ratePlanRepository.findById(id)
                .filter(RatePlan::getEnabled)
                .orElseThrow(() -> new BusinessException("所选房价方案不存在或已停用"));
    }

    /** 按会员等级匹配「会员价」方案（供预订表单自动带出） */
    @Transactional(readOnly = true)
    public Optional<RatePlan> matchMember(String memberLevel) {
        if (memberLevel == null || memberLevel.isBlank()) {
            return Optional.empty();
        }
        return ratePlanRepository.findFirstByTypeAndMemberLevelAndEnabledTrue(RatePlanType.MEMBER, memberLevel.trim());
    }

    /**
     * 新建 / 修改房价方案。
     *
     * <p>折扣率与固定房价至少填一个；两个都填时以固定房价优先（见 {@link RatePlan#priceFor}）。
     */
    @Transactional
    public RatePlan save(Long id, RatePlan form, String operator) {
        String code = trim(form.getCode());
        String name = trim(form.getName());
        if (code == null) {
            throw new BusinessException("请填写方案编码");
        }
        if (name == null) {
            throw new BusinessException("请填写方案名称");
        }
        boolean codeTaken = (id == null)
                ? ratePlanRepository.existsByCode(code)
                : ratePlanRepository.existsByCodeAndIdNot(code, id);
        if (codeTaken) {
            throw new BusinessException("方案编码「" + code + "」已被占用，请换一个");
        }

        BigDecimal rate = form.getDiscountRate();
        BigDecimal fixed = form.getFixedPrice();
        boolean hasRate = rate != null && rate.signum() > 0;
        boolean hasFixed = fixed != null && fixed.signum() > 0;
        if (!hasRate && !hasFixed) {
            throw new BusinessException("请至少填写「折扣率」或「固定房价」中的一项");
        }
        if (hasRate && rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("折扣率必须大于 0 且不超过 1（例如 0.85 表示 8.5 折）");
        }

        RatePlan target = (id == null) ? new RatePlan() : getById(id);
        target.setCode(code);
        target.setName(name);
        target.setType(form.getType() == null ? RatePlanType.CORPORATE : form.getType());
        target.setDiscountRate(hasRate ? rate : null);
        target.setFixedPrice(hasFixed ? fixed : null);
        target.setMemberLevel(trim(form.getMemberLevel()));
        target.setEnabled(form.getEnabled() == null || form.getEnabled());
        target.setNote(trim(form.getNote()));

        // 会员价必须绑定等级，否则永远匹配不上，属于「建了但没用」的坑，直接拦掉
        if (target.getType() == RatePlanType.MEMBER && target.getMemberLevel() == null) {
            throw new BusinessException("会员价方案必须选择适用的会员等级");
        }

        RatePlan saved = ratePlanRepository.save(target);
        operationLogService.record(id == null ? "房价方案新增" : "房价方案修改", "rate_plan", saved.getId(),
                saved.getCode() + " / " + saved.getName() + " / " + saved.getDiscountLabel(), operator);
        return saved;
    }

    /** 启用 / 停用。停用后不能再被新订单选用，但历史订单上的方案名快照不受影响。 */
    @Transactional
    public RatePlan toggle(Long id, boolean enabled, String operator) {
        RatePlan plan = getById(id);
        plan.setEnabled(enabled);
        RatePlan saved = ratePlanRepository.save(plan);
        operationLogService.record("房价方案" + (enabled ? "启用" : "停用"), "rate_plan", saved.getId(),
                saved.getCode() + " / " + saved.getName(), operator);
        return saved;
    }

    @Transactional
    public void delete(Long id, String operator) {
        RatePlan plan = getById(id);
        ratePlanRepository.delete(plan);
        operationLogService.record("房价方案删除", "rate_plan", id,
                plan.getCode() + " / " + plan.getName(), operator);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
