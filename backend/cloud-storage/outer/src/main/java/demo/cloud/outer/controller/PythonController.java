package demo.cloud.outer.controller;

import demo.cloud.common.pojo.Result;
import demo.cloud.common.web.context.BaseContext;
import demo.cloud.file.dto.FilePhysicalDTO;
import demo.cloud.file.service.FileDubboService;
import demo.cloud.outer.dto.DocumentMetadata;
import demo.cloud.outer.dto.DocumentProcessRequest;
import demo.cloud.outer.dto.IngestRequest;
import demo.cloud.outer.pojo.ChatRequest;
import demo.cloud.outer.service.ChatService;
import demo.cloud.outer.service.SessionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI应用", description = "与AI交互的接口")
@RequiredArgsConstructor
public class PythonController {

    private final RestTemplate restTemplate;
    private final SessionManager sessionManager;
    private final ChatService chatService;

    @DubboReference
    private final FileDubboService userFileService;

    /**
     * 处理已有文件
     */
    @Operation(summary = "处理已有文件", description = "处理当前用户拥有的文档")
    @PostMapping("/process")
    public Result<String> processExistingDocument(@RequestBody DocumentProcessRequest request){

        Long id = request.getId();
        Long userId = BaseContext.getUserId();

        FilePhysicalDTO filePhysical = userFileService.getPhysicalFileByUserIdAndUserFileId(id, userId);
        if (filePhysical == null) {
            log.warn("文件不存在: userId={}, userFileId={}", userId, request.getId());
            return Result.error("文件不存在或已被删除");
        }

        DocumentMetadata metadata = DocumentMetadata.builder()
                .docId(filePhysical.getId())
                .build();
        IngestRequest ingestRequest = IngestRequest.builder()
                .bucket("documents")
                .ossKey(filePhysical.getOssKey())
                .metadata(metadata)
                .build();
        log.info("{}",ingestRequest.toString());
        log.info("{}", ingestRequest.getMetadata().toString());



        String serviceName = "fastapi-service";
        String url = "http://" + serviceName + "/documents/ingest";
        ResponseEntity<Map> response = restTemplate.postForEntity(url, ingestRequest, Map.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            log.info(response.toString());
            return Result.success();
        }
        return Result.error("python调用错误");
    }


    /**
     * 进行AI聊天
     */
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "AI对话", description = "流式输出并输出引用文档")
    @PostMapping(value = "/stream/chat",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        String message = request.getMessage();
        String sessionId = request.getSessionId();

        SecurityContext securityContext = SecurityContextHolder.getContext();
        Long userId = BaseContext.getUserId();

        // 创建SSE发射器，超时时间5分钟
        SseEmitter emitter = new SseEmitter(300000L);


        emitter.onTimeout(() -> {
            log.warn("前端 SSE 连接超时，userId: {}, sessionId: {}", userId, sessionId);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("data", "链接超时，请重试"))
                );
            } catch (IOException ignored) {}
            emitter.complete();
        });

        emitter.onCompletion(() -> {
            log.info("前端 SSE 连接正常关闭，userId: {}, sessionId: {}", userId, sessionId);
        });


        // 异步处理
        CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(securityContext);
            BaseContext.setUserId(userId);


            try {
                // 构建请求参数
                List<Long> allowedDocIds = userFileService.getAllowedDocIdsByUserId(userId);
                String validSessionId = sessionManager.createOrGetSession(userId, sessionId);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("message", message);
                requestBody.put("session_id", validSessionId);
                requestBody.put("allowed_doc_ids", allowedDocIds);
                emitter.send(SseEmitter.event()
                        .name("session_created")
                        .data(Map.of("data", validSessionId))
                );

                // 调用流式服务
                chatService.streamChat(requestBody, emitter, userId);

            } catch (Exception e) {
                log.error("聊天处理失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("处理失败: " + e.getMessage()));
                    emitter.completeWithError(e);
                } catch (IOException ex) {
                    log.error("发送错误事件失败", ex);
                }
            }finally {
                BaseContext.clear();
            }
        });

        return emitter;
    }

    /**
     * 模拟 AI 对话（测试用）
     * 不调用 Python，直接构造 Mock SSE 事件流
     */
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "模拟AI对话（测试）", description = "用于测试 SSE 流，不实际调用 Python 服务")
    @PostMapping(value = "/stream/chat/mock", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter mockStreamChat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();
        // 若前端未传 sessionId，则生成一个模拟值
        String validSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId : "mock-session-" + System.currentTimeMillis();

        SseEmitter emitter = new SseEmitter(300000L);

        // 异步发送模拟数据
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 发送 session_created 事件
                emitter.send(SseEmitter.event()
                        .name("session_created")
                        .data(Map.of("data", validSessionId)));

                // 模拟流式返回内容（分多个 chunk）
                String[] chunks = {
                        "这是模拟的",
                        "流式回复内容，",
                        "分多次发送。",
                        "可以测试前端展示效果。"
                };
                for (String chunk : chunks) {
                    // 每个 chunk 封装为 {content: chunk}
                    Map<String, String> data = Map.of("content", chunk);
                    emitter.send(SseEmitter.event()
                            .name("chunk")
                            .data(data));
                    Thread.sleep(300); // 模拟网络延迟
                }

                // 2. 发送 references 事件（模拟引用文档）
                List<Map<String, Object>> refs = List.of(
                        Map.of("doc_id", "101", "text", "这是文档1的片段", "page_number", 3),
                        Map.of("doc_id", "102", "text", "这是文档2的片段", "page_number", 7)
                );
                Map<String, Object> refPayload = Map.of("data", refs);
                emitter.send(SseEmitter.event()
                        .name("references")
                        .data(refPayload));

                // 3. 发送 final_answer 事件（完整回答）
                String fullAnswer = "这是完整的最终回答，包含所有内容。";
                Map<String, String> finalData = Map.of("content", fullAnswer);
                emitter.send(SseEmitter.event()
                        .name("final_answer")
                        .data(finalData));

                // 完成流
                emitter.complete();
            } catch (Exception e) {
                log.error("模拟 SSE 发送失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("模拟失败: " + e.getMessage()));
                    emitter.completeWithError(e);
                } catch (IOException ex) {
                    log.error("发送错误事件失败", ex);
                }
            }
        });

        return emitter;
    }
}
