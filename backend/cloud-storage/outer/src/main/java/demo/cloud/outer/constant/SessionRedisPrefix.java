package demo.cloud.outer.constant;

/**
 * Redis Key 前缀常量（会话相关）
 * 命名规范：rag:{场景}:{业务}:{子业务}:
 */
public enum SessionRedisPrefix {

    /**
     * 前端展示用：用户会话列表 (ZSet)
     * 示例: agent:ui:session:list:10086
     */
    UI_SESSION_LIST("rag:ui:session:list:"),

    /**
     * 后端鉴权用：会话归属与Thread隔离 (String)
     * 示例: agent:auth:session:thread:uuid-xxxx-xxxx
     */
    AUTH_SESSION_THREAD("rag:auth:session:thread:");

    private final String prefix;

    SessionRedisPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * 拼接完整 Key 的便捷方法
     */
    public String of(String suffix) {
        return prefix + suffix;
    }
}