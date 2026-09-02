package demo.cloud.file.dto;


import demo.cloud.file.constant.FileItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用于文件/文件夹重命名的请求体")
public class RenameRequest {


    @Schema(description = "文件/文件夹Id",
        example = "1001"
    )
    private Long id;

    @Schema(description = "新名字",
        example = "new Name"
    )
    private String newName;

    @Schema(description = "文件类型（文件/文件夹）",
            example = "FILE"
    )
    private FileItemType type;

    @Schema(description = "父目录Id",
        example = "1001"
    )
    private Long parentId;

}
