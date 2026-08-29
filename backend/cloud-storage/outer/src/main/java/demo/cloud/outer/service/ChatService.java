package demo.cloud.outer.service;

import demo.cloud.outer.config.ChatRabbitConfig;
import demo.cloud.outer.pojo.ChatMessage;
import demo.cloud.outer.pojo.ChatMessagePair;
import demo.cloud.file.service.FileDubboService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ChatService {

    private final WebClient webClient;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @DubboReference
    private final FileDubboService fileDubboService;

    public ChatService(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, FileDubboService fileDubboService) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.fileDubboService = fileDubboService;

        HttpClient httpClient = HttpClient.create()
                .protocol(HttpProtocol.HTTP11);
        this.webClient = WebClient.builder()
                .baseUrl("http://localhost:8000")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    /**
     * 流式处理聊天响应
     */
    public void streamChat(Map<String, Object> requestBody, SseEmitter emitter,Long userId) {
        AtomicBoolean emitterAlive = new AtomicBoolean(true);

        webClient.post()
                .uri("/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(requestBody))
                .retrieve()
                .onStatus(status -> status.value() == 422, response ->
                        response.bodyToMono(String.class).doOnNext(body ->
                                System.err.println("Python 422 详细错误: " + body) // 打印详细错误
                        ).then(Mono.error(new RuntimeException("Python 参数校验失败")))
                )
                .bodyToFlux(ServerSentEvent.class)
                .doOnNext(sse -> {
                    if (emitterAlive.get()) {
                        try {
                            // 处理 references 事件
                            if ("references".equals(sse.event()) && sse.data() != null) {
                                sse = processReferencesEvent(sse, userId);
                            }

                            SseEmitter.SseEventBuilder builder = SseEmitter.event();
                            if (sse.event() != null) builder.name(sse.event());
                            if (sse.data() != null) builder.data(sse.data());
                            emitter.send(builder);
                        } catch (IllegalStateException e) {
                            log.debug("emitter已断开, 处理剩余数据");
                            emitterAlive.set(false);
                            if (isClientDisconnected(e)) {
                                // 不继续抛异常，仅终止后续处理
                            }
                        } catch (Exception e) {
                            log.error("透传 SSE 数据失败: {}", e.getMessage());
                            emitterAlive.set(false);
                        }
                    }else {
                        log.trace("emitter已降级, 忽略剩余事件");
                    }
                })
                .doOnNext(sse -> {
                    // 3. 【旁路处理】在转发的同时，仅针对特定事件做业务处理（如入库）
                    if ("final_answer".equals(sse.event()) && sse.data() != null) {
                        log.info("正在进行聊天记录异步入库");
                        String content = extractContent(sse.data());
                        sendToMq(content, requestBody);

                    }
                })
                .doOnComplete(() -> {
                    try {
                        emitter.complete();
                    } catch (IllegalStateException e) {
                        // 如果 emitter 已处于错误状态，忽略（避免再次触发错误）
                        log.debug("emitter already completed/error, ignoring complete call");
                    }
                })
                .doOnError(e -> {
                    log.info("调用 Python 异常: {}", e.getMessage());
                    if (isClientDisconnected(e)) {
                        // 客户端断开，不发送错误事件，直接完成
                        try {
                            emitter.complete();
                        } catch (Exception ignored) {}
                    } else {
                        try {
                            emitter.send(SseEmitter.event().name("error").data("{\"message\":\"AI服务异常\"}"));
                        } catch (Exception ignored) {}
                        emitter.completeWithError(e);
                    }
                })
                .subscribe();
    }

    private boolean isClientDisconnected(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg != null && (msg.contains("broken pipe") || msg.contains("connection reset")
                || msg.contains("你的主机中的软件中止了一个已建立的连接"))) {
            return true;
        }
        if (e.getCause() != null) {
            return isClientDisconnected(e.getCause());
        }
        return false;
    }


    private void sendToMq(String lastestAssistantMessage, Map<String, Object> requestBody) {
        if (lastestAssistantMessage == null || lastestAssistantMessage.isEmpty()) {
            log.warn("检测到最终回复为空，跳过 MQ 投递");
            return;
        }
        String sessionId = requestBody.get("session_id").toString();
        String content = requestBody.get("message").toString();


        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("user");
        userMessage.setContent(content);
        userMessage.setCreateTime(LocalDateTime.now());

        ChatMessage llmResult = new ChatMessage();
        llmResult.setSessionId(sessionId);
        llmResult.setRole("assistant");
        llmResult.setContent(lastestAssistantMessage);
        llmResult.setCreateTime(LocalDateTime.now());

        ChatMessagePair chatMessagePair = new ChatMessagePair(userMessage, llmResult);

        try {
            rabbitTemplate.convertAndSend(
                    ChatRabbitConfig.CHAT_MESSAGE_EXCHANGE,
                    "message.save",
                    chatMessagePair
            );
        } catch (Exception e) {
            log.error("消息投递 MQ 失败, sessionId: {}", sessionId, e);
            //TODO 重试逻辑
        }
    }


    private String extractContent(Object data) {
        if (data == null) {
            return "";
        }
        // 场景1：标准 JSON 对象 -> 转为 Map 取 content
        if (data instanceof Map) {
            Object content = ((Map<?, ?>) data).get("content");
            return content != null ? content.toString() : "";
        }
        // 场景2：如果 AI 直接返回纯文本（data 本身就是 String）
        if (data instanceof String) {
            // 极端情况：如果返回的字符串恰好是 "{content=xxx}" 这种非标格式，兼容处理
            String str = (String) data;
            if (str.startsWith("{content=") && str.endsWith("}")) {
                return str.substring(9, str.length() - 1); // 简单截取
            }
            return str;
        }
        // 场景3：其他未知类型，作为兜底
        log.warn("未知的 data 类型: {}", data.getClass());
        return data.toString();
    }

    @SuppressWarnings("unchecked")
    private ServerSentEvent processReferencesEvent(ServerSentEvent originalEvent, Long userId) {
        try {
            Object dataObj = originalEvent.data();

            // 处理 data 为 Map 的情况
            if (dataObj instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) dataObj;

                // 获取 data 数组
                Object dataArrayObj = dataMap.get("data");
                if (dataArrayObj instanceof List) {
                    List<?> dataList = (List<?>) dataArrayObj;
                    List<Map<String, Object>> frontendChunks = new ArrayList<>();

                    for (Object item : dataList) {
                        if (item instanceof Map) {
                            Map<String, Object> pythonChunk = (Map<String, Object>) item;
                            Map<String, Object> frontendChunk = new HashMap<>();

                            log.info("处理前 chunk: {}", pythonChunk);

                            // 1. doc_ids → filename（必需）
                            Object docIdsObj = pythonChunk.get("doc_id");
                            if (docIdsObj != null) {
                                try {
                                    String docId = docIdsObj.toString();
                                    log.info("查询 filename, docId: {}", docId);
                                    String filename = fileDubboService.getFilenameByPhysicalIdWithUserId(Long.valueOf(docId), userId);
                                    frontendChunk.put("filename", filename != null ? filename : "未知文档");
                                    log.info("查询到 filename: {}", filename);
                                } catch (Exception e) {
                                    log.error("查询 filename 失败: {}", e.getMessage(), e);
                                    frontendChunk.put("filename", "未知文档");
                                }
                            } else {
                                frontendChunk.put("filename", "未知文档");
                            }

                            // 2. text 字段（必需）
                            Object textObj = pythonChunk.get("text");
                            frontendChunk.put("text", textObj != null ? textObj.toString() : "");

                            // 3. page_number 字段（可选）
                            Object pageObj = pythonChunk.get("page_number");
                            if (pageObj != null) {
                                frontendChunk.put("page_number", pageObj);
                            }

                            log.info("处理后 chunk: {}", frontendChunk);
                            frontendChunks.add(frontendChunk);
                        }
                    }

                    // 构建前端期望的数据结构
                    Map<String, Object> response = new HashMap<>();
                    response.put("data", frontendChunks);

                    // 重新构建 ServerSentEvent
                    ServerSentEvent newSse = ServerSentEvent.builder()
                            .event(originalEvent.event())
                            .id(originalEvent.id())
                            .comment(originalEvent.comment())
                            .retry(originalEvent.retry())
                            .data(response)
                            .build();

                    return newSse;
                }
            }
        } catch (Exception e) {
            log.error("处理 references 事件失败: {}", e.getMessage(), e);
        }
        return originalEvent;
    }
}