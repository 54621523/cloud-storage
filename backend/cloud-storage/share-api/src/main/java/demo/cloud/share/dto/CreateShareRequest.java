package demo.cloud.share.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "创建分享的请求体")
public class CreateShareRequest {

    // 提取码（可选）
    @Schema(description = "提取码",
        example = "abcde"
    )
    private String password;
    // 过期时间
    @Schema(description = "过期时间",
        example = "YYYY-MM-DD HH:MM:SS"
    )
    private LocalDateTime expireTime;

    private String displayName;



    // 分享的虚拟文件/虚拟文件夹
    @Schema(description = "分享的文件/文件夹对象",
            example = "[{\"id\": 100, \"type\": \"FILE\"}, {\"id\": 200, \"type\": \"FOLDER\"}]"
    )
    private List<ShareItem> items;
}
