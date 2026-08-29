package demo.cloud.file.dto.upload;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 初始化请求
 */
@Data
@Schema(description = "初始化上传请求的请求体")
public class InitRequest {


    @NotNull
    @Schema(description = "文件名",
        example = "file.ext"
    )
    private String fileName;

    @Schema(description = "相对路径",
        example = "path/to/file.ext"
    )
    private String relativePath;

    @NotNull
    @Schema(description = "文件大小（字节）",
        example = "1024"
    )
    private long fileSize;

    @NotNull
    @Schema(description = "文件的前1MB的Md5",
        example = "3b1254bd7f1f14ce0d5caca25626733b"
    )
    private String md5;


    @Schema(description = "分块的大小（字节）",
        example = "1024"
    )
    private long chunkSize;

    @NotNull
    @Schema(description = "父目录ID",
        example = "1001"
    )
    private Long parentId;

    @NotNull
    @Schema(description = "是否为文件夹上传",
        example = "true"
    )
    private Boolean isFolderUpload;
}
