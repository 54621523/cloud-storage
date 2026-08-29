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
        // TODO bucket写入库，做动态bucket

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
}
