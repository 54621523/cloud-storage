package demo.cloud.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import demo.cloud.auth.dubboService.UserQuotaDubboService;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.FileSavedEvent;
import demo.cloud.file.dto.uploadv2.*;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.pojo.UserFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadV2Service {

    // ====== Constants ======
    private static final long DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;
    private static final String BUCKET_NAME = "documents";
    private static final String EXCHANGE_NAME = "file.exchange";
    private static final String ROUTING_KEY = "file.process";
    private static final DateTimeFormatter DATE_PATH_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    // ====== Dependencies ======
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final FilePhysicalService filePhysicalService;
    private final UserFileService userFileService;
    private final UserFolderService userFolderService;
    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    @DubboReference(version = "1.0.0", timeout = 3000, retries = 3)
    private UserQuotaDubboService quotaDubboService;

    // ========================================
    // ====== CREATE ======
    // ========================================

    @Transactional(rollbackFor = Exception.class)
    public InitResponseV2 initUpload(InitRequestV2 request, Long userId) {
        log.info("初始化/续传请求，文件名: {}, 大小: {}, uploadId: {}",
                request.getFileName(), request.getFileSize(), request.getUploadId());
        long fileSize = request.getFileSize();

        // 配额检查（前置校验）
        if (!quotaDubboService.hasEnoughQuota(userId, fileSize)) {
            throw new BusinessException(0, "空间不足");
        }

        Long parentId = resolveParentId(request, userId);

        // 1. 秒传判断
        if (request.getFileHash() != null) {
            FilePhysical existing = filePhysicalService.getOne(
                    new LambdaUpdateWrapper<FilePhysical>()
                            .eq(FilePhysical::getMd5, request.getFileHash())
                            .last("LIMIT 1")
            );
            if (existing != null) {
                filePhysicalService.increaseRef(existing.getId());
                UserFile userFile = createUserFile(userId, parentId, request.getFileName(), fileSize, existing.getId());
                registerFileSavedEvent(userFile.getId(), userId, request.getFileName());
                return InitResponseV2.builder().isComplete(true).build();
            }
        }

        // 2. 0字节文件
        if (fileSize == 0) {
            Long physicalId = filePhysicalService.getOrCreatePhysicalId(
                    "d41d8cd98f00b204e9800998ecf8427e", 0L, null
            );
            UserFile userFile = createUserFile(userId, parentId, request.getFileName(), 0L, physicalId);
            registerFileSavedEvent(userFile.getId(), userId, request.getFileName());
            return InitResponseV2.builder().isComplete(true).build();
        }

        // 3. 正常分片上传初始化
        long chunkSize = request.getChunkSize() != null ? request.getChunkSize() : DEFAULT_CHUNK_SIZE;
        String targetKey = buildTargetObjectName(request.getFileName());

        String uploadId;
        Set<Integer> uploadedPartNumbers = new HashSet<>();
        boolean isComplete = false;
        boolean isNewUpload = false;

        if (request.getUploadId() != null && !request.getUploadId().isEmpty()) {
            // 续传
            uploadId = request.getUploadId();
            try {
                ListPartsRequest listPartsRequest = ListPartsRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(targetKey)
                        .uploadId(uploadId)
                        .build();
                ListPartsResponse listPartsResponse = s3Client.listParts(listPartsRequest);
                uploadedPartNumbers = listPartsResponse.parts().stream()
                        .map(Part::partNumber)
                        .collect(Collectors.toSet());

                int totalParts = (int) Math.ceil((double) fileSize / chunkSize);
                isComplete = uploadedPartNumbers.size() == totalParts;
                if (isComplete) {
                    return buildResumeResponse(uploadId, uploadedPartNumbers, Collections.emptyList(), chunkSize, true);
                }
            } catch (NoSuchUploadException e) {
                log.warn("uploadId {} 不存在，将创建新上传", uploadId);
                uploadId = createNewMultipartUpload(targetKey);
                isNewUpload = true;
            } catch (Exception e) {
                log.error("查询分片失败", e);
                throw new RuntimeException("续传查询失败", e);
            }
        } else {
            // 新建
            uploadId = createNewMultipartUpload(targetKey);
            isNewUpload = true;
        }

        // 生成未上传分片的预签名 URL
        int totalParts = (int) Math.ceil((double) fileSize / chunkSize);
        List<String> presignedUrls = generatePresignedUrls(targetKey,uploadId, uploadedPartNumbers, totalParts);

        // 新建时记录物理表和Redis会话
        if (isNewUpload) {
            filePhysicalService.getOrCreatePhysicalId(request.getFileHash(), fileSize, targetKey);
            storeUploadSession(uploadId, userId, request.getFileName(), parentId, targetKey, request.getFileHash());
        }
        return buildResumeResponse(uploadId, uploadedPartNumbers, presignedUrls, chunkSize, isComplete);

    }

    @GlobalTransactional(rollbackFor = Exception.class, name = "minus-quota")
    public String completeMultipartUpload( MergeRequestV2 request, Long userId) {
        // 1. 转换 CompletedPart
        List<CompletedPart> completedParts = request.getParts().stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.getPartNumber())
                        .eTag(part.getETag())
                        .build())
                .sorted(Comparator.comparingInt(CompletedPart::partNumber))
                .collect(Collectors.toList());

        completedParts.forEach(p ->
                log.info("Part {} ETag: '{}'", p.partNumber(), p.eTag())
        );

        // 2. 获取会话信息
        String redisKey = "upload:session:" + request.getUploadId();
        Map<Object, Object> session = redisTemplate.opsForHash().entries(redisKey);
        if (session.isEmpty()) {
            throw new BusinessException(400, "上传会话不存在或已过期");
        }
        Long storedUserId = Long.valueOf(session.get("userId").toString());
        if (!storedUserId.equals(userId)) {
            log.warn("用户 {} 尝试使用他人上传会话 {}", userId, request.getUploadId());
            throw new BusinessException(403, "无权操作此上传");
        }
        String targetKey = session.get("ossKey").toString();
        String md5 = session.get("md5").toString();

        // 3. 构造合并请求
        CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
                .parts(completedParts)
                .build();

        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket(BUCKET_NAME)
                .key(targetKey)
                .uploadId(request.getUploadId())
                .multipartUpload(completedUpload)
                .build();

        try {
            // 4. 执行合并
            s3Client.completeMultipartUpload(completeRequest);

            // 5. 获取真实大小（安全防护：覆盖前端伪造大小）
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(targetKey)
                    .build();
            HeadObjectResponse headResponse = s3Client.headObject(headRequest);
            Long actualFileSize = headResponse.contentLength();

            // 6. 更新物理文件大小
            FilePhysical physical = filePhysicalService.getOne(
                    new LambdaQueryWrapper<FilePhysical>()
                            .eq(FilePhysical::getMd5, md5)
                            .last("LIMIT 1")
            );
            if (physical == null) {
                throw new BusinessException(0, "物理文件记录不存在");
            }
            physical.setSize(actualFileSize);
            filePhysicalService.updateById(physical);

            // 7. 创建用户文件记录
            Long parentId = Long.valueOf(
                    Objects.requireNonNull(redisTemplate.opsForHash().get(redisKey, "parentId")).toString()
            );
            String fileName = Objects.requireNonNull(
                    redisTemplate.opsForHash().get(redisKey, "fileName")
            ).toString();

            UserFile userFile = createUserFile(userId, parentId, fileName, actualFileSize, physical.getId());
            registerFileSavedEvent(userFile.getId(), userId, fileName);

            // 8. 扣减配额（远程调用）
            boolean success = quotaDubboService.addUsedQuota(userId, actualFileSize);
            if (!success) {
                log.warn("配额扣减失败，可能发生并发冲突，userId={}, size={}", userId, actualFileSize);
                throw new BusinessException(0, "配额更新失败，请重试");
            }

            // 9. 发送MQ事件触发后续处理（RAG等）
            registerFileUploadEvent(physical.getId(), targetKey, fileName, userId, request.getUploadId());
            // 10. 清理Redis会话
            redisTemplate.delete(redisKey);
            return null;
        } catch (S3Exception e) {
            log.error("合并失败，uploadId: {}, key: {}", request.getUploadId(), targetKey, e);
            // 1. 清理 S3 分片
            try {
                AbortMultipartUploadRequest abortRequest = AbortMultipartUploadRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(targetKey)
                        .uploadId(request.getUploadId())
                        .build();
                s3Client.abortMultipartUpload(abortRequest);
                log.info("已终止上传并清理分片，uploadId: {}", request.getUploadId());
            } catch (Exception abortEx) {
                log.error("清理分片失败，uploadId: {}", request.getUploadId(), abortEx);
                // 清理失败不影响主异常抛出，但可记录监控
            }
            // 2. 通知前端具体哪个文件失败（从会话中获取文件名）
            String fileName = Objects.toString(redisTemplate.opsForHash().get(redisKey, "fileName"), "未知文件");
            throw new BusinessException(500, "文件 [" + fileName + "] 合并失败，请重新上传");
        } catch (Exception e) {
            // 其他异常同样处理，但可能需要区分是否需清理
            log.error("合并过程发生未知异常", e);
            // 尝试清理分片（但有可能未获取到 targetKey，需谨慎）
            // 建议仅当 S3Exception 时清理，其他异常可能尚未开始合并，可选择性清理
            throw new BusinessException(500, "系统异常，请重试");
        }
    }

    // ========================================
    // ====== READ ======
    // ========================================

    public List<PartInfo> listParts( String bucket,
                                     String key,
                                     String uploadId) {
        log.info("查询分片信息，bucket: {}, key: {}, uploadId: {}", bucket, key, uploadId);

        ListPartsRequest listRequest = ListPartsRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .build();

        try {
            ListPartsResponse response = s3Client.listParts(listRequest);
            return response.parts().stream()
                    .map(part -> new PartInfo(part.partNumber(), part.eTag()))
                    .collect(Collectors.toList());
        } catch (S3Exception e) {
            log.error("查询分片失败: {}", e.getMessage());
            throw new RuntimeException("查询分片失败: " + e.awsErrorDetails().errorMessage());
        }
    }
    
    public String generateDownloadUrl( Long virtualFileId, Long userId) {
        MPJLambdaWrapper<UserFile> wrapper = new MPJLambdaWrapper<UserFile>()
                .selectAs(UserFile::getName, "displayName")
                .selectAs(FilePhysical::getOssKey, "ossKey")
                .innerJoin(FilePhysical.class, FilePhysical::getId, UserFile::getPhysicalId)
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getId, virtualFileId)
                .last("LIMIT 1");

        Map<String, Object> result = userFileService.selectJoinMap(wrapper);
        if (result == null) {
            throw new BusinessException(0, "文件不存在或无权限");
        }

        String displayName = (String) result.get("displayName");
        String ossKey = (String) result.get("ossKey");

        String encodedFileName = URLEncoder.encode(displayName, StandardCharsets.UTF_8);
        String contentDisposition = "attachment; filename=\"" + encodedFileName +
                "\"; filename*=UTF-8''" + encodedFileName;

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(ossKey)
                .responseContentDisposition(contentDisposition)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        String presignedUrl = presignedRequest.url().toExternalForm();
        log.info("生成下载预签名URL: {}", presignedUrl);
        return presignedUrl;
    }

    // ========================================
    // ====== PRIVATE METHODS ======
    // ========================================

    private String createNewMultipartUpload(String key) {
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(key)
                        .build()
        );
        return response.uploadId();
    }

    private InitResponseV2 buildResumeResponse(String uploadId, Set<Integer> uploadedParts,
                                               List<String> presignedUrls, long chunkSize, boolean isComplete) {
        return InitResponseV2.builder()
                .uploadId(uploadId)
                .chunkSize(chunkSize)
                .presignedUrls(presignedUrls)
                .uploadedChunks(uploadedParts)
                .isComplete(isComplete)
                .build();
    }

    private String buildTargetObjectName(String originalFileName) {
        String extension = extractExtension(originalFileName);
        String uuid = UUID.randomUUID().toString();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String datePath = LocalDate.now().format(DATE_PATH_FORMATTER);
        return String.format("files/%s/%s_%s%s", datePath, timestamp, uuid, extension);
    }

    private String extractExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }

    private String extractDirPath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return "";
        if (relativePath.endsWith("/")) {
            return relativePath.substring(0, relativePath.length() - 1);
        }
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash > 0 ? relativePath.substring(0, lastSlash) : "";
    }

    private Long resolveParentId(InitRequestV2 request, Long userId) {
        String dirPath = extractDirPath(request.getRelativePath());
        if (dirPath.isEmpty()) {
            return request.getParentId();
        }
        Map<String, Long> pathToId = userFolderService.saveFolders(userId, request.getParentId(), dirPath);
        return pathToId.getOrDefault(dirPath, request.getParentId());
    }

    private void sendFileSavedEvent(Long fileId, Long userId, String fileName) {
        FileSavedEvent event = FileSavedEvent.builder()
                .userFileId(fileId)
                .userId(userId)
                .fileName(fileName)
                .type(FileItemType.FILE)
                .build();
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, "file.saved", event);
    }

    private UserFile createUserFile(Long userId, Long parentId, String fileName, Long fileSize, Long physicalId) {
        UserFile userFile = new UserFile();
        userFile.setSize(fileSize);
        userFile.setName(fileName);
        userFile.setParentId(parentId);
        userFile.setUserId(userId);
        userFile.setPhysicalId(physicalId);
        userFileService.saveFile(userFile);
        return userFile;
    }

    private void registerFileSavedEvent(Long userFileId, Long userId, String fileName) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sendFileSavedEvent(userFileId, userId, fileName);
                    }
                }
        );
    }

    private void registerFileUploadEvent(Long physicalId, String targetKey, String fileName,
                                         Long userId, String uploadId) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        FileUploadEvent event = FileUploadEvent.builder()
                                .physicalId(physicalId)
                                .bucket(BUCKET_NAME)
                                .ossKey(targetKey)
                                .fileName(fileName)
                                .userId(userId)
                                .uploadId(uploadId)
                                .build();
                        rabbitTemplate.convertAndSend(EXCHANGE_NAME, ROUTING_KEY, event);
                    }
                }
        );
    }

    /**
     * 存储上传会话到 Redis（提取自 init 中的新建逻辑）
     */
    private void storeUploadSession(String uploadId, Long userId, String fileName,
                                    Long parentId, String targetKey, String md5) {
        String redisKey = "upload:session:" + uploadId;
        Map<String, Object> sessionMap = new HashMap<>();
        sessionMap.put("userId", userId);
        sessionMap.put("fileName", fileName);
        sessionMap.put("parentId", parentId);
        sessionMap.put("ossKey", targetKey);
        sessionMap.put("md5", md5);
        redisTemplate.opsForHash().putAll(redisKey, sessionMap);
        redisTemplate.expire(redisKey, 7, TimeUnit.DAYS);
    }

    private List<String> generatePresignedUrls(String targetKey, String uploadId,
                                               Set<Integer> uploadedParts, int totalParts) {
        List<String> urls = new ArrayList<>();
        for (int i = 1; i <= totalParts; i++) {
            if (uploadedParts.contains(i)) continue;
            UploadPartRequest request = UploadPartRequest.builder()
                    .bucket(BUCKET_NAME).key(targetKey).uploadId(uploadId).partNumber(i).build();
            PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(
                    r -> r.uploadPartRequest(request).signatureDuration(Duration.ofMinutes(15))
            );
            urls.add(presigned.url().toString());
        }
        return urls;
    }
}