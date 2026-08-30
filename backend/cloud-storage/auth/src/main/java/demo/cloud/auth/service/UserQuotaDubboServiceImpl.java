package demo.cloud.auth.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import demo.cloud.auth.dto.QuotaInfo;
import demo.cloud.auth.dubboService.UserQuotaDubboService;
import demo.cloud.auth.mapper.UserMapper;
import demo.cloud.auth.pojo.User;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@DubboService(version = "1.0.0")
@Service
public class UserQuotaDubboServiceImpl implements UserQuotaDubboService {

    @Autowired
    private UserMapper userMapper;

    /**
     * 检查用户剩余配额是否足够
     * @param userId   用户ID
     * @param fileSize 需要占用的空间（字节）
     * @return true-足够，false-不足或用户不存在
     */
    @Override
    public boolean hasEnoughQuota(Long userId, Long fileSize) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        Long available = user.getQuotaTotal() - user.getQuotaUsed();
        return available >= fileSize;
    }

    /**
     * 增加已用配额（如上传文件），采用乐观锁防止并发超扣
     * @param userId   用户ID
     * @param fileSize 增加的空间（字节）
     * @return true-更新成功，false-配额不足或乐观锁失败
     */
    @Override
    public boolean addUsedQuota(Long userId, Long fileSize) {
        // 先查询当前用户信息（含version）
        User user = userMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        // 检查剩余配额是否足够
        if (user.getQuotaTotal() - user.getQuotaUsed() < fileSize) {
            return false;
        }

        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getUserId, userId)
                .eq(User::getVersion, user.getVersion())          // 乐观锁版本条件
                .apply("quota_total - quota_used >= {0}", fileSize) // 再次检查剩余空间（防并发）
                .setSql("quota_used = quota_used + " + fileSize)
                .setSql("version = version + 1");

        int rows = userMapper.update(null, wrapper);
        return rows > 0;
    }

    /**
     * 减少已用配额（如删除文件），采用乐观锁
     * @param userId   用户ID
     * @param fileSize 减少的空间（字节）
     * @return true-更新成功，false-用户不存在或已用配额不足
     */
    @Override
    public boolean subtractUsedQuota(Long userId, Long fileSize) {
        // 先查询当前用户信息（含version）
        User user = userMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        // 检查已用配额是否足够扣除（避免负数）
        if (user.getQuotaUsed() < fileSize) {
            return false;
        }

        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getUserId, userId)
                .eq(User::getVersion, user.getVersion())          // 乐观锁版本条件
                .apply("quota_used >= {0}", fileSize)             // 再次检查（防并发）
                .setSql("quota_used = quota_used - " + fileSize)
                .setSql("version = version + 1");

        int rows = userMapper.update(null, wrapper);
        return rows > 0;
    }

    /**
     * 获取用户配额信息
     * @param userId 用户ID
     * @return QuotaInfo 对象，包含总配额、已用、剩余；若用户不存在则返回 null
     */
    @Override
    public QuotaInfo getQuotaInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        QuotaInfo info = new QuotaInfo();
        info.setUserId(userId);
        info.setQuotaTotal(user.getQuotaTotal());
        info.setQuotaUsed(user.getQuotaUsed());
        info.setQuotaAvailable(user.getQuotaTotal() - user.getQuotaUsed());
        return info;
    }
}