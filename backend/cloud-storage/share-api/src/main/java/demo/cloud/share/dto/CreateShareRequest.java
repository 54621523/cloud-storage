package demo.cloud.share.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "创建分享的请求体")
public class CreateShareRequest {

    // 提取码（可选）
    @Schema(description = "提取码",
        example = "abcde"
    )
    @Size(min = 4, max = 4, message = "密码长度应为4-20位")
    @Nullable
    private String password;
    // 过期时间
    @Schema(description = "过期时间",
        example = "YYYY-MM-DD HH:MM:SS"
    )
    @Nullable
    private LocalDateTime expireTime;

    @Size(max=255, message = "分享名称不能超过255个字符")
    private String displayName;


    // 分享的虚拟文件/虚拟文件夹
    @Schema(description = "分享的文件/文件夹对象",
            example = "[{\"id\": 100, \"type\": \"FILE\"}, {\"id\": 200, \"type\": \"FOLDER\"}]"
    )
    @NotEmpty(message = "分享内容不能为空")
    @Valid  // 级联校验子对象
    private List<ShareItem> items;
}
