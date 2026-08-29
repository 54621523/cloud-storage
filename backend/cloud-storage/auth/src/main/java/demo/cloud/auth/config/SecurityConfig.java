package demo.cloud.auth.config;

import demo.cloud.auth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 1. 密码加密器（必须注册为 Bean，Auth 和 Security 内部都会用到）
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 2. 核心认证管理器（将 UserService 和 PasswordEncoder 绑定）
     */
    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http,
                                                       UserService userService,
                                                       PasswordEncoder passwordEncoder) throws Exception {
        AuthenticationManagerBuilder builder = http.getSharedObject(AuthenticationManagerBuilder.class);
        builder.userDetailsService(userService).passwordEncoder(passwordEncoder);
        return builder.build();
    }

    /**
     * 3. HTTP 安全过滤链
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 禁用 CSRF（JWT 无状态架构下必须禁用）
                .csrf(AbstractHttpConfigurer::disable)
                // 禁用 Session（完全依赖 JWT）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 接口权限控制
                .authorizeHttpRequests(authz -> authz
                        // 放行登录、注册、验证 Token 和监控接口
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/verify", "/api/actuator/**",
                                "/v3/api-docs/**",       // 核心文档数据接口（非常重要）
                                "/swagger-ui/**",        // UI 静态页面资源
                                "/swagger-ui.html",      // UI 访问入口
                                "/swagger-resources/**", // 资源配置
                                "/webjars/**",           // 前端依赖的 webjars
                                "/doc.html"              // 兼容放行 knife4j 路径
                        ).permitAll()
                        // 其他所有请求都需要认证
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}