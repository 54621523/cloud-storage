package demo.cloud.outer.listener;

import com.rabbitmq.client.Channel;
import demo.cloud.file.dto.uploadv2.FileUploadEvent;
import demo.cloud.outer.config.RagProperties;
import demo.cloud.outer.dto.DocumentMetadata;
import demo.cloud.outer.dto.IngestRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessConsumer {

    private final S3Client s3Client;
    private final RestTemplate restTemplate;
    private final Tika tika = new Tika(); // 单例复用

    @Autowired
    private RagProperties ragProperties;

    /**
     * 监听队列，处理文件
     * channel 和 message 用于手动 ACK
     */
    @RabbitListener(queues = "file.process.queue", ackMode = "MANUAL")
    public void handleFile(FileUploadEvent event, Channel channel, Message message) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        log.info("消费到文件处理任务: ossKey={}, physical={}", event.getOssKey(), event.getPhysicalId());

        try {
            // 1. 【MIME 检测】从 OSS 只读取前 8KB 头
            String mimeType = detectMimeType(event.getBucket(), event.getOssKey());
            log.info("真实 MIME 类型: {}", mimeType);

            // 2. 【白名单判断】
            if (!isMimeAllowed(mimeType)) {
                log.info("MIME 类型不符合白名单，跳过 RAG。physicalId: {}", event.getPhysicalId());
                // 更新数据库状态为 "SKIPPED"
                // 手动确认消息（从队列移除）
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 【调用 Python RAG 服务】
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .docId(event.getPhysicalId())
                    .build();
            IngestRequest ingestRequest = IngestRequest.builder()
                    .bucket("documents")
                    .ossKey(event.getOssKey())
                    .metadata(metadata)
                    .build();
            log.info("{}",ingestRequest.toString());
            log.info("{}", ingestRequest.getMetadata().toString());



            String serviceName = "fastapi-service";
            String url = "http://" + serviceName + "/documents/ingest";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, ingestRequest, Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                // Python 返回失败，抛异常触发重试
                throw new RuntimeException("Python RAG 服务返回异常状态码: " + response.getStatusCode());
            }
            log.info("RAG 处理成功, physicalId: {}", event.getPhysicalId());

            // 4. 【手动 ACK】确认消息已处理
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("处理文件失败, physicalId: {}, error: ", event.getPhysicalId(), e);
            try {
                /*
                  关键：拒绝并重新入队（requeue=true）会无限重试造成死循环。
                  建议：设置 requeue=false，将消息丢入死信队列（DLX），后续人工介入或延迟重试。
                 */
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                log.error("消息拒绝失败", ex);
            }
        }
    }

    private String detectMimeType(String bucket, String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .range("bytes=0-8191") // 仅读取头部
                    .build();
            try (ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(request)) {
                return tika.detect(s3Stream);
            }
        } catch (Exception e) {
            log.error("MIME 检测失败", e);
            return null;
        }
    }

    private boolean isMimeAllowed(String mimeType) {
        if (mimeType == null) return false;
        return ragProperties.getAllowedMimeTypes().contains(mimeType);
    }
}