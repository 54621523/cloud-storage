package demo.cloud.auth.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import demo.cloud.auth.dto.QuotaInfo;
import demo.cloud.auth.dubboService.UserQuotaDubboService;
import demo.cloud.auth.mapper.UserMapper;
import demo.cloud.auth.pojo.User;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@DubboService(version = "1.0.0") // 暴露 Dubbo 服务
@Service
public class UserQuotaDubboServiceImpl implements UserQuotaDubboService {

    @Autowired
    private UserMapper userMapper;

    /**
     * @param userId
     * @param fileSize
     * @return
     */
    @Override
    public boolean hasEnoughQuota(Long userId, Long fileSize) {
        return false;
    }

    @Override
    public boolean addUsedQuota(Long userId, Long fileSize) {
        // MyBatis-Plus 乐观锁更新：update user set quota_used = quota_used + #{size}, version = version + 1 
        // where id = #{userId} and quota_total - quota_used >= #{size} and version = #{version}
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getUserId, userId)
                .ge(User::getQuotaTotal, User::getQuotaTotal + fileSize)
                .setSql("quota_used = quota_used + " + fileSize)
                .setSql("version = version + 1");
        return userMapper.update(null, wrapper) > 0;
    }

    /**
     * @param userId
     * @param fileSize
     * @return
     */
    @Override
    public boolean subtractUsedQuota(Long userId, Long fileSize) {
        return false;
    }

    /**
     * @param userId
     * @return
     */
    @Override
    public QuotaInfo getQuotaInfo(Long userId) {
        return null;
    }
    // ... 其他方法实现
}