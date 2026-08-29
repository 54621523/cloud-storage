package demo.cloud.file.dto;

import demo.cloud.file.constant.FileItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "用于前端展示的视图对象")
public class VirtualFileVO  implements Serializable {

    private static final long serialVersionUID = 1L;



    @Schema(description = "文件/文件夹ID",
            example = "1001"
    )
    private Long id;

    @Schema(description = "文件/文件夹名称",
        example = "name"
    )
    private String name;

    @Schema(description = "文件大小（字节）",
        example = "1024"
    )
    private Long size;


    @Schema(description = "最后更新时间",
        example = "YYYY-MM-DD HH:MM:SS"
    )
    private LocalDateTime updateTime;


    @Schema(description = "文件类型（文件/文件夹）",
            example = "FILE"
    )
    private FileItemType type;

    @Schema(description = "父目录ID",
            example = "1001"
    )
    private Long parentId;
}