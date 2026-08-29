package demo.cloud.auth.controller;


import demo.cloud.auth.dto.LoginRequest;
import demo.cloud.auth.dto.LoginResponse;
import demo.cloud.auth.dto.RegisterRequest;
import demo.cloud.auth.dto.RegisterResponse;
import demo.cloud.auth.service.AuthService;
import demo.cloud.common.pojo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证模块", description = "登录、注册的接口")
@Slf4j
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录接口
     */
    @Operation(summary = "登录",
            description = "根据登录凭证（目前为用户名）和密码登录"
    )
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        try {
            LoginResponse response = authService.login(request);
            return Result.success(response);
        } catch (Exception e) {
            log.error("用户登录失败: {}", request.getLoginAccount(), e);
            return Result.error("登录失败: " + e.getMessage());
        }
    }

    /**
     * 注册接口
     */
    @Operation(summary = "注册",
            description = "根据登录凭证（目前为用户名）和密码注册"
    )
    @PostMapping("/register")
    public Result<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        try {
            RegisterResponse registerResponse = authService.register(request);
            return Result.success(registerResponse);
        } catch (Exception e) {
            log.error("用户注册失败: {}", request.getUsername(), e);
            return Result.error("注册失败: " + e.getMessage());
        }
    }
}