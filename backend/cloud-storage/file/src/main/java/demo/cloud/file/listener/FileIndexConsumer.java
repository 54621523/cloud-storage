package demo.cloud.file.listener;

import com.rabbitmq.client.Channel;
import demo.cloud.file.dto.FileSavedEvent;
import demo.cloud.file.service.FileManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileIndexConsumer {

    private final FileManagerService fileSearchService;

    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(name = "queue.file.index", durable = "true"),
                    exchange = @Exchange(name = "file.exchange", type = ExchangeTypes.TOPIC),
                    key = "file.saved"
            ),
            ackMode = "MANUAL"
    )
    public void handleFileSaved(FileSavedEvent event, Channel channel, Message message) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Long fileId = event.getUserFileId();
        try {
            log.info("接收到ES索引构建消息，fileId: {}", fileId);
            fileSearchService.addDocument(fileId, event.getType());
            log.info("ES索引构建完成，fileId: {}", fileId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("ES索引构建失败，fileId: {}, 错误: {}", fileId, e.getMessage(), e);
            // 抛出异常触发重试（若配置了重试策略）或转入死信队列人工介入
            channel.basicNack(deliveryTag, false, false);
            throw new AmqpRejectAndDontRequeueException("ES索引失败，转入死信", e);
        }
    }
}