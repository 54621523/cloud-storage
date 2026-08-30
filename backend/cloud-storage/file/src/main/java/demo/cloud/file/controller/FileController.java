package demo.cloud.file.controller;

import demo.cloud.common.pojo.PageResult;
import demo.cloud.common.pojo.Result;
import demo.cloud.common.web.context.BaseContext;
import demo.cloud.file.dto.*;
import demo.cloud.file.service.FileManagerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/files")
@Tag(name = "文件管理", description = "包含文件/文件夹的浏览、创建、删除等操作")
public class FileController {

    private final FileManagerService fileManagerService;


    // ==================== 1. 浏览接口 ====================

    /**
     * 获取指定目录下的文件列表
     */
    @GetMapping("/list-file")
    @Operation(summary = "获取文件列表", description = "根据父目录ID查询该目录下的所有文件和子文件夹")
    public Result<List<VirtualFileVO>> listFiles(
            @Parameter(description = "父目录ID，若为0或null则自动定位到用户根目录", example = "1001")
            @RequestParam(required = false) Long parentId) {

        Long userId = BaseContext.getUserId();
        log.info("用户 {} 请求浏览目录，parentId: {}", userId, parentId);

        List<VirtualFileVO> virtualFileList = fileManagerService.getVirtualFileList(parentId, userId);
        return Result.success(virtualFileList);
    }

    @GetMapping("/list-folderOnly")
    @Operation(summary = "仅获取文件夹列表", description = "根据父目录ID查询该目录下的子文件夹")
    public Result<List<VirtualFileVO>> listFolderOnly(
            @Parameter(description = "父目录ID，若为0或null则自动定位到用户根目录", example = "1001")
            @RequestParam(required = false) Long parentId) {

        Long userId = BaseContext.getUserId();
        log.info("用户 {} 请求浏览文件夹树，parentId: {}", userId, parentId);

        List<VirtualFileVO> virtualFileList = fileManagerService.getVirtualFolderListOnly(parentId, userId);
        return Result.success(virtualFileList);
    }

    @GetMapping("/search")
    @Operation(summary = "搜索文件/文件夹", description = "根据关键词模糊搜索文件和文件夹，支持分页")
    public Result<PageResult<VirtualFileVO>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        Long userId = BaseContext.getUserId();
        log.info("用户 {} 搜索关键词: {}, page: {}, size: {}", userId, keyword, page, size);
        PageResult<VirtualFileVO> result = fileManagerService.search(keyword, userId, page, size);
        return Result.success(result);
    }

    // ==================== 2. 创建接口 ====================

    /**
     * 新建文件夹
     */
    @PostMapping("/folders")
    @Operation(summary = "新建文件夹", description = "在指定的父目录下创建一个新文件夹")
    public Result createFolder(@Validated @RequestBody CreateFolderRequest request) {
        Long userId = BaseContext.getUserId();
        log.info("用户 {} 创建文件夹，父目录: {}, 名称: {}", userId, request.getParentId(), request.getName());

        try {
            fileManagerService.createFolder(request, userId);
            return Result.success();
        } catch (Exception e) {
            log.error("创建文件夹失败", e);
            return Result.error(e.getMessage());
        }
    }

    // ==================== 3. 更新接口 ====================

    @PostMapping("/rename")
    @Operation(summary = "重命名文件/文件夹", description = "重命名一个文件或文件夹")
    public Result rename(@RequestBody RenameRequest request){
        Long userId = BaseContext.getUserId();
        log.info("用户{} 重命名文件, 新名称{}",userId, request.getNewName());
        fileManagerService.rename(request, userId);
        return Result.success();
    }

    @PostMapping("/move-to")
    @Operation(summary = "移动文件/文件夹", description = "将文件/文件夹移动到指定父目录下")
    public Result moveTo(@RequestBody MoveRequest request){
        Long userId = BaseContext.getUserId();
        log.info("用户{} 移动文件/文件夹 新目录{}", userId, request.getParentId());
        fileManagerService.moveTo(request, userId);
        return Result.success();
    }



    // ==================== 4. 删除接口 ====================

    /**
     * 批量删除文件或文件夹
     */
    @DeleteMapping
    @Operation(summary = "批量移入回收站", description = "将文件/文件夹移入回收站")
    public Result moveToRecycleBin(@RequestBody DeleteRequest request) {
        Long userId = BaseContext.getUserId();
        log.info("用户 {} 发起删除请求，条目数: {}", userId, request.getItems().size());
        fileManagerService.moveToRecycleBin(request, userId);
        return Result.success();
    }
}