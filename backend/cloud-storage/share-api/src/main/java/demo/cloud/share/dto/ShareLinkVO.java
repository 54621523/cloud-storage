package demo.cloud.share.dto;

import demo.cloud.share.constant.ShareStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "用于前端返回的视图对象")
public class ShareLinkVO {

    @Schema(description = "分享链接ID",
            example = "1001"
    )
    private Long id;

    @Schema(description = "分享链接唯一码(前端拼接URL)",
            example = "aaabbbccc"
    )
    private String shareCode;

    @Schema(description = "提取码",
            example = "abcde"
    )
    private String password;

    @Schema(description = "过期时间",
            example = "YYYY-MM-DD HH:MM:SS"
    )
    private LocalDateTime expireTime;

    @Schema(description = "分享状态",
        example = "ACTIVE"
    )
    private ShareStatus status;

    @Schema(description = "创建时间",
            example = "YYYY-MM-DD HH:MM:SS"
    )
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间",
            example = "YYYY-MM-DD HH:MM:SS"
    )
    private LocalDateTime updateTime;

    @Schema(description = "分享链接显示名称",
        example = "分享"
    )
    private String displayName;



}
