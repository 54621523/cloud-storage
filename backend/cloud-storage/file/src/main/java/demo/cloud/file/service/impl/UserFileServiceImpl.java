package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.file.cache.CacheInvalidationHelper;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.FileSavedEvent;
import demo.cloud.file.mapper.UserFileMapper;
import demo.cloud.file.pojo.UserFile;
import demo.cloud.file.service.UserFileService;
import lombok.extern.slf4j.Slf4j;
import org.apache.seata.tm.api.transaction.TransactionHook;
import org.apache.seata.tm.api.transaction.TransactionHookManager;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private final RabbitTemplate rabbitTemplate;
    private static final String EXCHANGE_NAME = "file.exchange";
    private static final String ROUTING_KEY = "file.process";
    private final CacheInvalidationHelper cache;
    // 注入自身的代理对象（使用 @Lazy 避免循环依赖）
    @Lazy
    @Autowired
    private UserFileServiceImpl self;

    public UserFileServiceImpl(UserFileMapper userFileMapper, RedissonClient redissonClient, RabbitTemplate rabbitTemplate, CacheInvalidationHelper cache) {
        this.userFileMapper = userFileMapper;
        this.redissonClient = redissonClient;
        this.rabbitTemplate = rabbitTemplate;
        this.cache = cache;
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
        Set<Long> parentIdsToEvict = new HashSet<>();
        // 2. 逐组处理（每组都是一个独立的命名空间）
        for (Map.Entry<Long, List<UserFile>> entry : groupMap.entrySet()) {
            Long parentId = entry.getKey();
            List<UserFile> groupFiles = entry.getValue();
            parentIdsToEvict.add(parentId);


            String lockKey = "upload:" + userId + "_" + parentId;
            RLock lock = redissonClient.getLock(lockKey);

            boolean locked = false;

            try {
                // 等待3秒，持锁最多30秒（可根据业务调整）
                long lockStart = System.currentTimeMillis();
                locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
                log.info("【锁】获取锁耗时: {} ms", System.currentTimeMillis() - lockStart);
                if (!locked) {
                    throw new RuntimeException("系统繁忙，请稍后重试");
                }
                self.doSaveFiles(groupFiles, userId, parentId, batchSize);
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

//        if (!parentIdsToEvict.isEmpty()) {
//            TransactionSynchronizationManager.registerSynchronization(
//                    new TransactionSynchronization() {
//                        @Override
//                        public void afterCommit() {
//                            for (Long parentId : parentIdsToEvict) {
//                                cache.evictDirectoryCache(parentId);
//                            }
//                        }
//                    }
//            );
//        }
    }

    @Transactional(rollbackFor = Exception.class)
    void doSaveFiles(List<UserFile> groupFiles, Long userId, Long parentId, int batchSize){
        long resolveStart = System.currentTimeMillis();
        List<UserFile> resolvedFiles = resolveNameConflicts(groupFiles, userId, parentId);
        log.info("【resolveNameConflicts】耗时: {} ms", System.currentTimeMillis() - resolveStart);

        // 3. 批量插入
        long insertStart = System.currentTimeMillis();
        userFileMapper.insert(resolvedFiles, batchSize);
        log.info("【批量插入】耗时: {} ms", System.currentTimeMillis() - insertStart);
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



    public List<UserFile> resolveNameConflicts(List<UserFile> targetFiles, Long userId, Long parentId) {
        // 1. 查询目标文件夹现有的所有名称
        Set<String> existingNames = userFileMapper.selectList(
                new LambdaQueryWrapper<UserFile>()
                        .eq(UserFile::getUserId, userId)
                        .eq(UserFile::getParentId, parentId)
                        .isNull(UserFile::getDeletedAt)
                        .select(UserFile::getName)
        ).stream().map(UserFile::getName).collect(Collectors.toSet());

        // 2. 重命名循环
        List<UserFile> resolvedList = new ArrayList<>(targetFiles);
        for (UserFile file : resolvedList) {
            String originalName = file.getName();
            while (existingNames.contains(originalName)) {
                originalName = generateUniqueName(originalName);
            }
            file.setName(originalName);
            existingNames.add(originalName);
        }
        return resolvedList;
    }

    // 全局事务
    public UserFile createUserFile(Long userId, Long parentId, String fileName, Long fileSize, Long physicalId) {
        UserFile userFile = new UserFile();
        userFile.setSize(fileSize);
        userFile.setName(fileName);
        userFile.setParentId(parentId);
        userFile.setUserId(userId);
        userFile.setPhysicalId(physicalId);
        self.saveFile(userFile);
        registerFileSavedEvent(Collections.singletonList(userFile.getId()), userId, parentId);
        log.info("createUserFIle 的 id 是 {}", userFile.getId());


        return userFile;
    }

    /**
     * @param folderIds
     * @param userId
     * @return
     */
    @Override
    public Set<Long> getFileChildren(Collection<Long> folderIds, Long userId) {
        return userFileMapper.getFileChilren(folderIds, userId);
    }

    private void registerFileSavedEvent(List<Long> ids, Long userId, Long parentId) {
        TransactionHookManager.registerHook(new TransactionHook() {
            @Override
            public void afterCommit() {
                sendFileSavedEvent(ids, userId);
                cache.evictDirectoryCache(parentId);
            }

            @Override
            public void beforeRollback() {
            }

            @Override
            public void beforeBegin() {
            }

            @Override
            public void afterBegin() {
            }

            @Override
            public void beforeCommit() {
            }

            @Override
            public void afterRollback() {
            }

            @Override
            public void afterCompletion() {
            }
        });
    }

    private void sendFileSavedEvent(List<Long> ids, Long userId) {
        FileSavedEvent event = FileSavedEvent.builder()
                .ids(ids)
                .userId(userId)
                .type(FileItemType.FILE)
                .build();
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, "file.saved", event);
    }


}
