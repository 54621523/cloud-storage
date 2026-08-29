package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.file.mapper.UserFileMapper;
import demo.cloud.file.pojo.UserFile;
import demo.cloud.file.service.UserFileService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserFileServiceImpl extends ServiceImpl<UserFileMapper, UserFile> implements UserFileService {

    private final UserFileMapper userFileMapper;
    private final RedissonClient redissonClient;

    // 注入自身的代理对象（使用 @Lazy 避免循环依赖）
    @Lazy
    @Autowired
    private UserFileServiceImpl self;

    public UserFileServiceImpl(UserFileMapper userFileMapper, RedissonClient redissonClient) {
        this.userFileMapper = userFileMapper;
        this.redissonClient = redissonClient;
    }


    public boolean isOwner(Long userFileId, Long userId) {
        if (userFileId == null || userId == null) return false;
        return userFileMapper.exists(
                new LambdaQueryWrapper<UserFile>()
                        .eq(UserFile::getId, userFileId)
                        .eq(UserFile::getUserId, userId)
        );
    }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveFiles(List<UserFile> fileList,int batchSize) {
        if (fileList == null || fileList.isEmpty()) {
            return;
        }

        // 校验所有文件 userId 一致（可选）
        Long userId = fileList.get(0).getUserId();
        for (UserFile file : fileList) {
            if (!userId.equals(file.getUserId())) {
                throw new IllegalArgumentException("所有文件的 userId 必须相同");
            }
        }
        // 1. 分组 key: "userId_parentId"
        Map<Long, List<UserFile>> groupMap = fileList.stream()
                .collect(Collectors.groupingBy(UserFile::getParentId));

        // 2. 逐组处理（每组都是一个独立的命名空间）
        for (Map.Entry<Long, List<UserFile>> entry : groupMap.entrySet()) {
            Long parentId = entry.getKey();
            List<UserFile> groupFiles = entry.getValue();


            String lockKey = "upload:" + userId + "_" + parentId;
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;

            try {
                // 等待3秒，持锁最多30秒（可根据业务调整）
                locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
                if (!locked) {
                    throw new RuntimeException("系统繁忙，请稍后重试");
                }

            // 2.1 查询该组（该文件夹）下所有活跃的文件名（只查 name 列，高效）
                Set<String> existingNames = userFileMapper.selectList(
                        new LambdaQueryWrapper<UserFile>()
                                .eq(UserFile::getUserId, userId)
                                .eq(UserFile::getParentId, parentId)
                                .isNull(UserFile::getDeletedAt)
                                .select(UserFile::getName)
                ).stream().map(UserFile::getName).collect(Collectors.toSet());

            // 2.2 该组内内存重命名（处理与该文件夹已有文件的重名，以及组内自身的重名）
                for (UserFile file : groupFiles) {
                    String originalName = file.getName();
                    while (existingNames.contains(originalName)) {
                        originalName = generateUniqueName(originalName);
                    }
                    file.setName(originalName);
                    existingNames.add(originalName); // 防止组内同名
                }

            // 3. 批量插入
            userFileMapper.insert(groupFiles, batchSize);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("锁等待被中断", e);
            }finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    @Override
    public void saveFiles(List<UserFile> fileList) {
        self.saveFiles(fileList, 100);
    }

    /**
     * 便捷的单条保存方法
     */
    public void saveFile(UserFile file) {
        self.saveFiles(Collections.singletonList(file));
    }

    private String generateUniqueName(String originalName) {
        // 生成带时间戳和随机数的名称
        int dotIndex = originalName.lastIndexOf(".");
        String baseName = (dotIndex == -1) ? originalName : originalName.substring(0, dotIndex);
        String extension = (dotIndex == -1) ? "" : originalName.substring(dotIndex);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String random = UUID.randomUUID().toString().substring(0, 4);
        return baseName + "_" + timestamp + "_" + random + extension;
    }
}
