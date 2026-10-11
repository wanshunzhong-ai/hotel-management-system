package com.hotel.hms.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置：表单登录 + 基于角色的 URL 授权。
 *
 * <p>角色分工：
 * <ul>
 *     <li>ADMIN 管理员：全部功能</li>
 *     <li>RECEPTION 前台：预订、入住、退房、结账、客户、房态</li>
 *     <li>HOUSEKEEPING 客房：房态查看与清洁维护</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 静态资源与登录页
                        .requestMatchers("/login", "/css/**", "/js/**", "/vendor/**",
                                "/images/**", "/favicon.ico", "/error/**").permitAll()
                        // 住客扫码页：免登录，安全性由 32 位 Base62 随机令牌（约 190 bit 熵）+「房间必须有在住订单」共同保证
                        .requestMatchers("/scan", "/scan/**").permitAll()
                        // 账号管理仅管理员
                        .requestMatchers("/users/**").hasRole("ADMIN")
                        // 操作日志（审计）仅管理员
                        .requestMatchers("/logs", "/logs/**").hasRole("ADMIN")
                        // 客房的新增 / 编辑 / 删除仅管理员与前台
                        .requestMatchers("/rooms/new", "/rooms/*/edit", "/rooms/*/delete")
                        .hasAnyRole("ADMIN", "RECEPTION")
                        // 房态维护三类角色都可
                        .requestMatchers("/rooms/**").hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 客房部工作台：派工验收 / 迷你吧挂账（验收动作另由 @PreAuthorize 限管理员）
                        .requestMatchers("/housekeeping/**").hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 维修工单
                        .requestMatchers("/maintenance/**").hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 失物招领
                        .requestMatchers("/lost-found/**").hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 查房评分
                        .requestMatchers("/inspections", "/inspections/**")
                        .hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 洗衣与布草
                        .requestMatchers("/laundry", "/laundry/**").hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 扫码消费：目录维护与房间二维码（写操作另由 @PreAuthorize 限管理员/前台）
                        .requestMatchers("/consumables", "/consumables/**")
                        .hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 房型维护
                        .requestMatchers("/room-types/**").hasAnyRole("ADMIN", "RECEPTION")
                        // 前台核心业务
                        .requestMatchers("/customers/**", "/reservations/**", "/bills/**")
                        .hasAnyRole("ADMIN", "RECEPTION")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/dashboard", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .exceptionHandling(ex -> ex.accessDeniedPage("/error/403"))
                .sessionManagement(session -> session
                        .maximumSessions(5)
                        .expiredUrl("/login?expired")
                );

        return http.build();
    }
}
