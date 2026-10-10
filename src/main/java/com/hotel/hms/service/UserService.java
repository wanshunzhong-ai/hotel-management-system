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
        if (db.getRole() == Role.ADMIN && countEnabledAdmins() <= 1) {
            throw new BusinessException("系统必须保留至少一个管理员账号");
        }
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

    private long countEnabledAdmins() {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN && Boolean.TRUE.equals(u.getEnabled()))
                .count();
    }

    private String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
