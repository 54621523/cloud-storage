package demo.cloud.outer.listener;


import demo.cloud.outer.config.ChatRabbitConfig;
import demo.cloud.outer.pojo.ChatMessage;
import demo.cloud.outer.pojo.ChatMessagePair;
import demo.cloud.outer.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class ChatMessageConsumer {

    private final ChatHistoryService chatHistoryService;

    public ChatMessageConsumer(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @RabbitListener(queues = ChatRabbitConfig.CHAT_MESSAGE_QUEUE)
    public void handleMessage(ChatMessagePair messagePair) {

        if (messagePair == null ||
                messagePair.getUserMessage() == null ||
                messagePair.getAssistantMessage() == null) {
            log.warn("接收到无效的 ChatMessagePair，跳过落库");
            return;
        }

        List<ChatMessage> messages = Arrays.asList(
                messagePair.getUserMessage(),
                messagePair.getAssistantMessage()
        );
        try {
            chatHistoryService.saveMessagesBatch(
                    messagePair.getUserMessage().getSessionId(),
                    messages
            );
            log.debug("MQ 异步落库成功(一问一答), sessionId: {}",
                    messagePair.getUserMessage().getSessionId());
        } catch (Exception e) {
            String safeSessionId = messagePair.getUserMessage() != null
                    ? messagePair.getUserMessage().getSessionId() : null;
            log.error("MQ 异步落库失败, sessionId: {}", safeSessionId, e);
            throw new AmqpRejectAndDontRequeueException(e); // 拒绝并进入死信队列，避免无限重试
        }
    }
}