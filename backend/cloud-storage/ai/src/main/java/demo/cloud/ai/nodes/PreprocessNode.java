package demo.cloud.ai.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import demo.cloud.ai.pojo.UserContext;
import demo.cloud.ai.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class PreprocessNode implements NodeAction {


    private final ChatModel chatModel;
    private final RedissonClient redissonClient;
    private final UserContextService userContextService;
    private final ObjectMapper objectMapper;


    private static final int HISTORY_WINDOW = 5;
    private static final Duration USER_CONTEXT_TIMEOUT = Duration.ofSeconds(2);

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String threadId = state.value("threadId").toString();
        String rawQuery = state.value("query").toString();
        Optional<Object> messagesOpt = state.value("messages");
        List<Message> messages;
        messages = messagesOpt.map(object -> (List<Message>) object).orElseGet(List::of);

        // 1. 异步并行任务
        CompletableFuture<String> resolvedQueryFuture = CompletableFuture.supplyAsync(() ->
                resolveCoreference(messages, rawQuery)
        );

        CompletableFuture<UserContext> userContextFuture = CompletableFuture.supplyAsync(() ->
                        loadUserContext(threadId)
                ).orTimeout(USER_CONTEXT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(th -> {
                    log.warn("User context load failed, using empty", th);
                    return UserContext.empty();
                });

        // 等待两个任务完成（最长等待3秒，整体超时由外部控制）
        CompletableFuture.allOf(resolvedQueryFuture, userContextFuture).join();

        String resolvedQuery = resolvedQueryFuture.join();
        UserContext userContext = userContextFuture.join();

        // 2. 构造增强上下文
        Map<String, Object> updates = new HashMap<>();
        updates.put("resolved_query", resolvedQuery);
        updates.put("user_context", userContext);

        // 可选：将增强信息也存到metadata，便于调试
        updates.put("preprocess_metadata", Map.of(
                "history_used", HISTORY_WINDOW,
                "context_loaded", userContext.isNotEmpty()
        ));

        log.info("Preprocess completed: session={}, resolved='{}', context={}",
                threadId, resolvedQuery, userContext);

        return updates;
    }

    private String resolveCoreference(List<Message> messages, String rawQuery) {
        try {
            if (messages == null || messages.isEmpty()) {
                return rawQuery;
            }

            // 取最近 N 轮
            List<String> history = extractRecentHistory(messages, HISTORY_WINDOW);

            return applyCoreferenceResolution(history, rawQuery);

        } catch (Exception e) {
            log.warn("Failed to resolve coreference, fallback to raw query", e);
            return rawQuery;
        }
    }

    private List<String> extractRecentHistory(List<Message> messages, int maxTurns) {
        // 从最新开始倒序提取，最多 maxTurns 轮（每轮可能包含 user + assistant）
        List<String> history = new ArrayList<>();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < maxTurns * 2; i--) {
            Message msg = messages.get(i);
            MessageType role = msg.getMessageType();
            String content = msg.getText();
            if (MessageType.USER.equals(role) || MessageType.ASSISTANT.equals(role)) {
                history.add(role + ": " + content);
                if (MessageType.USER.equals(role)) count++;
            }
        }
        Collections.reverse(history); // 按时间正序
        return history;
    }

    private String applyCoreferenceResolution(List<String> history, String query) {
        if (history.isEmpty()) return query;

        String prompt = """
请一步步思考：
根据用户的历史问答，判断当前问题是否需要做指代消解或省略补全？
如果回答[是]，则对当前问题进行指代消解或省略补全，然后输出处理后的问题。
如果回答[否]，则输出当前问题的原始内容。
历史问答：
%s
当前问题：
%s
请只输出改写后的完整问题，不要添加任何解释。
        """.formatted(String.join("\n", history), query);

        // 调用 chatModel 生成（同步或异步）
        String rewritten = chatModel.call(prompt);
        return rewritten.isEmpty() ? query : rewritten;
    }

    private UserContext loadUserContext(String sessionId) {
        // 预留接口
        return userContextService.getUserContext(sessionId);
    }
}