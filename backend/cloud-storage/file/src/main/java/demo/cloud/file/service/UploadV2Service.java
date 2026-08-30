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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
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
    @PostMapping("/init")
    public InitResponseV2 initUpload(@RequestBody InitRequestV2 request, Long userId) {
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
                UserFile userFile = new UserFile();
                userFile.setSize(fileSize);
                userFile.setName(request.getFileName());
                userFile.setParentId(parentId);
                userFile.setUserId(userId);
                userFile.setPhysicalId(existing.getId());
                userFileService.saveFile(userFile);
                return buildCompleteResponse();
            }
        }

        // 2. 0字节文件
        if (fileSize == 0) {
            FilePhysical existing = filePhysicalService.getOne(
                    new LambdaUpdateWrapper<FilePhysical>()
                            .eq(FilePhysical::getMd5, "d41d8cd98f00b204e9800998ecf8427e")
                            .last("LIMIT 1")
            );
            Long physicalId;
            if (existing == null) {
                FilePhysical filePhysical = new FilePhysical();
                filePhysical.setMd5("d41d8cd98f00b204e9800998ecf8427e");
                filePhysical.setSize(0L);
                filePhysicalService.save(filePhysical);
                physicalId = filePhysical.getId();
            } else {
                physicalId = existing.getId();
            }
            UserFile userFile = new UserFile();
            userFile.setSize(0L);
            userFile.setName(request.getFileName());
            userFile.setParentId(parentId);
            userFile.setUserId(userId);
            userFile.setPhysicalId(physicalId);
            userFileService.saveFile(userFile);
            return buildCompleteResponse();
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
        List<String> presignedUrls = new ArrayList<>();
        if (!isComplete) {
            for (int i = 1; i <= totalParts; i++) {
                if (uploadedPartNumbers.contains(i)) {
                    continue;
                }
                UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(targetKey)
                        .uploadId(uploadId)
                        .partNumber(i)
                        .build();
                PresignedUploadPartRequest presignedRequest = s3Presigner.presignUploadPart(
                        r -> r.uploadPartRequest(uploadPartRequest)
                                .signatureDuration(Duration.ofMinutes(15))
                );
                presignedUrls.add(presignedRequest.url().toString());
            }
        }

        // 新建时记录物理表和Redis会话
        if (isNewUpload) {
            FilePhysical filePhysical = new FilePhysical();
            filePhysical.setMd5(request.getFileHash());
            filePhysical.setOssKey(targetKey);
            filePhysical.setSize(fileSize);
            filePhysicalService.save(filePhysical);

            String redisKey = "upload:session:" + uploadId;
            Map<String, Object> sessionMap = new HashMap<>();
            sessionMap.put("userId", userId);
            sessionMap.put("fileName", request.getFileName());
            sessionMap.put("parentId", parentId);
            sessionMap.put("ossKey", targetKey);
            redisTemplate.opsForHash().putAll(redisKey, sessionMap);
            redisTemplate.expire(redisKey, 7, TimeUnit.DAYS);
        }

        return buildResumeResponse(uploadId, uploadedPartNumbers, presignedUrls, chunkSize, isComplete);
    }

    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/merge")
    public String completeMultipartUpload(@RequestBody MergeRequestV2 request, Long userId) {
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
        String targetKey = Objects.requireNonNull(
                redisTemplate.opsForHash().get(redisKey, "ossKey")
        ).toString();

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
                            .eq(FilePhysical::getOssKey, targetKey)
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

            UserFile userFile = new UserFile();
            userFile.setPhysicalId(physical.getId());
            userFile.setSize(actualFileSize);
            userFile.setUserId(userId);
            userFile.setParentId(parentId);
            userFile.setName(fileName);
            userFileService.saveFile(userFile);

            // 8. 扣减配额（远程调用）
            boolean success = quotaDubboService.addUsedQuota(userId, actualFileSize);
            if (!success) {
                log.warn("配额扣减失败，可能发生并发冲突，userId={}, size={}", userId, actualFileSize);
                throw new BusinessException(0, "配额更新失败，请重试");
            }

            // 9. 发送MQ事件触发后续处理（RAG等）
            FileUploadEvent event = FileUploadEvent.builder()
                    .physicalId(physical.getId())
                    .bucket(BUCKET_NAME)
                    .ossKey(targetKey)
                    .fileName(fileName)
                    .userId(userId)
                    .uploadId(request.getUploadId())
                    .build();
            rabbitTemplate.convertAndSend(EXCHANGE_NAME, ROUTING_KEY, event);

            // 10. 清理Redis会话
            redisTemplate.delete(redisKey);

            return "合并成功";
        } catch (S3Exception e) {
            log.error("合并失败", e);
            // 建议清理：abortMultipartUpload
            throw new RuntimeException("合并失败: " + e.getMessage());
        }
    }

    // ========================================
    // ====== READ ======
    // ========================================

    @GetMapping("/listParts")
    public List<PartInfo> listParts(@RequestParam String bucket,
                                    @RequestParam String key,
                                    @RequestParam String uploadId) {
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

    @GetMapping("/download")
    public String generateDownloadUrl(@RequestParam Long virtualFileId, Long userId) {
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

    private InitResponseV2 buildCompleteResponse() {
        InitResponseV2 response = new InitResponseV2();
        response.setIsComplete(true);
        return response;
    }

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
        InitResponseV2 response = new InitResponseV2();
        response.setUploadId(uploadId);
        response.setChunkSize(chunkSize);
        response.setPresignedUrls(presignedUrls);
        response.setUploadedChunks(uploadedParts);
        response.setIsComplete(isComplete);
        return response;
    }

    private String buildTargetObjectName(String originalFileName) {
        String safeFileName = UUID.randomUUID().toString();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return String.format("files/%s/%s_%s%s", datePath, timestamp, safeFileName, extension);
    }

    private Long resolveParentId(InitRequestV2 request, Long userId) {
        String relativePath = request.getRelativePath();
        if (relativePath == null || relativePath.isEmpty()) {
            return request.getParentId();
        }

        String dirPath;
        if (relativePath.endsWith("/")) {
            dirPath = relativePath.substring(0, relativePath.length() - 1);
        } else {
            int lastSlash = relativePath.lastIndexOf('/');
            dirPath = lastSlash > 0 ? relativePath.substring(0, lastSlash) : "";
        }

        if (dirPath.isEmpty()) {
            return 0L;
        }

        Map<String, Long> pathToId = userFolderService.saveFolders(userId, request.getParentId(), dirPath);
        Long id = pathToId.get(dirPath);
        if (id == null) {
            throw new RuntimeException("无法创建文件夹路径: " + dirPath);
        }
        return id;
    }
}