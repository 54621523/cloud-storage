package demo.cloud.auth.dubboService;

public interface UserQuotaDubboService {

    // 1. 检查配额是否足够（上传前调用）
    boolean hasEnoughQuota(Long userId, Long fileSize);

    // 2. 增加已用容量（上传成功后调用，带乐观锁）
    // 返回 true 表示扣减成功，false 表示配额不足或并发冲突
    boolean addUsedQuota(Long userId, Long fileSize);

    // 3. 减少已用容量（删除文件时调用）
    boolean subtractUsedQuota(Long userId, Long fileSize);

    // 4. 查询当前配额（展示用）
    QuotaInfo getQuotaInfo(Long userId);
}