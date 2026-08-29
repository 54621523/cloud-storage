package demo.cloud.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "取消上传请求体")
public class LoginRequest {
    @NotBlank(message = "登录凭证不能为空")
    @Schema(description = "登录凭证（目前为用户名）",
            example = "aaabbbccc"
    )
    private String loginAccount;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "登录密码",
            example = "123abc"
    )
    private String password;
}