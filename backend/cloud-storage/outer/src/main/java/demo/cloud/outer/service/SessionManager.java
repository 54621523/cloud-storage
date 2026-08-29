package demo.cloud.outer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import demo.cloud.outer.constant.SessionRedisPrefix;
import demo.cloud.outer.mapper.ChatSessionMapper;
import demo.cloud.outer.pojo.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class SessionManager {

    private static final Duration SESSION_TTL = Duration.ofHours(1);
    private static final int MAX_SESSIONS_PER_USER = 5;
    private final ChatSessionMapper chatSessionMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public SessionManager(ChatSessionMapper chatSessionMapper, StringRedisTemplate stringRedisTemplate) {
        this.chatSessionMapper = chatSessionMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }


    public String createOrGetSession(Long userId, String requestSessionId) {
            if (userId == null) {
                throw new IllegalArgumentException("userId 不能为空");
            }

            //1. 检查传入会话是否有效
            if(requestSessionId != null && validateSession(requestSessionId, userId)){
                log.info("复用现有会话, sessionId: {}", requestSessionId);
                return requestSessionId;
            }

            // 2. 如果未传入，或传入的 sessionId 校验失败，则创建一个新会话
            log.info("现有会话无效或不存在，自动创建新会话, userId: {}", userId);
            return createSession(userId, null);
    }

    public String createSession(Long userId, String title) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (title == null || title.isBlank()) {
            title = "新会话";
        }

        String indexKey = SessionRedisPrefix.UI_SESSION_LIST.of(String.valueOf(userId));
        long score = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        try {
            // 1. 原子性操作：检查数量 -> 淘汰旧会话 -> 添加新会话
            String luaScript =
                    "local currentSize = tonumber(redis.call('ZCARD', KEYS[1])) " +
                            "if currentSize and currentSize >= tonumber(ARGV[1]) then " +
                            "    redis.call('ZPOPMIN', KEYS[1], 1) " +
                            "end " +
                            "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3]) " +
                            "return 1";

            stringRedisTemplate.execute(
                    new DefaultRedisScript<>(luaScript, Long.class),
                    Collections.singletonList(indexKey),
                    String.valueOf(MAX_SESSIONS_PER_USER),
                    String.valueOf(score),
                    sessionId
            );

            // 2. 刷新列表索引的 TTL
            Long ttl = stringRedisTemplate.getExpire(indexKey, TimeUnit.SECONDS);
            if (ttl < 0) {
                stringRedisTemplate.expire(indexKey, SESSION_TTL.getSeconds(), TimeUnit.SECONDS);
            }

            // 3. 【新增】同步写入鉴权缓存（供 LangGraph threadId 隔离使用）
            String authKey = SessionRedisPrefix.AUTH_SESSION_THREAD.of(sessionId);
            stringRedisTemplate.opsForValue().set(authKey, String.valueOf(userId), SESSION_TTL);

            // 4. 异步写入 MySQL
            final String finalTitle = title;
            CompletableFuture.runAsync(() -> {
                try {
                    ChatSession chatSession = new ChatSession();
                    chatSession.setSessionId(sessionId);
                    chatSession.setUserId(userId);
                    chatSession.setTitle(finalTitle);
                    chatSession.setMessageCount(0);
                    chatSessionMapper.insert(chatSession);
                    log.info("异步写入会话成功, userId: {}, sessionId: {}", userId, sessionId);
                } catch (Exception e) {
                    log.error("异步写入会话失败, userId: {}, sessionId: {}", userId, sessionId, e);
                }
            });

            log.info("用户 {} 创建新会话: {}", userId, sessionId);
            return sessionId;

        } catch (Exception e) {
            log.error("创建会话失败, userId: {}", userId, e);
            throw new RuntimeException("创建会话失败", e);
        }
    }

    public boolean validateSession(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return false;
        }

        try {
            // 优先检查 Redis 鉴权缓存
            String authKey = SessionRedisPrefix.AUTH_SESSION_THREAD.of(sessionId);
            Object cachedUserId = stringRedisTemplate.opsForValue().get(authKey);

            // 缓存命中且归属一致，直接放行
            if (cachedUserId != null && userId.equals(Long.valueOf(cachedUserId.toString()))) {
                // 刷新 TTL，防止用户在长对话中途缓存过期
                stringRedisTemplate.expire(authKey, SESSION_TTL.getSeconds(), TimeUnit.SECONDS);
                return true;
            }
            // 缓存未命中，降级查 MySQL
            return fallbackValidateFromDb(sessionId, userId);

        } catch (Exception e) {
            log.error("验证会话失败, sessionId: {}, userId: {}", sessionId, userId, e);
            // 发生异常时出于安全考虑，拒绝访问
            return false;
        }
    }

    /**
     * 降级查库：验证会话归属权并补写鉴权缓存
     */
    private boolean fallbackValidateFromDb(String sessionId, Long userId) {
        try {
            // 1. 查询数据库，同时校验 sessionId 和 userId 是否匹配
            ChatSession chatSession = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getSessionId, sessionId)
                    .eq(ChatSession::getUserId, userId));

            // 2. 如果查到数据，说明归属合法，补写鉴权缓存
            if (chatSession != null) {
                String authKey = SessionRedisPrefix.AUTH_SESSION_THREAD.of(sessionId);
                stringRedisTemplate.opsForValue().set(authKey, String.valueOf(userId), SESSION_TTL);

                log.info("Redis鉴权缓存未命中，从MySQL验证通过并补写缓存, sessionId: {}", sessionId);
                return true;
            }

            // 3. 查不到数据（sessionId 不存在，或不属于该 userId），直接返回 false
            return false;

        } catch (Exception e) {
            // 捕获数据库异常，记录日志并返回 false，防止 DB 故障导致鉴权接口直接崩溃
            log.error("降级查库验证会话失败, sessionId: {}, userId: {}", sessionId, userId, e);
            return false;
        }
    }
}