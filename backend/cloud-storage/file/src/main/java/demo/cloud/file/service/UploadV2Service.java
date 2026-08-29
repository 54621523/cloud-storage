package demo.cloud.file.service;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import demo.cloud.file.dto.uploadv2.InitRequestV2;
import demo.cloud.file.dto.uploadv2.InitResponseV2;
import demo.cloud.file.dto.uploadv2.MergeRequestV2;
import demo.cloud.file.dto.uploadv2.PartInfo;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.pojo.UserFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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


    private static final long DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;


    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final FilePhysicalService filePhysicalService;
    private final UserFileService userFileService;
    private final UserFolderService userFolderService;


    private final RedisTemplate redisTemplate;



    @PostMapping("/init")
    public InitResponseV2 initUpload(@RequestBody InitRequestV2 request, Long userId) {
        log.info("初始化/续传请求，文件名: {}, 大小: {}, uploadId: {}",
                request.getFileName(), request.getFileSize(), request.getUploadId());



        Long parentId = resolveParentId(request, userId);

        // 1. 秒传判断（若文件已完整存在，直接返回完成状态）
        if (request.getFileHash() != null) {
            FilePhysical existing = filePhysicalService.getOne(
                    new LambdaUpdateWrapper<FilePhysical>()
                            .eq(FilePhysical::getMd5, request.getFileHash())
                            .last("LIMIT 1")
            );
            if (existing != null) {
                filePhysicalService.increaseRef(existing.getId());
                UserFile userFile = new UserFile();
                userFile.setSize(request.getFileSize());
                userFile.setName(request.getFileName());
                userFile.setParentId(parentId);
                userFile.setUserId(userId);
                userFile.setPhysicalId(existing.getId());
                userFileService.saveFile(userFile);
                return buildCompleteResponse();
            }
        }
        // 0字节文件
        if (request.getFileSize() == 0) {
            FilePhysical existing = filePhysicalService.getOne(
                    new LambdaUpdateWrapper<FilePhysical>()
                            .eq(FilePhysical::getMd5, "d41d8cd98f00b204e9800998ecf8427e")
                            .last("LIMIT 1")
            );
            Long physicalId;
            if(existing == null){
                FilePhysical filePhysical = new FilePhysical();
                filePhysical.setMd5("d41d8cd98f00b204e9800998ecf8427e");
                filePhysical.setSize(0L);
                physicalId = filePhysical.getId();
            }else {
                physicalId = existing.getId();
            }
            UserFile userFile = new UserFile();
            userFile.setSize(request.getFileSize());
            userFile.setName(request.getFileName());
            userFile.setParentId(parentId);
            userFile.setUserId(userId);
            userFile.setPhysicalId(physicalId);
            return buildCompleteResponse();
        }

        long chunkSize = request.getChunkSize() != null ? request.getChunkSize() : DEFAULT_CHUNK_SIZE;
        String targetKey = buildTargetObjectName(request.getFileName());

        // 2. 判断是续传还是新建
        String uploadId;
        Set<Integer> uploadedPartNumbers = new HashSet<>();
        boolean isComplete = false;
        boolean isNewUpload = false;

        if (request.getUploadId() != null && !request.getUploadId().isEmpty()) {
            // --- 续传模式：使用传入的 uploadId ---
            uploadId = request.getUploadId();
            try {
                // 查询该 uploadId 下已上传的分片
                ListPartsRequest listPartsRequest = ListPartsRequest.builder()
                        .bucket("documents")
                        .key(targetKey)
                        .uploadId(uploadId)
                        .build();
                ListPartsResponse listPartsResponse = s3Client.listParts(listPartsRequest);
                List<Part> uploadedParts = listPartsResponse.parts();

                // 提取已上传分片编号
                uploadedPartNumbers = uploadedParts.stream()
                        .map(Part::partNumber)
                        .collect(Collectors.toSet());

                int totalParts = (int) Math.ceil((double) request.getFileSize() / chunkSize);
                isComplete = uploadedPartNumbers.size() == totalParts;

                // 如果已完整，直接返回（无需生成预签名URL）
                if (isComplete) {
                    return buildResumeResponse(uploadId, uploadedPartNumbers, Collections.emptyList(), chunkSize, true);
                }

            } catch (NoSuchUploadException e) {
                // uploadId 不存在或已过期，可以选择创建新上传或报错
                log.warn("uploadId {} 不存在，将创建新上传", uploadId);
                // 这里选择创建新上传
                uploadId = createNewMultipartUpload(targetKey);
                isNewUpload = true;
            } catch (Exception e) {
                log.error("查询分片失败", e);
                throw new RuntimeException("续传查询失败", e);
            }
        } else {
            // --- 新建模式：创建新的 Multipart Upload ---
            uploadId = createNewMultipartUpload(targetKey);
            isNewUpload = true;
        }

        // 3. 生成未上传分片的预签名 URL（如果未完成）
        int totalParts = (int) Math.ceil((double) request.getFileSize() / chunkSize);
        List<String> presignedUrls = new ArrayList<>();
        if (!isComplete) {
            for (int i = 1; i <= totalParts; i++) {
                if (uploadedPartNumbers.contains(i)) {
                    continue; // 已上传跳过
                }
                UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                        .bucket("documents")
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

        // 4. 若为新建，记录物理表（可在此记录 uploadId 和状态）
        if (isNewUpload) {
            FilePhysical filePhysical = new FilePhysical();
            filePhysical.setMd5(request.getFileHash());
            filePhysical.setOssKey(targetKey);
            filePhysical.setSize(request.getFileSize());
            filePhysicalService.save(filePhysical);
            String key = "upload:session" + uploadId;
            Map<String, Object> map = new HashMap<>();
            map.put("userId", userId);
            map.put("fileName", request.getFileName());
            map.put("parentId", parentId);
            map.put("ossKey", targetKey);
            redisTemplate.opsForHash().putAll(key, map);
            redisTemplate.expire(key,7, TimeUnit.DAYS);
        }

        // 5. 构建响应
        return buildResumeResponse(uploadId, uploadedPartNumbers, presignedUrls, chunkSize, isComplete);
    }

    private InitResponseV2 buildCompleteResponse() {
        InitResponseV2 response = new InitResponseV2();
        response.setIsComplete(true);
        return response;
    }

    // 辅助方法：创建新的 Multipart Upload
    private String createNewMultipartUpload(String key) {
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket("documents")
                        .key(key)
                        .build()
        );
        return response.uploadId();
    }

    // 辅助方法：构建响应
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

    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/merge")
    public String completeMultipartUpload(@RequestBody MergeRequestV2 request, Long userId) {

        // 1. 将 PartInfo 列表转换为 CompletedPart 列表
        List<CompletedPart> completedParts = request.getParts().stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.getPartNumber())
                        .eTag(part.getETag())   // 注意：字段名一致
                        .build()).sorted(Comparator.comparingInt(CompletedPart::partNumber)).collect(Collectors.toList());

        completedParts.forEach(p ->
                log.info("Part {} ETag: '{}'", p.partNumber(), p.eTag())
        );


        // 2. 构造合并请求
        CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
                .parts(completedParts)
                .build();

        String key = "upload:session" + request.getUploadId();
        String targetKey = Objects.requireNonNull(redisTemplate.opsForHash().get(key, "ossKey")).toString();
        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket("documents")
                .key(targetKey)
                .uploadId(request.getUploadId())
                .multipartUpload(completedUpload)
                .build();

        try {
            CompleteMultipartUploadResponse response = s3Client.completeMultipartUpload(completeRequest);

            FilePhysical one = filePhysicalService.getOne(
                    new LambdaQueryWrapper<FilePhysical>()
                            .eq(FilePhysical::getOssKey, targetKey)
                            .last("Limit 1")
            );
            Long parentId = Long.valueOf( Objects.requireNonNull(redisTemplate.opsForHash().get(key, "parentId")).toString() );
            String fileName = Objects.requireNonNull(redisTemplate.opsForHash().get(key, "fileName")).toString();
            UserFile userFile = new UserFile();
            userFile.setPhysicalId(one.getId());
            userFile.setSize(one.getSize());
            userFile.setUserId(userId);
            userFile.setParentId(parentId);
            userFile.setName(fileName);
            userFileService.saveFile(userFile);
            return response.location();
        } catch (S3Exception e) {
            // 建议在失败时调用 abortMultipartUpload 清理
            throw new RuntimeException("合并失败: " + e.getMessage());
        }
    }

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
            List<PartInfo> parts = response.parts().stream()
                    .map(part -> new PartInfo(
                            part.partNumber(),
                            part.eTag()
                            )
                    )
                    .collect(Collectors.toList());

            log.info("共找到 {} 个分片", parts.size());
            return parts;
        } catch (S3Exception e) {
            log.error("查询分片失败: {}", e.getMessage());
            throw new RuntimeException("查询分片失败: " + e.awsErrorDetails().errorMessage());
        }
    }


    @GetMapping("/Download")
    public String generateDownloadUrl(@RequestParam Long virtualFileId, Long userId){

        MPJLambdaWrapper<UserFile> wrapper = new MPJLambdaWrapper<UserFile>()
                .selectAs(UserFile::getName, "displayName")                // 选取 UserFile 的 name
                .selectAs(FilePhysical::getOssKey, "ossKey")          // 选取 FilePhysical 的 ossKey
                .innerJoin(FilePhysical.class, FilePhysical::getId, UserFile::getPhysicalId) // 连接条件
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getId, virtualFileId)
                .last("LIMIT 1");
        Map<String, Object> result = userFileService.selectJoinMap(wrapper);

        if(result== null){
            // TODO
            return "";
        }
        String displayName = (String) result.get("displayName");
        String ossKey = (String) result.get("ossKey");


        String encodedFileName = URLEncoder.encode(displayName, StandardCharsets.UTF_8);
        String contentDisposition = "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName;

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket("documents")
                .key(ossKey)
                .responseContentDisposition(contentDisposition)
                .build();

        GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedGetObjectRequest =
                s3Presigner.presignGetObject(getObjectPresignRequest);

        String presignedUrl = presignedGetObjectRequest.url().toExternalForm();
        log.info("预签名URL {}", presignedUrl);
        return presignedUrl;
    }


    private String buildTargetObjectName(String originalFileName) {
        String safeFileName = UUID.randomUUID().toString();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            // 取最后一个点之后的部分作为扩展名（包含点）
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return String.format("files/%s/%s_%s%s", datePath, timestamp, safeFileName, extension);
    }

    private Long resolveParentId(InitRequestV2 request, Long userId) {
        String relativePath = request.getRelativePath();
        if (relativePath == null || relativePath.isEmpty()) {
            return request.getParentId(); // 兼容旧逻辑
        }
        // 提取目录部分
        String dirPath;
        if (relativePath.endsWith("/")) {
            dirPath = relativePath.substring(0, relativePath.length() - 1);
        } else {
            int lastSlash = relativePath.lastIndexOf('/');
            dirPath = lastSlash > 0 ? relativePath.substring(0, lastSlash) : "";
        }
        if (dirPath.isEmpty()) {
            return 0L; // 根目录ID（根据您的设计调整）
        }
        Map<String, Long> pathToId = userFolderService.saveFolders(userId, request.getParentId(), dirPath);
        Long id = pathToId.get(dirPath);
        if (id == null) {
            throw new RuntimeException("无法创建文件夹路径: " + dirPath);
        }
        return id;
    }
}
