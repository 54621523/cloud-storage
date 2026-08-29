package demo.cloud.common.web.config;

import demo.cloud.common.web.filter.UserContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;


@Configuration
@EnableMethodSecurity
public class SecurityConfig {


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
                        .requestMatchers("/api/ai/stream/chat", "/api/actuator/**",
                                "/v3/api-docs/**",       // 核心文档数据接口（非常重要）
                                "/swagger-ui/**",        // UI 静态页面资源
                                "/swagger-ui.html",      // UI 访问入口
                                "/swagger-resources/**", // 资源配置
                                "/webjars/**",           // 前端依赖的 webjars
                                "/doc.html"              // 兼容放行 knife4j 路径
                        ).permitAll()
                        // 其他所有请求都需要认证
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new UserContextFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
