package demo.cloud.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import demo.cloud.auth.dto.LoginRequest;
import demo.cloud.auth.dto.LoginResponse;
import demo.cloud.auth.dto.RegisterRequest;
import demo.cloud.auth.dto.RegisterResponse;
import demo.cloud.auth.mapper.UserMapper;
import demo.cloud.auth.pojo.User;
import demo.cloud.common.util.JwtUtil;
import demo.cloud.file.dto.UserRootFolderDTO;
import demo.cloud.file.service.UserFolderDubboService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;

    private final AuthenticationManager authenticationManager;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;


    @DubboReference(check = false)
    private UserFolderDubboService userFolderService;


    /**
     * 用户登录核心逻辑
     */
    public LoginResponse login(LoginRequest request) {
        try {
            // 1. 将用户名和密码封装为 Token，交给 AuthenticationManager 校验
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getLoginAccount(), request.getPassword())
            );

            // 2. 校验通过后，从 SecurityContext 中获取真实的 User 对象
            User user = (User) authentication.getPrincipal();
            Long rootFolderId = user.getRootFolderId();
            String rootFolderName = user.getRootFolderName();
            if(rootFolderId == null || rootFolderName.isEmpty()){
                UserRootFolderDTO rootFolder = userFolderService.getRootFolder(user.getUserId());
                rootFolderId = rootFolder.getRootFolderID();
                rootFolderName = rootFolder.getRootFolderName();
            }

            // 3. 使用 userId 生成 JWT
            String token = jwtUtil.generateUserToken(user.getUserId());

            // 4. 封装并返回响应
            LoginResponse response = new LoginResponse();
            response.setToken(token);
            response.setUsername(user.getUsername());
            response.setNickname(user.getNickname());
            response.setEmail(user.getEmail());
            response.setPhone(user.getPhone());
            response.setExpiresIn(86400000L); // 24小时
            response.setRootFolderName(rootFolderName);
            response.setRootFolderId(rootFolderId);

            return response;

        } catch (AuthenticationException e) {
            // 捕获认证失败异常（如：用户不存在、密码错误）
            log.error("用户登录失败: {}", e.getMessage());
            throw new RuntimeException("用户名或密码错误");
        }
    }

    /**
     * 用户注册核心逻辑
     */
    public RegisterResponse register(RegisterRequest request) {
        // 1. 核心校验：用户名、邮箱、手机号至少需要提供一个
        boolean hasUsername = StringUtils.hasText(request.getUsername());
        boolean hasEmail = StringUtils.hasText(request.getEmail());
        boolean hasPhone = StringUtils.hasText(request.getPhone());

        if (!hasUsername && !hasEmail && !hasPhone) {
            throw new RuntimeException("用户名、邮箱、手机号至少需要填写一项");
        }

        // 2. 唯一性冲突校验
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(hasUsername, User::getUsername, request.getUsername())
                .or()
                .eq(hasEmail, User::getEmail, request.getEmail())
                .or()
                .eq(hasPhone, User::getPhone, request.getPhone());

        User existUser = userMapper.selectOne(wrapper);
        if (existUser != null) {
            if (hasUsername && existUser.getUsername().equals(request.getUsername())) {
                throw new RuntimeException("该用户名已被注册");
            }
            if (hasEmail && existUser.getEmail().equals(request.getEmail())) {
                throw new RuntimeException("该邮箱已被注册");
            }
            if (hasPhone && existUser.getPhone().equals(request.getPhone())) {
                throw new RuntimeException("该手机号已被注册");
            }
        }

        // 3. 构建实体并加密密码
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        try {
            // 调用远程Dubbo服务创建名为"我的文件"的根文件夹
            // 假设 userFolderService 提供了 createRootFolder(Long userId, String folderName) 方法
            // 如果方法名不同，请根据实际情况调整
            UserRootFolderDTO dto = userFolderService.createRootFolder(user.getUserId());

            user.setRootFolderId(dto.getRootFolderID());
            user.setRootFolderName(dto.getRootFolderName());
            log.info("用户 {} 的根文件夹创建成功", user.getUserId());
        } catch (Exception e) {
            // 远程调用失败，记录错误并抛出异常，使本地事务回滚
            log.error("创建用户根文件夹失败，userId: {}", user.getUserId(), e);
            throw new RuntimeException("创建用户空间失败，请重试", e);
        }
        // 4. 写入数据库
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            log.warn("并发注册冲突: {}", e.getMessage());
            throw new RuntimeException("账号信息已被占用，请更换后重试");
        }

        // ========== 新增：注册成功后创建根文件夹 ==========


        // 5. 封装返回结果
        RegisterResponse response = new RegisterResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        return response;
    }

}