package demo.cloud.file.dto.upload;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 合并请求
 */
@Data
@Schema(description = "合并请求体")
public class MergeRequest {

    @Schema(description = "会话ID",
        example = "550e8400e29b41d4a716446655440000"
    )
    private String sessionId;
}
