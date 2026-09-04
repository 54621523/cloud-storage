package demo.cloud.file.listener;

import com.rabbitmq.client.Channel;
import demo.cloud.file.dto.FileDeleteEvent;
import demo.cloud.file.dto.FileRenameEvent;
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
import java.util.Collection;
import java.util.List;

import static demo.cloud.file.mq.RabbitExchangeConfig.EXCHANGE_NAME;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileIndexConsumer {

    public static final String SAVED_EVENT = "file.saved";
    public static final String DELETE_EVENT = "file.delete";
    public static final String RENAME_EVENT = "file.rename";

    private final FileManagerService fileSearchService;

    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(name = "queue.file.saved", durable = "true"),
                    exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
                    key = SAVED_EVENT
            ),
            ackMode = "MANUAL"
    )
    public void handleFileSaved(FileSavedEvent event, Channel channel, Message message) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        List<Long> ids= event.getIds();
        try {
            log.info("接收到搜索引擎索引构建消息");
            fileSearchService.addDocuments(ids, event.getType());
            log.info("搜索引擎索引构建完成");
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("搜索引擎索引构建失败，错误: {}",e.getMessage(), e);
            // 抛出异常触发重试（若配置了重试策略）或转入死信队列人工介入
            channel.basicNack(deliveryTag, false, false);
            throw new AmqpRejectAndDontRequeueException("ES索引失败，转入死信", e);
        }
    }

    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(name = "queue.file.delete", durable = "true"),
                    exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
                    key = DELETE_EVENT
            ),
            ackMode = "MANUAL"
    )
    public void handleFileDelete(FileDeleteEvent event, Channel channel, Message message) throws IOException{
        Long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Collection<Long> ids = event.getIds();
        try {
            log.info("接收到搜索引擎删除索引消息");
            fileSearchService.deleteDocuments(ids, event.getType());
            log.info("完成索引的删除");
            channel.basicAck(deliveryTag, false);
        }
        catch (Exception e) {
            log.error("搜索引擎索引构建失败，错误: {}",e.getMessage(), e);
            // 抛出异常触发重试（若配置了重试策略）或转入死信队列人工介入
            channel.basicNack(deliveryTag, false, false);
            throw new AmqpRejectAndDontRequeueException("ES索引失败，转入死信", e);
        }
    }

    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(name = "queue.file.rename", durable = "true"),
                    exchange = @Exchange(name = EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
                    key = RENAME_EVENT
            ),
            ackMode = "MANUAL"
    )
    public void handleFileRename(FileRenameEvent event, Channel channel, Message message) throws IOException {
        Long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            log.info("接收到搜索引擎重命名索引消息, id = {}, type = {}", event.getId(), event.getType());
            fileSearchService.renameDocument(event.getId(), event.getType(), event.getNewName());
            log.info("完成索引的重命名");
            channel.basicAck(deliveryTag, false);
        }
        catch (Exception e) {
            log.error("搜索引擎索引构建失败，错误: {}",e.getMessage(), e);
            // 抛出异常触发重试（若配置了重试策略）或转入死信队列人工介入
            channel.basicNack(deliveryTag, false, false);
            throw new AmqpRejectAndDontRequeueException("ES索引失败，转入死信", e);
        }

    }
}