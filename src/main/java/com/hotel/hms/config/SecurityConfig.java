package com.hotel.hms.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置：表单登录 + 基于角色的 URL 授权 + 角色继承。
 *
 * <p>角色分工：
 * <ul>
 *     <li>ADMIN 管理员：全部功能</li>
 *     <li>RECEPTION 前台：预订、入住、退房、结账、客户、房态</li>
 *     <li>HOUSEKEEPING_SUPERVISOR 客房主管：客房服务员的一切 + 排班 / 派工 / 验收（第七批新增）</li>
 *     <li>HOUSEKEEPING 客房：房态查看与清洁维护</li>
 * </ul>
 *
 * <h3>为什么用角色继承而不是到处加角色码</h3>
 * 主管能进的页面与客房服务员完全重合（房态、派工、洗衣、迷你吧、维修、失物、检查单……），
 * 若在每个 matcher 与 {@code @PreAuthorize} 里都补一个 {@code 'HOUSEKEEPING_SUPERVISOR'}，
 * 不但啰嗦，而且日后新增客房页面时<b>极容易漏</b>——漏了就是「主管反而进不去」的怪现象。
 * 用 {@link RoleHierarchy} 声明 {@code SUPERVISOR > HOUSEKEEPING} 之后，
 * 授权表达式里只写 {@code HOUSEKEEPING} 即可自动包含主管；需要额外放开给主管的动作
 * （派工、验收）才显式写 {@code 'HOUSEKEEPING_SUPERVISOR'}。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 角色继承：客房主管 ⊃ 客房服务员。
     *
     * <p>继承是<b>单向</b>的——主管能做服务员的事，服务员不会因此获得主管的权限。
     */
    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_HOUSEKEEPING_SUPERVISOR > ROLE_HOUSEKEEPING");
    }

    /**
     * 让 {@code @PreAuthorize} 也走上面这套继承关系。
     *
     * <p>URL 层的授权管理器会自动拾取 {@link RoleHierarchy} bean，但方法级安全使用的
     * 表达式处理器需要显式绑定，否则会出现「页面进得去、提交 403」的割裂现象。
     */
    @Bean
    public static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }

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
                        // 交接班名单打印页：含全店客人姓名与手机号，属前台隐私数据，
                        // 客房部 / 主管一律不得查看（营收都遮了，客人手机号更不能敞开）
                        .requestMatchers("/dashboard/print/**").hasAnyRole("ADMIN", "RECEPTION")
                        // 客房的新增 / 编辑 / 删除仅管理员与前台。
                        // 注意必须连表单提交地址 /rooms/save 一起拦：先前只拦了 GET 表单页
                        // （/rooms/new、/rooms/*/edit）与 /rooms/*/delete，/rooms/save 落进下面的
                        // /rooms/**，客房部就能绕过被隐藏的按钮直接 POST 建房/改房。
                        .requestMatchers("/rooms/new", "/rooms/save", "/rooms/*/edit", "/rooms/*/delete")
                        .hasAnyRole("ADMIN", "RECEPTION")
                        // 房态维护三类角色都可
                        .requestMatchers("/rooms/**").hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 排班与工作量：全员排班表与按人考核口径（派工数 / 完成率 / 平均耗时）属管理数据，
                        // 服务员不进。必须放在 /housekeeping/** 之前——matcher 是顺序匹配，
                        // 落在后面就永远轮不到（第八批缺口 1）。
                        .requestMatchers("/housekeeping/schedule", "/housekeeping/schedule/**")
                        .hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING_SUPERVISOR")
                        // 客房部工作台：派工验收 / 迷你吧挂账（派工与验收动作另由 @PreAuthorize 限管理员与前台）
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
                        // 布草外送：批次操作与供应商结算（洗涤单价 / 结算金额属成本数据）同为主管与前台事务，
                        // 服务员不进。必须放在 /laundry/** 之前（顺序匹配）（第八批缺口 4）。
                        .requestMatchers("/laundry/outsourcing", "/laundry/outsourcing/**")
                        .hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING_SUPERVISOR")
                        // 扫码消费：目录维护与房间二维码（写操作另由 @PreAuthorize 限管理员/前台）
                        .requestMatchers("/consumables", "/consumables/**")
                        .hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 第七批：清洁标准检查单（模板维护另由 @PreAuthorize 限管理员/前台/主管）
                        .requestMatchers("/checklists", "/checklists/**")
                        .hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 第七批：客房耗材出入库台账
                        .requestMatchers("/supplies", "/supplies/**")
                        .hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 第七批：客房部现场上报（房间操作台）。注意与免登录的 /scan/** 是两条不同路径——
                        // 员工上报必须登录，否则任何人都能替客房部报修/登记失物。
                        .requestMatchers("/hk", "/hk/**")
                        .hasAnyRole("ADMIN", "RECEPTION", "HOUSEKEEPING")
                        // 房型维护
                        .requestMatchers("/room-types/**").hasAnyRole("ADMIN", "RECEPTION")
                        // 前台核心业务
                        .requestMatchers("/customers/**", "/reservations/**", "/bills/**")
                        .hasAnyRole("ADMIN", "RECEPTION")
                        // 第六批：前台日常刚需六件套（收款流水 / 发票 / 应收账款 / 交接班 / 团队入住 / 房价方案）。
                        // 全部只对管理员与前台开放——这些页面能看到全店资金往来，客房部不该进。
                        .requestMatchers("/payments", "/payments/**", "/invoices", "/invoices/**",
                                "/receivables", "/receivables/**", "/shift", "/shift/**",
                                "/groups", "/groups/**", "/rate-plans", "/rate-plans/**")
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
