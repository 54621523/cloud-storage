package demo.cloud.common.web.filter;

import demo.cloud.common.web.context.BaseContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * 用户上下文注入过滤器
 * 拦截所有请求，从网关传递的请求头中提取用户信息并注入到 Spring Security 上下文
 */
public class UserContextFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. 从请求头中获取网关注入的用户ID
            String userIdStr = request.getHeader(HEADER_USER_ID);

            // 2. 如果存在用户ID，则解析并注入到 Spring Security 上下文
            if (userIdStr != null && !userIdStr.isEmpty()) {
                try {
                    Long userId = Long.parseLong(userIdStr);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());

                    // 存入 Security 上下文
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                } catch (NumberFormatException e) {
                    // 格式异常忽略，后续业务会处理未登录状态
                }
            }

            // 3. 放行请求
            filterChain.doFilter(request, response);

        } finally {
            BaseContext.clear();
        }
    }
}