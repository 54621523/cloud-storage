package demo.cloud.common.oss.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储配置
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "minio", name = "endpoint") // 只有当配置文件中存在 minio.endpoint 时才生效
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            
            log.info("[MinIO] 客户端初始化成功, endpoint: {}", endpoint);
            return client;
        } catch (Exception e) {
            log.error("[MinIO] 客户端初始化失败", e);
            throw new RuntimeException("MinIO 客户端初始化失败: " + e.getMessage(), e);
        }
    }
}
