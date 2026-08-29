package demo.cloud.file.service;

import demo.cloud.common.exception.BusinessException;
import demo.cloud.common.oss.service.MinioService;
import demo.cloud.common.pojo.ResultCode;
import demo.cloud.common.pojo.StorageResult;
import demo.cloud.file.dto.*;
import demo.cloud.file.dto.upload.InitRequest;
import demo.cloud.file.dto.upload.MergeRequest;
import demo.cloud.file.dto.upload.UploadChunkRequest;
import demo.cloud.file.dto.upload.UploadProgress;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.pojo.UploadSession;
import demo.cloud.file.pojo.UserFile;
import demo.cloud.file.service.impl.FolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


/**
 * 文件上传流程服务 - 完整重构版本
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadFlowService {

    // 核心依赖
    private final MinioService minioService;
    private final FilePhysicalService filePhysicalService;
    private final UserFileService userFileService;
    private final FolderService folderService;
    private final UploadSessionService uploadSessionService;


    // 配置常量
    private static final long MAX_FILE_SIZE = 1024 * 1024 * 1024; // 1GB


    /**
     * 初始化上传流程
     */
    public UploadSession initUpload(InitRequest request, Long userId) {
        log.info("[初始化上传] FileName: {}, Size: {}, MD5: {}, UserId: {}",
                request.getFileName(), request.getFileSize(), request.getMd5(), userId);

        // 1. 参数校验
        validateInitParams(request);

        // 2. 处理目标目录
        Long targetParentId = resolveTargetParentId(request, userId);

        // 3. 秒传检查
        FilePhysical existingFile = checkQuickUpload(request.getMd5());
        if (existingFile != null) {
            return handleQuickUpload(request, userId ,existingFile, targetParentId);
        }

        // 4. 创建新会话
        return createNewUploadSession(request, userId, targetParentId);
    }

    /**
     * 处理分片上传
     */
    public UploadProgress uploadChunk(UploadChunkRequest request, MultipartFile chunk, Long userId) throws IOException {
        log.info("[上传分片] SessionId: {}, ChunkIndex: {}, Size: {}",
                request.getSessionId(), request.getChunkIndex(), chunk.getSize());

        // 1. 参数校验
        validateUploadChunkParams(request, chunk);

        // 2. 获取并校验会话
        UploadSession session = getSessionOrThrow(request.getSessionId(), userId);

        // 3. 检查断点续传
        if (uploadSessionService.isChunkUploaded(request.getSessionId(), request.getChunkIndex())) {
            log.info("[断点续传] 分片已上传，跳过处理. SessionId: {}, ChunkIndex: {}",
                    request.getSessionId(), request.getChunkIndex());
            return createUploadProgress(session);
        }

        // 4. 校验分片完整性
        validateChunkIntegrity(chunk, request.getMd5(), request.getSessionId(), request.getChunkIndex());

        // 5. 上传分片到存储
        uploadChunkToStorage(request, chunk);

        // 6. 记录上传状态
        uploadSessionService.markChunkUploaded(request.getSessionId(), request.getChunkIndex());
        updateSessionStatus(session, "UPLOADING");

        return createUploadProgress(session);
    }

    /**
     *  合并分片
     */
    public void mergeChunks(MergeRequest request, Long userId){

        log.info("[合并分片] SessionId: {}", request.getSessionId());

        // 1. 获取会话
        UploadSession session = getSessionOrThrow(request.getSessionId(), userId);

        // 2. 校验会话状态
        if("CANCELLED".equals(session.getStatus())){
            throw new BusinessException(ResultCode.UPLOAD_CANCELLED);
        }

        if("FAILED".equals(session.getStatus())){
            throw new BusinessException(ResultCode.UPLOAD_FAILED);
        }

        // 3. 合并文件
        StorageResult result = mergeFileChunks(session);
        String finalOssKey = result.getObjectName();

        // 4. 更新会话
        session.setOssKey(finalOssKey);
        session.setFileSize(result.getFileSize());
        updateSessionStatus(session, "COMPLETED");

        // 5. 入库
        // 5.1. 处理物理文件记录 (FilePhysical)
        FilePhysical physicalFile = filePhysicalService.processPhysicalFile(session,session.getMd5());

        // 5.2. 创建用户文件记录 (UserFile)
        UserFile userFile = UserFile.builder()
                .userId(userId)
                .parentId(session.getParentId())
                .physicalId(physicalFile.getId())
                .name(session.getFileName())
                .size(session.getFileSize())
                .build();
        userFileService.saveFile(userFile);
    }

    /**
     * 获取上传进度
     */
    public UploadProgress getProgress(String sessionId, Long userId){
        UploadSession session = getSessionOrThrow(sessionId, userId);
        return createUploadProgress(session);
    }

    /**
     * 取消上传
     */
    public void cancelUpload(CancelRequest request, Long userId){
        log.info("[取消上传] SessionId: {}", request.getSessionId());
        UploadSession session = getSessionOrThrow(request.getSessionId(), userId);
        updateSessionStatus(session, "CANCELLED");
        cleanupUploadResources(request.getSessionId());
    }



    private void validateInitParams(InitRequest request){
        if(StringUtils.isBlank(request.getFileName())){
            throw new IllegalArgumentException("文件名不能为空");
        }
        if(request.getFileSize() <= 0){
            throw new IllegalArgumentException("文件大小必须大于0");
        }
        if(StringUtils.isBlank(request.getMd5())){
            throw new IllegalArgumentException("文件MD5不能为空");
        }
        if (request.getFileSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小过大");
        }
    }

    /**
     * 解析目标父目录ID
     */
    private Long resolveTargetParentId(InitRequest request, Long userId){
        if(request.getIsFolderUpload() != null && request.getIsFolderUpload()){
            return folderService.resolveAndCreateFolders(userId, request.getParentId() ,request.getRelativePath());
        } else {
            return request.getParentId() != null ? request.getParentId() : 0L;
        }
    }

    /**
     * 检查秒传
     */
    private FilePhysical checkQuickUpload(String md5){
        return filePhysicalService.getByMd5(md5);
    }

    /**
     * 处理秒传
     */
    private UploadSession handleQuickUpload(InitRequest request, Long userId, FilePhysical existingFile, Long targetParentId){
        log.info("[秒传] 文件已存在, MD: {}", request.getMd5());

        // 创建假会话
        UploadSession fakeSession = createFakeSession(request);

        // 更新文件引用计数
        existingFile.incrementRefCount();
        filePhysicalService.updateById(existingFile);

        // 处理上传成功
        fakeSession.setParentId(targetParentId);

        // 创建用户文件记录 (UserFile)
        UserFile userFile = UserFile.builder()
                .userId(userId)
                .parentId(targetParentId)
                .physicalId(existingFile.getId())
                .name(request.getFileName())
                .build();
        userFileService.saveFile(userFile);

        return fakeSession;
    }

    /**
     * 创建新上传会话
     */
    private UploadSession createNewUploadSession(InitRequest request, Long userId, Long targetParentId){
        String sessionId = generateSessionId();
        int totalChunks = calculateTotalChunks(request.getFileSize(), request.getChunkSize());

        UploadSession session = UploadSession.builder()
                .userId(userId)
                .sessionId(sessionId)
                .fileName(request.getFileName())
                .fileSize(request.getFileSize())
                .md5(request.getMd5())
                .status("INIT")
                .totalChunks(totalChunks)
                .parentId(targetParentId)
                .relativePath(request.getRelativePath())
                .isFolderUpload(request.getIsFolderUpload())
                .build();
        uploadSessionService.saveSession(session);
        return session;
    }

    /**
     * 创建假会话
     */
    private UploadSession createFakeSession(InitRequest request){
        String sessionId = generateSessionId();
        return UploadSession.builder()
                .sessionId(sessionId)
                .fileName(request.getFileName())
                .fileSize(request.getFileSize())
                .md5(request.getMd5())
                .status("COMPLETED")
                .totalChunks(0)
                .build();
    }

    /**
    * 校验分片上传参数
    */
    private void validateUploadChunkParams(UploadChunkRequest request, MultipartFile chunk) {
        if (StringUtils.isBlank(request.getSessionId())) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new IllegalArgumentException("分片文件不能为空");
        }
        if (request.getChunkIndex() < 0) {
            throw new IllegalArgumentException("分片索引不能为负数");
        }
    }

    /**
     * 获取会话或抛出异常
     */
    private UploadSession getSessionOrThrow(String sessionId, Long userId) {
        UploadSession session = uploadSessionService.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("上传会话不存在: " + sessionId);
        }
        if(!Objects.equals(session.getUserId(), userId)){
            throw new IllegalArgumentException("用户与上传会话不符");
        }
        return session;
    }


    /**
     * 校验分片完整性
     */
    private void validateChunkIntegrity(MultipartFile chunk, String clientMd5, String sessionId, int chunkIndex) {
        String computedMd5 = calculateChunkMd5(chunk);
        if (!computedMd5.equalsIgnoreCase(clientMd5)) {
            log.error("[分片校验失败] SessionId: {}, ChunkIndex: {}, 客户端MD5: {}, 服务端MD5: {}",
                    sessionId, chunkIndex, clientMd5, computedMd5);
            throw new IllegalArgumentException(String.format(
                    "分片数据损坏，MD5不匹配: 客户端=%s, 服务端=%s", clientMd5, computedMd5));
        }
        log.info("[分片校验通过] SessionId: {}, ChunkIndex: {}, MD5: {}", sessionId, chunkIndex, computedMd5);
    }

    /**
     * 上传分片到存储
     */
    private void uploadChunkToStorage(UploadChunkRequest request, MultipartFile chunk) throws IOException {
        String chunkObjectName = request.getSessionId() + "/chunks/" + request.getChunkIndex();
        minioService.uploadChunk(
                chunkObjectName,
                chunk.getInputStream(),
                chunk.getSize(),
                chunk.getContentType()
        );
    }

    /**
     * 合并文件分片
     */
    private StorageResult mergeFileChunks(UploadSession session) {
        // 构建分片列表
        List<String> chunkObjectNames = buildChunkObjectNames(session.getSessionId(), session.getTotalChunks());

        // 构建目标文件名
         String targetObjectName = buildTargetObjectName(session);

        // 执行合并
        StorageResult result = minioService.mergeChunks(targetObjectName, chunkObjectNames);

        log.info("[合并分片] 合并成功, 目标对象: {}", targetObjectName);
        return result;
    }





    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private int calculateTotalChunks(long fileSize, long chunkSize){
        return (int) Math.ceil((double) fileSize / chunkSize);
    }

    private String calculateChunkMd5(MultipartFile chunk) {
        try {
            return DigestUtils.md5DigestAsHex(chunk.getInputStream());
        } catch (Exception e) {
            log.error("计算分片MD5失败", e);
            throw new RuntimeException("计算分片MD5失败: " + e.getMessage(), e);
        }
    }

    private List<String> buildChunkObjectNames(String sessionId, int totalChunks) {
        List<String> chunkObjectNames = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            chunkObjectNames.add(sessionId + "/chunks/" + i);
        }
        return chunkObjectNames;
    }
    private String buildTargetObjectName(UploadSession session) {
        String safeFileName = sanitizeFileName(session.getFileName());
        String timestamp = String.valueOf(System.currentTimeMillis());
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return String.format("files/%s/%s_%s", datePath, timestamp, safeFileName);
    }

    private String sanitizeFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "unknown";
        }
        return fileName.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9_.-]", "_");
    }




    // ============= 代理方法 =============

    private void updateSessionStatus(UploadSession session, String status) {
        session.setStatus(status);
        uploadSessionService.saveSession(session);
    }
    private void cleanupUploadResources(String sessionId) {
        uploadSessionService.deleteChunkRecords(sessionId);
    }

    private UploadProgress createUploadProgress(UploadSession session) {
        return uploadSessionService.create(session);
    }
}
