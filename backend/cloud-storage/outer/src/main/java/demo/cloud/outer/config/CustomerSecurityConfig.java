package demo.cloud.outer.config;

import demo.cloud.common.web.filter.UserContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableWebSecurity
public class CustomerSecurityConfig {

    @Bean
    @Order(0)  // 高优先级，优先匹配
    public SecurityFilterChain customerFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(  // 👈 关键：限定此链仅匹配以下路径
                        "/api/ai/stream/chat",
                        "/api/ai/stream/chat/mock"   // 你需要的 mock 路径
                )
                .authorizeHttpRequests(authz -> authz
                        .anyRequest().permitAll()   // 匹配到的路径全部放行
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new UserContextFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}