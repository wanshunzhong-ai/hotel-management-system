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
                        // 账号管理仅管理员
                        .requestMatchers("/users/**").hasRole("ADMIN")
                        // 客房的新增 / 编辑 / 删除仅管理员与前台
                        .requestMatchers("/rooms/new", "/rooms/*/edit", "/rooms/*/delete")
                        .hasAnyRole("ADMIN", "RECEPTION")
                        // 房态维护三类角色都可
                        .requestMatchers("/rooms/**").hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
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
