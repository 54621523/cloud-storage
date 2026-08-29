package demo.cloud.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;



@Data
public class RegisterRequest {

    // 用户名（允许为空，但三者不能全为空）
    @Size(min = 3, max = 20, message = "用户名长度需在3-20个字符之间")
    @Schema(description = "用户名",
            example = "aaabbbccc"
    )
    private String username;

    // 邮箱（允许为空）
    @Size(max = 50, message = "邮箱长度不能超过50个字符")
    @Schema(description = "邮箱",
            example = "example@ex.com"
    )
    private String email;

    // 手机号码（允许为空）
    @Size(max = 20, message = "手机号码格式不正确")
    @Schema(description = "手机号码",
            example = "+00 123456789"
    )
    private String phone;

    // 密码（必填）
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需在6-20个字符之间")
    @Schema(description = "密码",
            example = "123abc"
    )
    private String password;
}