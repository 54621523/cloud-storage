package demo.cloud.share.controller;


import demo.cloud.common.pojo.PageResult;
import demo.cloud.common.pojo.Result;
import demo.cloud.common.web.context.BaseContext;
import demo.cloud.file.dto.VirtualFileVO;
import demo.cloud.share.dto.CreateShareRequest;
import demo.cloud.share.dto.CreateShareResponse;
import demo.cloud.share.dto.ShareLinkVO;
import demo.cloud.share.dto.TransferRequest;
import demo.cloud.share.service.ShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Tag(name = "分享模块", description = "创建、删除、更新、访问分享的接口")
@RestController
@RequestMapping("/api/shares")
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    /**
     * 创建分享链接
     * @param request
     * @return
     */
    @Operation(summary = "创建分享链接",
            description = "根据请求体创建分享链接"
    )
    @PostMapping("/share")
    public Result<CreateShareResponse> shareFile(@Valid @RequestBody CreateShareRequest request){
        Long userId = BaseContext.getUserId();
        CreateShareResponse share = shareService.createShare(request, userId);
        return Result.success(share);
    }

    /**
     * 查询自己的分享文件列表
     */
    @Operation(summary = "查询分享文件列表",
        description = "查询属于自己的分享链接列表"
    )
    @GetMapping("/list")
    public Result<PageResult<ShareLinkVO>> listSharedFile(@Parameter(description = "页码", example = "1001")
                                                          @RequestParam @Min(0) Long pageNum,
                                                          @Parameter(description = "页大小", example = "50")
                                                          @RequestParam @Min(0) Long pageSize){
        Long userId = BaseContext.getUserId();
        PageResult<ShareLinkVO> page = shareService.queryMyShare(pageNum, pageSize, userId);
        return Result.success(page);
    }

    /**
     * 取消分享
     */
    @Operation(summary = "删除分享",
        description = "删除指定的分享链接"
    )
    @DeleteMapping("/cancel")
    public Result cancelSharedFile(@RequestParam @Min(0) Long shareId){
        Long userId = BaseContext.getUserId();
        shareService.deleteShareLink(shareId, userId);

        return Result.success();
    }

    /**
     * 访问分享链接
     */
    @Operation(summary = "访问分享链接",
        description = "使用提取码访问分享链接"
    )
    @PostMapping("/verify/{shareCode}")
    public Result<String> verifySharedFile(@PathVariable String shareCode,
                                   @RequestParam(name = "password", required = false) String password){
        String s = shareService.verifyShare(shareCode, password);
        return Result.success(s);
    }

    /**
     * 查询分享链接详情文件列表
     */
    @Operation(summary = "访问他人分享详情",
        description = "访问他人分享链接具体内容"
    )
    @GetMapping("/info")
    public Result<List<VirtualFileVO>> getShareInfo(@RequestHeader("Share-Token") String shareToken,
                                                    @RequestParam @Min(0) Long parentId,
                                                    @RequestParam @Min(0) Long rootId
                                                    ){
        List<VirtualFileVO> shareInfo = shareService.getShareInfo(shareToken, parentId, rootId);

        return Result.success(shareInfo);
    }


    @Operation(summary = "下载文件",
        description = "访问者直接在网页中下载文件，返回一个下载链接"
    )
    @GetMapping("/download")
    //访问者下载文件
    public Result<String> downloadSharedFile(@RequestHeader("Share-Token") String shareToken,
                                     @Min(0) Long id,
                                     @Min(0) Long rootId
                                     ){
        String downloadUrl = shareService.generateDownloadUrl(shareToken, id, rootId);
        return Result.success(downloadUrl);
    }

    @Operation(summary = "转存文件",
        description = "访问者将文件转存到自己的网盘中，需要指定目标文件夹"
    )
    @PostMapping("/save")
    //访问者转存到自己的网盘中
    public Result saveSharedFile(@RequestHeader("Share-Token") String shareToken,
                                 @RequestBody @Valid TransferRequest request){
        Long userId = BaseContext.getUserId();
        shareService.saveToMyDisk(shareToken, userId, request);
        return Result.success();
    }
}
