package demo.cloud.ai.rag.listner;

import com.rabbitmq.client.Channel;
import demo.cloud.ai.rag.RAGDocumentProcessor;
import demo.cloud.file.dto.uploadv2.FileUploadEvent;
import demo.cloud.file.mq.RabbitExchangeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class DocumentMessageConsumer {

    @Autowired
    private RAGDocumentProcessor ragDocumentProcessor;


    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = "rag_channel", durable = "true"),
                    exchange = @Exchange(value = RabbitExchangeConfig.EXCHANGE_NAME, type = ExchangeTypes.TOPIC),
                    key = RabbitExchangeConfig.UPLOAD_EVENT
            ),
            ackMode = "MANUAL"
    )
    public void handleMessage(FileUploadEvent event, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            log.info("接收到文档RAG处理信息");
            ragDocumentProcessor.processDocumentFromS3(event.getBucket(), event.getOssKey());
            log.info("文档RAG处理完毕");
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("搜索引擎索引构建失败，错误: {}",e.getMessage(), e);
            // 抛出异常触发重试（若配置了重试策略）或转入死信队列人工介入
            channel.basicNack(deliveryTag, false, false);
            throw new AmqpRejectAndDontRequeueException("ES索引失败，转入死信", e);
        }
    }
}