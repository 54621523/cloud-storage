package demo.cloud.file.controller;


import demo.cloud.common.pojo.Result;
import demo.cloud.common.web.context.BaseContext;
import demo.cloud.file.dto.uploadv2.InitRequestV2;
import demo.cloud.file.dto.uploadv2.InitResponseV2;
import demo.cloud.file.dto.uploadv2.MergeRequestV2;
import demo.cloud.file.dto.uploadv2.PartInfo;
import demo.cloud.file.service.UploadV2Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@RestController
@RequestMapping("/api/v2/storage")
@RequiredArgsConstructor
public class UploadV2Controller {


    private final UploadV2Service uploadV2Service;



    @PostMapping("/init")
    public Result<InitResponseV2> initUpload(@RequestBody InitRequestV2 request) {
        Long userId = BaseContext.getUserId();
        InitResponseV2 responseV2 = uploadV2Service.initUpload(request, userId);
        return Result.success(responseV2);

    }

    @PostMapping("/merge")
    public Result<String> completeMultipartUpload(@RequestBody MergeRequestV2 request) {
        Long userId = BaseContext.getUserId();
        String s = uploadV2Service.completeMultipartUpload(request, userId);
        return Result.success(s);
    }

    @GetMapping("/listParts")
    public Result<List<PartInfo>> listParts(@RequestParam String bucket,
                                    @RequestParam String key,
                                    @RequestParam String uploadId) {
        List<PartInfo> partInfos = uploadV2Service.listParts(bucket, key, uploadId);
        return Result.success(partInfos);
    }


    @GetMapping("/Download")
    public Result<String> generateDownloadUrl(@RequestParam Long virtualFileId){
        Long userId = BaseContext.getUserId();
        String s = uploadV2Service.generateDownloadUrl(virtualFileId, userId);
        return Result.success(s);
    }



}
