package demo.cloud.common.web.context;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class BaseContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    /**
     * 优先从 Spring Security 上下文中获取，获取不到再走 ThreadLocal 兜底
     */
    public static Long getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long) {
            return (Long) authentication.getPrincipal();
        }
        return CURRENT_USER_ID.get();
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
        CURRENT_USER_ID.remove();
    }
}