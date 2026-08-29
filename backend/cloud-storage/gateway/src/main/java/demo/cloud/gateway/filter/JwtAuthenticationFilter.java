package demo.cloud.gateway.filter;

import demo.cloud.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 放行不需要鉴权的路径（白名单）
        if (isSwaggerOrKnife4jPath(path)
        ) {
            return chain.filter(exchange);
        }

        // 2. 获取并校验 Token
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorizedResponse(exchange, "缺少认证令牌或格式错误");
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return unauthorizedResponse(exchange, "Token无效或已过期");
        }

        // 3. 解析 userId 并添加到请求头中，传递给下游微服务
        Long userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            return unauthorizedResponse(exchange, "无法解析用户信息");
        }

        // 使用 mutate 构建带有新 Header 的请求
        ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-User-Id", String.valueOf(userId))
                .build();

        // 4. 校验通过，放行请求
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 构建统一的 401 未授权响应
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String body = "{\"code\":401,\"message\":\"" + message + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    /**
     * 设置过滤器执行顺序，数字越小优先级越高
     */
    @Override
    public int getOrder() {
        return -100;
    }

    private boolean isSwaggerOrKnife4jPath(String path) {
        // 1. 精确匹配或前缀匹配（如登录、监控接口）
        if (path.startsWith("/api/auth/") || path.startsWith("/actuator/")) {
            return true;
        }
        // 2. 使用 contains 或通配正则，兼容带有网关路由前缀的路径
        // 放行 doc.html 及其相关路径
        if (path.contains("/doc.html")) {
            return true;
        }
        // 放行 Swagger UI 相关
        if (path.contains("/swagger-ui")) {
            return true;
        }
        // 放行 Swagger 资源配置
        if (path.contains("/swagger-resources")) {
            return true;
        }
        // 放行 API 文档数据接口（兼容 v2 和 v3）
        if (path.contains("/v3/api-docs") || path.contains("/v2/api-docs")) {
            return true;
        }
        // 放行 webjars 静态资源（解决 404 和 401 的核心）
        return path.contains("/webjars/");
    }
}