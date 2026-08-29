package demo.cloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@Schema(description = "创建分享的响应体 ")
public class CreateShareResponse {


    //分享ID
    @Schema(description = "分享链接ID",
            example = "1001"
    )
    private Long id;
    //分享唯一码
    @Schema(description = "分享链接唯一码(前端拼接URL)",
            example = "aaabbbccc"
    )
    private String shareCode;
    //提取码
    @Schema(description = "提取码",
            example = "abcde"
    )
    private String password;
    //过期时间
    @Schema(description = "过期时间",
            example = "YYYY-MM-DD HH:MM:SS"
    )
    private LocalDateTime expireTime;
    //分享时间
    @Schema(description = "创建分享时间",
            example = "YYYY-MM-DD HH:MM:SS"
    )
    private LocalDateTime createTime;
}
