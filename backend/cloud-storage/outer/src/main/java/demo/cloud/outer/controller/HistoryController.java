package demo.cloud.outer.controller;


import demo.cloud.common.pojo.CursorPageResult;
import demo.cloud.common.pojo.Result;
import demo.cloud.common.web.context.BaseContext;
import demo.cloud.outer.pojo.ChatMessage;
import demo.cloud.outer.pojo.ChatSession;
import demo.cloud.outer.service.ChatHistoryService;
import jakarta.annotation.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class HistoryController {


    private final ChatHistoryService chatHistoryService;

    public HistoryController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping("/list")
    public Result<CursorPageResult<ChatMessage>> listHistory(String sessionId, @Nullable String cursor){
        Long userId = BaseContext.getUserId();

        CursorPageResult<ChatMessage> page = chatHistoryService.findMessagesBySessionId(sessionId, userId, cursor);
        return Result.success(page);
    }

    @GetMapping("/list-session")
    public Result<CursorPageResult<ChatSession>> listSessions(@Nullable String cursor){
        Long userId = BaseContext.getUserId();

        CursorPageResult<ChatSession> chatSessions = chatHistoryService.listSessions(userId, cursor);
        return Result.success(chatSessions);
    }


    @DeleteMapping("/delete")
    public Result deleteSession(String sessionId){
        Long userId = BaseContext.getUserId();

        chatHistoryService.deleteSession(sessionId, userId);
        return Result.success();
    }
}
