

package demo.cloud.file.dto.upload;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分片上传请求 DTO
 * 用于接收分片的元数据信息
 */
@Data
@Schema(description = "上传分片请求体")
public class UploadChunkRequest {
    /**
     * 上传会话ID
     */
    @Schema(description = "会话ID",
        example = "550e8400e29b41d4a716446655440000"
    )
    private String sessionId;

    /**
     * 分片索引，从0开始
     */
    @Schema(description = "分片索引，从0开始",
        example = "0"
    )
    private int chunkIndex;

    /**
     * 整个分片的MD5值，用于校验
     */
    @Schema(description = "整个分片的Md5",
        example = "3b1254bd7f1f14ce0d5caca25626733b"
    )
    private String md5;
}