package demo.cloud.outer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.common.pojo.CursorPageResult;
import demo.cloud.outer.mapper.ChatMessageMapper;
import demo.cloud.outer.mapper.ChatSessionMapper;
import demo.cloud.outer.pojo.ChatMessage;
import demo.cloud.outer.pojo.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
public class ChatHistoryService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    public ChatHistoryService(ChatSessionMapper chatSessionMapper,
                              ChatMessageMapper chatMessageMapper) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
    }


    @Transactional(rollbackFor = Exception.class)
    public void saveMessagesBatch(String sessionId, List<ChatMessage> messages) {
            if (messages == null || messages.isEmpty()) {
                return;
            }


            for (ChatMessage msg : messages) {
                chatMessageMapper.insert(msg);
            }

            // 2. 更新会话的消息数量（按主键选择性更新）
            chatSessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                    .eq(ChatSession::getSessionId, sessionId)
                    .setIncrBy(ChatSession::getMessageCount,messages.size())
            );
            log.debug("批量保存消息成功, sessionId: {}, 数量: {}", sessionId, messages.size());
    }

    /**
     * 根据 SessionId 查询聊天记录并按时间正序排列
     */
    public CursorPageResult<ChatMessage> findMessagesBySessionId(String sessionId, Long userId, String cursor) {

        Long lastId = decodeCursor(cursor);
        boolean exists = chatSessionMapper.exists(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getSessionId, sessionId)
                        .eq(ChatSession::getUserId, userId)
        );
        if(!exists){
            throw new BusinessException(0,"没有访问权限");
        }


        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByDesc(ChatMessage::getCreateTime)
                .last("Limit 21");
        if(lastId != null){
            wrapper.lt(ChatMessage::getId, lastId);
        }
        List<ChatMessage> messages = chatMessageMapper.selectList(wrapper);

        boolean hasNext = messages.size() > 20;
        List<ChatMessage> list = hasNext ? messages.subList(0, 20) : messages;

        String nextCursor = null;
        if (!list.isEmpty()) {
            ChatMessage last = list.get(list.size() - 1);
            nextCursor = encodeCursor(last.getId());
        }
        CursorPageResult<ChatMessage> page = new CursorPageResult<>(list, nextCursor, hasNext);
        return page;
    }

    /**
     * 删除会话及其所有消息
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId, Long userId) {
        log.info("删除会话及其消息, sessionId: {}, userId: {}", sessionId, userId);

        // 1. 查询会话是否存在且属于该用户（同时加载出来，用于后续判断）
        ChatSession session = chatSessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getSessionId, sessionId)
                        .eq(ChatSession::getUserId, userId)
        );
        if (session == null) {
            throw new BusinessException(0,"会话不存在或无权限");
        }

        // 2. 删除该会话下的所有消息（物理删除或逻辑删除，根据业务）
        chatMessageMapper.delete(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
        );

        // 3. 删除会话本身
        chatSessionMapper.deleteById(session.getId()); // 或使用 sessionId 条件删除

        log.info("删除成功, sessionId: {}", sessionId);
    }

    public CursorPageResult<ChatSession> listSessions(Long userId, String cursor) {
        Long lastId = decodeCursor(cursor);

        LambdaQueryWrapper<ChatSession> wrapper =
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getCreateTime)
                        .last("Limit 21");
        if(lastId != null){
            wrapper.lt(ChatSession::getId, lastId);
        }

        List<ChatSession> chatSessions = chatSessionMapper.selectList(wrapper);

        boolean hasNext = chatSessions.size() > 20;
        List<ChatSession> list = hasNext ? chatSessions.subList(0, 20) : chatSessions;
        String nextCursor = null;
        if (!list.isEmpty()) {
            ChatSession last = list.get(list.size() - 1);
            nextCursor = encodeCursor(last.getId());
        }
        CursorPageResult<ChatSession> page = new CursorPageResult<>(list, nextCursor, hasNext);
        return page;
    }

    public static String encodeCursor(Long id) {
        if (id == null) return null;
        String raw = id.toString();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
            Long lastId = Long.valueOf(decoded);
            return lastId;
        } catch (Exception e) {
            return null;
        }
    }
}