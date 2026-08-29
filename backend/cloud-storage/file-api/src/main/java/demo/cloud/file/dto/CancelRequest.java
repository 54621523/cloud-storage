package demo.cloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 取消上传请求
 */
@Data
@Schema(description = "取消上传请求体")
public class CancelRequest {


    @NotBlank(message = "会话ID不能为空")
    @Schema(description = "上传会话ID（由初始化接口返回）",
            example = "550e8400e29b41d4a716446655440000"
    )
    private String sessionId;
}
