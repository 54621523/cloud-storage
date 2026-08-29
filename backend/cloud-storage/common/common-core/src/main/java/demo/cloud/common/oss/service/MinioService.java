package demo.cloud.common.oss.service;

import demo.cloud.common.pojo.StorageResult;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MinIO 对象存储服务
 * 提供文件上传、下载、删除、分片上传等功能
 */
@Service
@Slf4j
@ConditionalOnBean(MinioClient.class)
public class MinioService {

    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${minio.url-expiration:365}")
    private int urlExpirationDays;

    @Value("${minio.endpoint}")
    private String endpoint;

    public MinioService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @PostConstruct
    public void init() {
        ensureBucketExists();
    }

    /**
     * 确保存储桶存在
     */
    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
            );
            
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucketName).build()
                );
                log.info("[MinIO] 创建存储桶成功: {}", bucketName);
            } else {
                log.info("[MinIO] 存储桶已存在: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("[MinIO] 检查/创建存储桶失败", e);
            throw new RuntimeException("MinIO 存储桶初始化失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 上传单个分片到MinIO
     *
     * @param objectName 对象名称
     * @param inputStream 分片输入流
     * @param size 分片大小
     * @param contentType 内容类型
     * @return 上传成功后的对象名称
     */
    public String uploadChunk(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build();
            
            minioClient.putObject(putObjectArgs);
            log.info("[MinIO] 分片上传成功: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.error("[MinIO] 分片上传失败: {}", objectName, e);
            throw new RuntimeException("分片上传失败: " + e.getMessage(), e);
        }


    }

    /**
     * 合并分片为完整文件
     * 使用MinIO的composeObject API进行服务端分片合并
     *
     * @param targetObjectName 目标对象名称
     * @param chunkObjectNames 分片对象名称列表（按顺序）
     * @return 合并后的对象名称
     */
    public StorageResult mergeChunks(String targetObjectName, List<String> chunkObjectNames) {




        try {
            for (String chunkObjectName : chunkObjectNames) {
                boolean exists = minioClient.statObject(
                        StatObjectArgs.builder()
                                .bucket(bucketName)
                                .object(chunkObjectName)
                                .build()
                ) != null;

                if (!exists) {
                    throw new RuntimeException("分片缺失，无法合并: " + chunkObjectName);
                }
            }


            // 构建Compose源列表
            List<ComposeSource> composeSources = new ArrayList<>();
            for (String chunkObjectName : chunkObjectNames) {
                composeSources.add(
                    ComposeSource.builder()
                        .bucket(bucketName)
                        .object(chunkObjectName)
                        .build()
                );
            }

            // 使用composeObject合并分片
            ComposeObjectArgs composeArgs = ComposeObjectArgs.builder()
                    .bucket(bucketName)
                    .object(targetObjectName)
                    .sources(composeSources)
                    .build();
            
            minioClient.composeObject(composeArgs);
            log.info("[MinIO] 分片合并成功: {}", targetObjectName);

            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(targetObjectName)
                    .build());
            
            // 合并成功后删除临时分片对象
            deleteChunks(chunkObjectNames);
            
            return new StorageResult(bucketName, targetObjectName, stat.size());
        } catch (Exception e) {
            log.error("[MinIO] 分片合并失败: {}", targetObjectName, e);
            throw new RuntimeException("分片合并失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除临时分片对象
     *
     * @param chunkObjectNames 分片对象名称列表
     */
    private void deleteChunks(List<String> chunkObjectNames) {
        try {
            Iterable<io.minio.Result<io.minio.messages.DeleteError>> results = minioClient.removeObjects(
                io.minio.RemoveObjectsArgs.builder()
                    .bucket(bucketName)
                    .objects(chunkObjectNames.stream()
                        .map(DeleteObject::new)
                        .collect(java.util.stream.Collectors.toList()))
                    .build()
            );
            
            // 检查删除结果
            for (var result : results) {
                io.minio.messages.DeleteError error = result.get();
                log.warn("[MinIO] 删除分片失败: {}, 错误: {}", error.objectName(), error.message());
            }
            
            log.info("[MinIO] 临时分片删除完成");
        } catch (Exception e) {
            log.error("[MinIO] 删除临时分片失败", e);
        }
    }

    /**
     * 获取文件输入流
     * 用于从MinIO下载文件并返回InputStream，支持流式处理
     *
     * @param objectName 对象名称
     * @return 文件输入流
     */
    public InputStream getFileStream(String objectName) {
        try {
            return minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build()
            );
        } catch (Exception e) {
            log.error("[MinIO] 获取文件流失败: {}", objectName, e);
            throw new RuntimeException("获取文件流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取文件信息（包括文件名、大小、类型等）
     *
     * @param objectName 对象名称
     * @return 文件信息Map
     */
    public Map<String, String> getFileInfo(String objectName) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build()
            );
            // 将Headers转换为Map
            Map<String, String> headersMap = new HashMap<>();
            for (String name : stat.headers().names()) {
                headersMap.put(name, stat.headers().get(name));
            }
            // 添加额外信息
            headersMap.put("fileName", extractFileName(objectName));
            headersMap.put("size", String.valueOf(stat.size()));
            headersMap.put("contentType", stat.contentType());
            return headersMap;
        } catch (Exception e) {
            log.error("[MinIO] 获取文件信息失败: {}", objectName, e);
            throw new RuntimeException("获取文件信息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从对象路径中提取文件名
     *
     * @param objectName 对象名称（如：files/category/sessionId/filename.pdf）
     * @return 文件名
     */
    private String extractFileName(String objectName) {
        if (objectName == null || objectName.isEmpty()) {
            return "";
        }
        int lastSlash = objectName.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < objectName.length() - 1) {
            return objectName.substring(lastSlash + 1);
        }
        return objectName;
    }


    public String getDownloadUrl(String objectName, int expiry, TimeUnit timeUnit) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry(expiry, timeUnit)
                            .build()
            );
        } catch (Exception e) {
            log.error("[MinIO] 获取预签名URL失败: {}", objectName, e);
            throw new RuntimeException("获取预签名URL失败: " + e.getMessage(), e);
        }
    }

    public String getUploadUrl(String objectName, int expiry, TimeUnit timeUnit) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry(expiry, timeUnit)
                            .build()
            );
        } catch (Exception e) {
            log.error("[MinIO] 获取预签名URL失败: {}", objectName, e);
            throw new RuntimeException("获取预签名URL失败: " + e.getMessage(), e);
        }
    }


    /**
     * 删除文件
     *
     * @param objectName 对象名称
     */
    public void deleteFile(String objectName) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build()
            );
            log.info("[MinIO] 文件删除成功: {}", objectName);
        } catch (Exception e) {
            log.error("[MinIO] 文件删除失败: {}", objectName, e);
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量删除对象
     */
    public void batchDeleteObjects(List<String> objectNames) {
        if (objectNames == null || objectNames.isEmpty()) {
            return;
        }

        try {
            List<DeleteObject> objects = objectNames.stream()
                    .map(DeleteObject::new)
                    .collect(Collectors.toList());

            RemoveObjectsArgs args = RemoveObjectsArgs.builder()
                    .bucket(bucketName)
                    .objects(objects)
                    .build();

            // 执行批量删除
            Iterable<Result<DeleteError>> results = minioClient.removeObjects(args);

            // 检查删除结果
            for (Result<DeleteError> result : results) {
                DeleteError error = result.get();
                if (error != null) {
                    log.warn("删除对象失败: {}, 错误: {}", error.objectName(), error.message());
                }
            }

            log.info("批量删除成功，数量: {}", objectNames.size());
        } catch (Exception e) {
            log.error("批量删除失败", e);
            throw new RuntimeException("批量删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检查对象是否存在
     *
     * @param objectName 对象名称
     * @return 是否存在
     */
    public boolean objectExists(String objectName) {
        try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 MinIO endpoint（去除尾部斜杠）
     */
    private String endpointWithoutTrailingSlash() {
        return endpoint.replaceAll("/$", "");
    }
}
