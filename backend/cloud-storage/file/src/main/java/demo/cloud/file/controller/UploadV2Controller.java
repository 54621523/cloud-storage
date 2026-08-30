package demo.cloud.file.controller;

import demo.cloud.common.pojo.Result;
import demo.cloud.common.web.context.BaseContext;
import demo.cloud.file.dto.uploadv2.InitRequestV2;
import demo.cloud.file.dto.uploadv2.InitResponseV2;
import demo.cloud.file.dto.uploadv2.MergeRequestV2;
import demo.cloud.file.dto.uploadv2.PartInfo;
import demo.cloud.file.service.UploadV2Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v2/storage")
@RequiredArgsConstructor
@Tag(name = "分片上传", description = "包含大文件分片上传的初始化、合并、查询等操作")
public class UploadV2Controller {

    private final UploadV2Service uploadV2Service;

    // ==================== 1. 创建接口 ====================

    /**
     * 初始化分片上传
     */
    @PostMapping("/init")
    @Operation(summary = "初始化分片上传", description = "创建分片上传会话，返回uploadId和预签名URL列表")
    public Result<InitResponseV2> initUpload(@Valid @RequestBody InitRequestV2 request) {
        Long userId = BaseContext.getUserId();
        log.info("用户 {} 初始化分片上传，文件名: {}, 大小: {}, 目标目录: {}",
                userId, request.getFileName(), request.getFileSize(), request.getParentId());

        InitResponseV2 response = uploadV2Service.initUpload(request, userId);
        return Result.success(response);
    }

    /**
     * 合并分片
     */
    @PostMapping("/merge")
    @Operation(summary = "合并分片", description = "所有分片上传完成后，调用此接口合并成完整文件")
    public Result<String> completeMultipartUpload(@Valid @RequestBody MergeRequestV2 request) {
        Long userId = BaseContext.getUserId();
        log.info("用户 {} 合并分片，uploadId: {}, 分片数: {}",
                userId, request.getUploadId(), request.getParts().size());

        uploadV2Service.completeMultipartUpload(request, userId);
        return Result.success();
    }

    // ==================== 2. 查询接口 ====================

    /**
     * 查询已上传的分片信息
     */
    @GetMapping("/list-parts")
    @Operation(summary = "查询已上传分片", description = "根据uploadId查询已上传的分片列表，用于断点续传")
    public Result<List<PartInfo>> listParts(
            @Parameter(description = "存储桶名称", example = "documents")
            @RequestParam String bucket,
            @Parameter(description = "文件在存储桶中的完整路径", example = "files/2026/08/29/xxx.txt")
            @RequestParam String key,
            @Parameter(description = "分片上传ID", example = "12345")
            @RequestParam String uploadId) {

        log.info("查询分片信息，bucket: {}, key: {}, uploadId: {}", bucket, key, uploadId);

        List<PartInfo> partInfos = uploadV2Service.listParts(bucket, key, uploadId);
        return Result.success(partInfos);
    }

    // ==================== 3. 下载接口 ====================

    /**
     * 生成文件下载预签名URL
     */
    @GetMapping("/download")
    @Operation(summary = "生成下载链接", description = "为指定文件生成有时效性的预签名下载URL")
    public Result<String> generateDownloadUrl(
            @Parameter(description = "虚拟文件ID", example = "1001")
            @RequestParam Long virtualFileId) {

        Long userId = BaseContext.getUserId();
        log.info("用户 {} 请求下载文件，virtualFileId: {}", userId, virtualFileId);

        String downloadUrl = uploadV2Service.generateDownloadUrl(virtualFileId, userId);
        return Result.success(downloadUrl);
    }
}