package demo.cloud.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:9000"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("Nqb679iLCkotjkBVtt1S", "vddSoo77cUkR9oD9MMxv7isLUJZQ1g4VBBJSiveb")))
                .region(Region.US_EAST_1)   // MinIO 不关心 region，但 SDK 要求必须设置
                .forcePathStyle(true)        // MinIO 必须开启路径风格（关键！）
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(){
        return S3Presigner.builder()
                .endpointOverride(URI.create("http://localhost:9000"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("Nqb679iLCkotjkBVtt1S", "vddSoo77cUkR9oD9MMxv7isLUJZQ1g4VBBJSiveb")))
                .region(Region.US_EAST_1)   // MinIO 不关心 region，但 SDK 要求必须设置
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
