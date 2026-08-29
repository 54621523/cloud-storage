package demo.cloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;



@Data
@Schema(description = "创建文件夹请求体")
public class CreateFolderRequest {

    @NotNull(message = "父目录ID不能为空")
    @Schema(description = "父目录ID，若为0表示根目录",
            example = "1001"
    )
    private Long parentId;

    @NotNull(message = "文件夹名称不能为空")
    @Size(min = 1, max = 255, message = "文件夹名称长度必须在1-255之间")
    @Schema(description = "文件夹名称",
            minLength = 1,
            maxLength = 255,
            example = "工作文档"
    )
    private String name;
}