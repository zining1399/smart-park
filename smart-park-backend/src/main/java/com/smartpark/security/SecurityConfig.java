package com.smartpark.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 最小登录 + 角色鉴权：无状态 JWT + 接口级角色规则。
 * 权限矩阵（URL 均为 context-path /api 之内路径）：
 * <pre>
 * /auth/login                 匿名；/actuator/health 匿名（探活，其余 actuator 端点不放行）
 * GET    /employees           ADMIN,GATE ；其他方法 /employees/**  ADMIN
 * GET    /vehicles            ADMIN,GATE ；其他方法 /vehicles/**   ADMIN
 * GET    /permissions         ADMIN,GATE ；POST/PUT                ADMIN,GATE ；DELETE        ADMIN
 * GET    /access-records      ADMIN,GATE ；POST /simulate          ADMIN,GATE
 * /visitors/**                ADMIN,GATE（含 check-in / check-out）
 * /materials/**               ADMIN,STORE
 * GET    /stock-records       ADMIN,STORE；POST /in /out /return   ADMIN,STORE
 * POST   /stock-records/{id}/approve                               ADMIN（审批权收归管理员）
 * /alarms/**  /dashboard/**   /auth/**    任意已登录角色
 * </pre>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final SecurityErrorHandlers errorHandlers;
    private final JwtProperties jwtProperties;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          SecurityErrorHandlers errorHandlers,
                          JwtProperties jwtProperties) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.errorHandlers = errorHandlers;
        this.jwtProperties = jwtProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .authorizeHttpRequests(reg -> reg
                        // CORS 预检
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 登录、令牌刷新（access 过期后靠 refresh 换新，此时已无有效凭证，必须免鉴权）与探活
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // 员工
                        .requestMatchers(HttpMethod.GET, "/employees").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        .requestMatchers("/employees", "/employees/**").hasRole(Roles.ADMIN)
                        // 车辆
                        .requestMatchers(HttpMethod.GET, "/vehicles").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        .requestMatchers("/vehicles", "/vehicles/**").hasRole(Roles.ADMIN)
                        // 通行权限（门岗可现场授权，删除仅管理员）
                        .requestMatchers(HttpMethod.GET, "/permissions").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        .requestMatchers(HttpMethod.POST, "/permissions").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        .requestMatchers(HttpMethod.PUT, "/permissions/**").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        .requestMatchers("/permissions/**").hasRole(Roles.ADMIN)
                        // 通行流水 + 模拟终端
                        .requestMatchers(HttpMethod.GET, "/access-records").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        .requestMatchers("/access-records/**").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        // 访客（门岗日常登记与进出场）
                        .requestMatchers(HttpMethod.GET, "/visitors").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        .requestMatchers("/visitors", "/visitors/**").hasAnyRole(Roles.ADMIN, Roles.GATE)
                        // 物资
                        .requestMatchers(HttpMethod.GET, "/materials").hasAnyRole(Roles.ADMIN, Roles.STORE)
                        .requestMatchers("/materials", "/materials/**").hasAnyRole(Roles.ADMIN, Roles.STORE)
                        // 出入库（审批仅管理员，避免仓管自我审批）
                        .requestMatchers(HttpMethod.GET, "/stock-records").hasAnyRole(Roles.ADMIN, Roles.STORE)
                        .requestMatchers("/stock-records/*/approve").hasRole(Roles.ADMIN)
                        .requestMatchers("/stock-records/**").hasAnyRole(Roles.ADMIN, Roles.STORE)
                        // 告警、看板、账号信息：任意已登录角色
                        .requestMatchers("/alarms/**").authenticated()
                        .requestMatchers("/dashboard/**").authenticated()
                        .requestMatchers("/auth/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** CORS：白名单来自 SMARTPARK_CORS_ORIGINS；留空 = 同域部署，不开放跨域 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        String origins = jwtProperties.getCorsOrigins();
        if (StringUtils.hasText(origins)) {
            for (String origin : origins.split(",")) {
                String trimmed = origin.trim();
                if (StringUtils.hasText(trimmed)) {
                    cfg.addAllowedOriginPattern(trimmed);
                }
            }
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id"));
            cfg.setExposedHeaders(List.of("X-Trace-Id"));
            cfg.setAllowCredentials(true);
            cfg.setMaxAge(3600L);
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
