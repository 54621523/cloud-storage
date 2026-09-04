package demo.cloud.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import demo.cloud.auth.dubboService.UserQuotaDubboService;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.file.dto.uploadv2.*;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.pojo.UserFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.apache.seata.tm.api.transaction.TransactionHook;
import org.apache.seata.tm.api.transaction.TransactionHookManager;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static demo.cloud.file.mq.RabbitExchangeConfig.EXCHANGE_NAME;
import static demo.cloud.file.mq.RabbitExchangeConfig.UPLOAD_EVENT;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadV2Service {

    // ====== Constants ======
    private static final long DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;
    private static final String BUCKET_NAME = "documents";
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

    @Autowired
    @Lazy
    private UploadV2Service self;

    @DubboReference(version = "1.0.0", timeout = 3000, retries = 3)
    private UserQuotaDubboService quotaDubboService;

    // ========================================
    // ====== CREATE ======
    // ========================================
    public InitResponseV2 initUpload(InitRequestV2 request, Long userId){
        long startTotal = System.currentTimeMillis();
        log.info("========== initUpload 开始 ==========");


        log.info("初始化/续传请求，文件名: {}, 大小: {}, uploadId: {}",
                request.getFileName(), request.getFileSize(), request.getUploadId());

        long step2Start = System.currentTimeMillis();
        Long parentId = resolveParentId(request, userId);
        log.info("解析父目录耗时: {} ms", System.currentTimeMillis() - step2Start);

        log.info("========== initUpload 结束（正常初始化）总耗时: {} ms ==========", System.currentTimeMillis() - startTotal);
        return self.doInitUpload(request, userId, parentId);

    }

    @GlobalTransactional(rollbackFor = Exception.class)
    public InitResponseV2 doInitUpload(InitRequestV2 request, Long userId, Long parentId) {
        long fileSize = request.getFileSize();
        //  0字节文件
        long step4Start = System.currentTimeMillis();
        if (fileSize == 0) {
            throw new BusinessException(0,"不可上传没有大小的文件");
        }
        log.info("0字节文件处理耗时: {} ms", System.currentTimeMillis() - step4Start);

        // 秒传判断
        long step3Start = System.currentTimeMillis();
        if (request.getFileHash() != null) {
            FilePhysical existing = filePhysicalService.getOne(
                    new LambdaUpdateWrapper<FilePhysical>()
                            .eq(FilePhysical::getMd5, request.getFileHash())
                            .last("LIMIT 1")
            );
            if (existing != null) {
                filePhysicalService.increaseRef(existing.getId());
                userFileService.createUserFile(userId, parentId, request.getFileName(), fileSize, existing.getId());
                Long actualFileSize = existing.getSize();
                boolean success = quotaDubboService.addUsedQuota(userId, actualFileSize);
                if(!success){
                    throw new BusinessException(0,"配额增加失败");
                }
                return InitResponseV2.builder().isComplete(true).build();
            }
        }
        log.info("秒传查询耗时: {} ms（未命中）", System.currentTimeMillis() - step3Start);



        // 3. 正常分片上传初始化
        long step5Start = System.currentTimeMillis();
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
                long s3ListStart = System.currentTimeMillis();
                ListPartsRequest listPartsRequest = ListPartsRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(targetKey)
                        .uploadId(uploadId)
                        .build();
                ListPartsResponse listPartsResponse = s3Client.listParts(listPartsRequest);
                uploadedPartNumbers = listPartsResponse.parts().stream()
                        .map(Part::partNumber)
                        .collect(Collectors.toSet());
                log.info("【步骤5.1】S3 listParts 耗时: {} ms", System.currentTimeMillis() - s3ListStart);

                int totalParts = (int) Math.ceil((double) fileSize / chunkSize);
                isComplete = uploadedPartNumbers.size() == totalParts;
                if (isComplete) {
                    log.info("【步骤5】续传已完成，直接返回");
                    return buildResumeResponse(uploadId, uploadedPartNumbers, Collections.emptyList(), chunkSize, true);
                }
            } catch (NoSuchUploadException e) {
                log.warn("uploadId {} 不存在，将创建新上传", uploadId);
                long s3CreateStart = System.currentTimeMillis();
                uploadId = createNewMultipartUpload(targetKey);
                log.info("【步骤5.2】S3 createMultipartUpload 耗时: {} ms", System.currentTimeMillis() - s3CreateStart);
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
        long step6Start = System.currentTimeMillis();
        int totalParts = (int) Math.ceil((double) fileSize / chunkSize);
        List<String> presignedUrls = generatePresignedUrls(targetKey, uploadId, uploadedPartNumbers, totalParts);
        log.info("【步骤6】生成 {} 个预签名URL耗时: {} ms", presignedUrls.size(), System.currentTimeMillis() - step6Start);

        // 新建时记录物理表和Redis会话
        if (isNewUpload) {
            long step7Start = System.currentTimeMillis();
            filePhysicalService.getOrCreatePhysicalId(request.getFileHash(), fileSize, targetKey);
            storeUploadSession(uploadId, userId, request.getFileName(), parentId, targetKey, request.getFileHash());
            log.info("【步骤7】记录物理表和Redis会话耗时: {} ms", System.currentTimeMillis() - step7Start);
        }
        log.info("【步骤5+6+7】总初始化耗时: {} ms", System.currentTimeMillis() - step5Start);

        return buildResumeResponse(uploadId, uploadedPartNumbers, presignedUrls, chunkSize, isComplete);

    }

    @GlobalTransactional(rollbackFor = Exception.class, name = "minus-quota")
    @Transactional(rollbackFor = Exception.class)
    public String completeMultipartUpload(MergeRequestV2 request, Long userId) {
        long startTotal = System.currentTimeMillis();
        log.info("========== completeMultipartUpload 开始 ==========");
        // 1. 从 Redis 获取会话信息（提前获取，便于回滚时使用）
        long step1Start = System.currentTimeMillis();
        String redisKey = "upload:session:" + request.getUploadId();
        Map<Object, Object> session = redisTemplate.opsForHash().entries(redisKey);
        if (session.isEmpty()) {
            throw new BusinessException(400, "上传会话不存在或已过期");
        }
        Long storedUserId = Long.valueOf(session.get("userId").toString());
        if (!storedUserId.equals(userId)) {
            throw new BusinessException(403, "无权操作此上传");
        }
        final String targetKey = session.get("ossKey").toString();
        final String md5 = session.get("md5").toString();
        final Long parentId = Long.valueOf(session.get("parentId").toString());
        final String fileName = session.get("fileName").toString();

        log.info("【步骤1】Redis查询会话耗时: {} ms", System.currentTimeMillis() - step1Start);

        // 2. 状态标记
        AtomicBoolean mergeCompleted = new AtomicBoolean(false);

        // 3. 注册 Seata 事务钩子（回滚清理）
        TransactionHookManager.registerHook(new TransactionHook() {

            @Override
            public void beforeBegin() {
            }

            @Override
            public void afterBegin() {
            }

            @Override
            public void beforeCommit() {
            }

            @Override
            public void afterCommit() {
            }

            @Override
            public void beforeRollback() {
            }

            @Override
            public void afterRollback() {
                log.warn("全局事务回滚，执行清理: uploadId={}, key={}", request.getUploadId(), targetKey);
                try {
                    if (mergeCompleted.get()) {
                        // 合并已完成 → 删除已创建的对象
                        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                                .bucket(BUCKET_NAME)
                                .key(targetKey)
                                .build();
                        s3Client.deleteObject(deleteRequest);
                        log.info("已删除合并完成的对象: {}", targetKey);
                    } else {
                        // 合并未完成 → 中止上传，清理分片
                        AbortMultipartUploadRequest abortRequest = AbortMultipartUploadRequest.builder()
                                .bucket(BUCKET_NAME)
                                .key(targetKey)
                                .uploadId(request.getUploadId())
                                .build();
                        s3Client.abortMultipartUpload(abortRequest);
                        log.info("已中止未完成的上传: {}", request.getUploadId());
                    }
                } catch (Exception e) {
                    // 清理失败不阻塞事务，但需记录报警
                    log.error("回滚清理失败，需人工介入: uploadId={}", request.getUploadId(), e);
                }

                // 删除 Redis 会话（无论成功与否）
                try {
                    redisTemplate.delete(redisKey);
                    log.info("已删除 Redis 会话: {}", redisKey);
                } catch (Exception e) {
                    log.error("删除 Redis 会话失败: {}", redisKey, e);
                }

                // cleanPhysicalFileIfNeeded(md5);
            }

            @Override
            public void afterCompletion() {
            }
        });

        // 4. 转换 已上传分片列表
        long step3Start = System.currentTimeMillis();
        List<CompletedPart> completedParts = request.getParts().stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.getPartNumber())
                        .eTag(part.getETag())
                        .build())
                .sorted(Comparator.comparingInt(CompletedPart::partNumber))
                .collect(Collectors.toList());
        log.info("【步骤3】构建完成分片列表耗时: {} ms", System.currentTimeMillis() - step3Start);

        long step4Start = System.currentTimeMillis();
        CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
                .parts(completedParts)
                .build();

        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket(BUCKET_NAME)
                .key(targetKey)
                .uploadId(request.getUploadId())
                .multipartUpload(completedUpload)
                .build();
        log.info("【步骤4】S3 completeMultipartUpload 耗时: {} ms", System.currentTimeMillis() - step4Start);

        try {
            // 5. 执行合并
            s3Client.completeMultipartUpload(completeRequest);

            // 6. 获取真实大小（安全防护：覆盖前端伪造大小）
            long step5Start = System.currentTimeMillis();
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(targetKey)
                    .build();
            HeadObjectResponse headResponse = s3Client.headObject(headRequest);
            Long actualFileSize = headResponse.contentLength();
            log.info("【步骤5】S3 headObject 耗时: {} ms", System.currentTimeMillis() - step5Start);

            // 7. 更新物理文件大小
            long step6Start = System.currentTimeMillis();
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
            log.info("【步骤6】查询并更新FilePhysical耗时: {} ms", System.currentTimeMillis() - step6Start);


            // 8. 创建用户文件记录
            long step7Start = System.currentTimeMillis();
            userFileService.createUserFile(userId, parentId, fileName, actualFileSize, physical.getId());
            log.info("【步骤7】创建UserFile耗时: {} ms", System.currentTimeMillis() - step7Start);

            // 9. 扣减配额（远程调用）
            long step8Start = System.currentTimeMillis();
            boolean success = quotaDubboService.addUsedQuota(userId, actualFileSize);
            if (!success) {
                throw new BusinessException(0, "配额更新失败，请重试");
            }
            log.info("【步骤8】Dubbo配额扣减耗时: {} ms", System.currentTimeMillis() - step8Start);

            // 10. 发送MQ事件触发后续处理（RAG等）
            long step9Start = System.currentTimeMillis();
            registerFileUploadEvent(physical.getId(), targetKey, fileName, userId, request.getUploadId());
            log.info("【步骤9】发送MQ事件耗时: {} ms", System.currentTimeMillis() - step9Start);
            // 11. 清理Redis会话
            long step10Start = System.currentTimeMillis();
            redisTemplate.delete(redisKey);
            log.info("【步骤10】删除Redis会话耗时: {} ms", System.currentTimeMillis() - step10Start);

            log.info("========== completeMultipartUpload 结束总耗时: {} ms ==========", System.currentTimeMillis() - startTotal);
            return null;
        } catch (Exception e) {
            // 任何异常都不在本地清理，交给 afterRollback 统一处理
            log.error("合并过程异常，事务将回滚", e);
            // 如果是 S3 异常，可以包装为业务异常，触发回滚
            throw new BusinessException(500, "文件合并失败，请重试");
        }
    }

    // ========================================
    // ====== READ ======
    // ========================================

    public List<PartInfo> listParts(String bucket,
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

    public String generateDownloadUrl(Long virtualFileId, Long userId) {
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



    private void sendFileUploadEvent(Long physicalId, String targetKey, String fileName,
                                     Long userId, String uploadId) {
        FileUploadEvent event = FileUploadEvent.builder()
                .physicalId(physicalId)
                .bucket(BUCKET_NAME)
                .ossKey(targetKey)
                .fileName(fileName)
                .userId(userId)
                .uploadId(uploadId)
                .build();
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, UPLOAD_EVENT, event);
    }





    private void registerFileUploadEvent(Long physicalId, String targetKey, String fileName,
                                         Long userId, String uploadId) {
        TransactionHookManager.registerHook(new TransactionHook() {
            @Override
            public void afterCommit() {
                sendFileUploadEvent(physicalId, targetKey, fileName, userId, uploadId);
            }

            @Override
            public void beforeRollback() {
            }

            @Override
            public void beforeBegin() {
            }

            @Override
            public void afterBegin() {
            }

            @Override
            public void beforeCommit() {
            }

            @Override
            public void afterRollback() {
            }

            @Override
            public void afterCompletion() {
            }
        });

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
                    r -> r
                            .uploadPartRequest(request)
                            .signatureDuration(Duration.ofMinutes(15))
            );
            urls.add(presigned.url().toString());
        }
        return urls;
    }
}