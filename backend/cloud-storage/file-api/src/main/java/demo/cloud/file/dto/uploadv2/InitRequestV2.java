package demo.cloud.file.dto.uploadv2;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 初始化请求
 */
@Data
@Schema(description = "初始化上传请求的请求体")
public class InitRequestV2 {


    @NotNull
    @Schema(description = "文件名",
            example = "file.ext"
    )
    private String fileName;

    @NotNull
    @Schema(description = "文件大小（字节）",
            example = "1024"
    )
    private long fileSize;

    @NotNull
    @Schema(description = "文件的前1MB的Md5",
            example = "3b1254bd7f1f14ce0d5caca25626733b"
    )
    private String fileHash;


    @Schema(description = "分块的大小（字节）",
            example = "1024"
    )
    private Long chunkSize;


    private Long parentId;

    @Nullable
    private String relativePath;

    private Long totalChunks;

    private String uploadId;
}
