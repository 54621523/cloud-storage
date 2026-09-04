package demo.cloud.auth.service;

import demo.cloud.auth.dto.QuotaInfo;
import demo.cloud.auth.dubboService.UserQuotaDubboService;
import demo.cloud.auth.mapper.UserMapper;
import demo.cloud.auth.pojo.User;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@DubboService(version = "1.0.0")
@Service
@Slf4j
public class UserQuotaDubboServiceImpl implements UserQuotaDubboService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private RedissonClient redissonClient;

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
        log.info("要扣除的配额为 {}", fileSize);
        String lockKey = "quota_lock:" + userId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            lock.lock(3, TimeUnit.SECONDS);
            User user = userMapper.selectById(userId);
            if (user == null) {
                return false;
            }
            // 检查剩余配额是否足够
            if (user.getQuotaTotal() - user.getQuotaUsed() < fileSize) {
                return false;
            }

            user.setQuotaUsed(user.getQuotaUsed() + fileSize);
            int rows = userMapper.updateById(user);
            return rows > 0;
        }finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /**
     * 减少已用配额（如删除文件），采用乐观锁
     * @param userId   用户ID
     * @param fileSize 减少的空间（字节）
     * @return true-更新成功，false-用户不存在或已用配额不足
     */
    @Override
    public boolean subtractUsedQuota(Long userId, Long fileSize) {
        String lockKey = "quota_lock:" + userId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            lock.lock(3, TimeUnit.SECONDS);
            // 先查询当前用户信息（含version）
            log.info("要回退的配额为 {}", fileSize);
            User user = userMapper.selectById(userId);
            if (user == null) {
                return false;
            }
            // 检查已用配额是否足够扣除（避免负数）
            if (user.getQuotaUsed() < fileSize) {
                return false;
            }

            user.setQuotaUsed(user.getQuotaUsed() - fileSize);
            int rows = userMapper.updateById(user);
            return rows > 0;
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
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