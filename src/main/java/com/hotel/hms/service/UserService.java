package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.User;
import com.hotel.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 员工账号服务。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<User> search(String keyword) {
        String kw = normalize(keyword);
        return kw == null ? userRepository.findAllByOrderByIdAsc() : userRepository.search(kw);
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("账号不存在"));
    }

    @Transactional(readOnly = true)
    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("账号不存在"));
    }

    @Transactional
    public User create(User form, String rawPassword) {
        if (userRepository.existsByUsername(form.getUsername())) {
            throw new BusinessException("登录账号「" + form.getUsername() + "」已存在");
        }
        if (rawPassword == null || rawPassword.trim().length() < 6) {
            throw new BusinessException("初始密码至少 6 位");
        }
        form.setId(null);
        form.setPassword(passwordEncoder.encode(rawPassword.trim()));
        if (form.getRole() == null) {
            form.setRole(Role.RECEPTION);
        }
        if (form.getEnabled() == null) {
            form.setEnabled(true);
        }
        return userRepository.save(form);
    }

    @Transactional
    public User update(Long id, User form, String rawPassword) {
        User db = getById(id);
        if (!db.getUsername().equals(form.getUsername()) && userRepository.existsByUsername(form.getUsername())) {
            throw new BusinessException("登录账号「" + form.getUsername() + "」已存在");
        }
        // 变更前先校验：不允许把系统里最后一个启用的管理员改掉
        Role newRole = form.getRole() == null ? db.getRole() : form.getRole();
        boolean newEnabled = form.getEnabled() == null || form.getEnabled();
        assertEnabledAdminRemains(db.getRole(), Boolean.TRUE.equals(db.getEnabled()), newRole, newEnabled, "修改");

        db.setUsername(form.getUsername());
        db.setRealName(form.getRealName());
        db.setRole(form.getRole());
        db.setPhone(form.getPhone());
        db.setEnabled(form.getEnabled() == null ? Boolean.TRUE : form.getEnabled());
        db.setRemark(form.getRemark());

        if (rawPassword != null && !rawPassword.isBlank()) {
            if (rawPassword.trim().length() < 6) {
                throw new BusinessException("密码至少 6 位");
            }
            db.setPassword(passwordEncoder.encode(rawPassword.trim()));
        }
        return userRepository.save(db);
    }

    @Transactional
    public void delete(Long id, Long currentUserId) {
        User db = getById(id);
        if (db.getId().equals(currentUserId)) {
            throw new BusinessException("不能删除当前登录的账号");
        }
        assertEnabledAdminRemains(db.getRole(), Boolean.TRUE.equals(db.getEnabled()),
                Role.RECEPTION, false, "删除");
        userRepository.delete(db);
    }

    @Transactional
    public void resetPassword(Long id, String rawPassword) {
        if (rawPassword == null || rawPassword.trim().length() < 6) {
            throw new BusinessException("密码至少 6 位");
        }
        User db = getById(id);
        db.setPassword(passwordEncoder.encode(rawPassword.trim()));
        userRepository.save(db);
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User db = getById(userId);
        if (!passwordEncoder.matches(oldPassword, db.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        if (newPassword == null || newPassword.trim().length() < 6) {
            throw new BusinessException("新密码至少 6 位");
        }
        db.setPassword(passwordEncoder.encode(newPassword.trim()));
        userRepository.save(db);
    }

    /**
     * 校验「变更后系统仍至少保留一个启用状态的管理员」。
     * 覆盖删除、降级（ADMIN → 其它角色）、停用三条路径。
     *
     * @param oldRole      变更前的角色
     * @param oldEnabled   变更前是否启用
     * @param newRole      变更后的角色
     * @param newEnabled   变更后是否启用
     * @param action       动作描述，用于拼装提示语（修改 / 删除）
     */
    private void assertEnabledAdminRemains(Role oldRole, boolean oldEnabled,
                                           Role newRole, boolean newEnabled, String action) {
        boolean wasEnabledAdmin = oldRole == Role.ADMIN && oldEnabled;
        boolean willBeEnabledAdmin = newRole == Role.ADMIN && newEnabled;
        if (wasEnabledAdmin && !willBeEnabledAdmin && countEnabledAdmins() <= 1) {
            throw new BusinessException("系统必须保留至少一个启用状态的管理员账号，无法" + action
                    + "该账号（请先新增或启用另一个管理员）");
        }
    }

    private long countEnabledAdmins() {
        return userRepository.countByRoleAndEnabledTrue(Role.ADMIN);
    }

    private String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
