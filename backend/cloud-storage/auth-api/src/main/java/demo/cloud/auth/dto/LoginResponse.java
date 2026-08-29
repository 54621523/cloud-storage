package demo.cloud.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginResponse {

    @Schema(description = "登录token",
            example = "aaabbbccc"
    )
    @NotBlank
    private String token;
    @Schema(description = "昵称",
            example = "nickname"
    )
    private String nickname;


    private String username;
    private String phone;
    private String email;

    @Schema(description = "过期时间",
            example = "aaabbbccc"
    )
    private Long expiresIn;

    @Schema(description = "根目录Id",
            example = "1001"
    )
    private Long rootFolderId;
    @Schema(description = "根目录名称",
            example = "folderName"
    )
    private String rootFolderName;
}