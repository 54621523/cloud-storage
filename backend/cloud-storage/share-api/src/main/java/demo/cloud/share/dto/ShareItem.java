package demo.cloud.share.dto;


import demo.cloud.file.constant.FileItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ShareItem {

    // 目标ID
    @Schema(description = "文件/文件夹ID",
            example = "1001"
    )
    private Long targetId;
    // 0：虚拟文件， 1：虚拟文件夹
    @Schema(description = "文件类型（文件/文件夹）",
            example = "FILE"
    )
    private FileItemType targetType;
}
