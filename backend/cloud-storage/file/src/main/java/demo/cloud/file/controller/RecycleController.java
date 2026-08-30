package demo.cloud.file.controller;

import demo.cloud.common.pojo.PageResult;
import demo.cloud.common.pojo.Result;
import demo.cloud.common.web.context.BaseContext;
import demo.cloud.file.dto.DeleteRequest;
import demo.cloud.file.dto.RecycleFileVO;
import demo.cloud.file.dto.RestoreRequest;
import demo.cloud.file.service.FileManagerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/recycle")
@Tag(name = "回收站管理", description = "包含回收站内的浏览、还原、彻底删除等操作")
public class RecycleController {

    private final FileManagerService fileManagerService;


    // ==================== 1. 浏览接口 ====================

    /**
     * 获取指定目录下的文件列表
     */
    @GetMapping
    @Operation(summary = "获取回收站文件列表", description = "查询回收站内所有文件和文件夹")
    public Result<PageResult<RecycleFileVO>> listRecycleBinFiles(@Parameter(description = "页码", example = "1001")
                                                               @RequestParam Long pageNum,
                                                           @Parameter(description = "页大小", example = "50")
                                                               @RequestParam Long pageSize) {

        Long userId = BaseContext.getUserId();
        log.info("用户 {} 请求浏览回收站", userId);

        PageResult<RecycleFileVO> recycleFilePage = fileManagerService.queryMyRecycleBin(pageNum, pageSize, userId);
        return Result.success(recycleFilePage);
    }

    // ==================== 2. 创建接口 ====================


    // ==================== 3. 更新接口 ====================

    @PostMapping("/restore")
    @Operation(summary = "还原文件/文件夹", description = "还原文件或文件夹")
    public Result restore(@RequestBody RestoreRequest request){
        Long userId = BaseContext.getUserId();
        log.info("用户{} 还原文件数量 {} ",userId, request.getItems().size());
        fileManagerService.restore(request, userId);
        return Result.success();
    }

    // ==================== 4. 删除接口 ====================

    /**
     * 批量删除文件或文件夹
     */
    @DeleteMapping("/permanent")
    @Operation(summary = "批量彻底删除", description = "彻底删除文件/文件夹")
    public Result deletePermanently(@RequestBody DeleteRequest request) {
        Long userId = BaseContext.getUserId();
        log.info("用户 {} 发起删除请求，条目数: {}", userId, request.getItems().size());
        fileManagerService.deletePermanently(request, userId);
        return Result.success();
    }
}