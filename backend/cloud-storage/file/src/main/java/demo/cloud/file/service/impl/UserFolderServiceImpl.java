package demo.cloud.file.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.file.cache.CacheInvalidationHelper;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.FileSavedEvent;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.mapper.FolderTreePathMapper;
import demo.cloud.file.mapper.UserFolderMapper;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.UserFolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Param;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static demo.cloud.file.listener.FileIndexConsumer.SAVED_EVENT;
import static demo.cloud.file.mq.RabbitExchangeConfig.EXCHANGE_NAME;


@Slf4j
@RequiredArgsConstructor
@Service
public class UserFolderServiceImpl extends ServiceImpl<UserFolderMapper, UserFolder> implements UserFolderService {

    private final UserFolderMapper userFolderMapper;
    private final RedissonClient redissonClient;
    private final CacheInvalidationHelper cache;
    private final FolderTreePathMapper userFolderTreeMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    @Lazy
    @Autowired
    private UserFolderServiceImpl self;


    public boolean isOwner(Long folderId, Long userId) {
        if (folderId == null || userId == null) return false;
        return userFolderMapper.exists(
                new LambdaQueryWrapper<UserFolder>()
                        .eq(UserFolder::getId, folderId)
                        .eq(UserFolder::getUserId, userId)
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveFolder(UserFolder userFolder){
        userFolderMapper.insert(userFolder);
        sendFolderSavedEvent(Collections.singletonList(userFolder.getId()), userFolder.getUserId());
    }



    public Map<String, Long> saveFolders(Long userId, Long rootParentId, String fullPath) {

        String[] parts = Arrays.stream(fullPath.split("/"))
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
        if (parts.length == 0) {
            return new HashMap<>();
        }

        // 用户级锁：保证同一用户的路径构建串行化
        String lockKey = "folder:build:" + userId + ":" + rootParentId;
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try {
            return self.doSaveFolders(userId, rootParentId, parts);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }


    @Override
    public List<UserFolder> resolveNameConflicts(List<UserFolder> targetFiles, Long userId, Long parentId) {
        // 1. 查询目标文件夹现有的所有名称
        Set<String> existingNames = userFolderMapper.selectList(
                new LambdaQueryWrapper<UserFolder>()
                        .eq(UserFolder::getUserId, userId)
                        .eq(UserFolder::getParentId, parentId)
                        .isNull(UserFolder::getDeletedAt)
                        .select(UserFolder::getName)
        ).stream().map(UserFolder::getName).collect(Collectors.toSet());

        // 2. 重命名循环
        List<UserFolder> resolvedList = new ArrayList<>(targetFiles);
        for (UserFolder file : resolvedList) {
            String originalName = file.getName();
            while (existingNames.contains(originalName)) {
                originalName = generateUniqueName(originalName);
            }
            file.setName(originalName);
            existingNames.add(originalName);
        }
        return resolvedList;
    }

    /**
     * 校验父目录是否存在且不在回收站中
     * @param userId 用户ID
     * @param parentId 父目录ID
     * @param itemName 待操作的文件/文件夹名称（用于报错提示），可为null
     */
    public void validateParent(Long userId, Long parentId, String itemName) {
        if (parentId == null) {
            throw new BusinessException(0,
                    itemName == null ? "目标文件夹不存在" : String.format("无法还原“%s”：原目录不存在", itemName)
            );
        }

        UserFolder parent = userFolderMapper.selectOne(
                new LambdaQueryWrapper<UserFolder>()
                        .eq(UserFolder::getId, parentId)
                        .eq(UserFolder::getUserId, userId)
                        .select(UserFolder::getName, UserFolder::getDeletedAt)
        );

        if (parent == null) {
            String msg = (itemName != null)
                    ? String.format("“%s”的父目录不存在或已被永久删除", itemName)
                    : "目标父目录不存在或已被永久删除";
            throw new BusinessException(0, msg);
        }

        if (parent.getDeletedAt() != null) {
            String msg = (itemName != null)
                    ? String.format("“%s”的父目录已在回收站中，请先还原父目录", itemName)
                    : "目标父目录已在回收站中，请先还原父目录";
            throw new BusinessException(0, msg);
        }
    }


    public boolean checkPermissionByCTE(Long targetId, Long rootId) {
        return userFolderMapper.checkPermissionByCTE(targetId, rootId);
    }

    public List<ItemIdentity> filterItemsUnderRoot(@Param("rootId") Long rootId,
                                            @Param("items") List<ItemIdentity> items){
        return userFolderMapper.filterItemsUnderRoot(rootId, items);
    }


    public List<UserFolder> selectSubTreeBatch(@Param("rootIds") List<Long> rootIds){
        return userFolderMapper.selectSubTreeBatch(rootIds);
    }


    @Override
    public Set<Long> getFolderChildren(Collection<Long> folderIds, Long userId) {
       return userFolderMapper.getFolderChildren(folderIds, userId);
    }


    private String generateUniqueName(String originalName) {
        // 生成带时间戳和随机数的名称
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String random = UUID.randomUUID().toString().substring(0, 4);
        return originalName + "_" + timestamp + "_" + random;
    }

    private void sendFolderSavedEvent(List<Long> ids, Long userId) {
        FileSavedEvent event = FileSavedEvent.builder()
                .ids(ids)
                .userId(userId)
                .type(FileItemType.FOLDER)
                .build();
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, SAVED_EVENT, event);
    }


    @Transactional(rollbackFor = Exception.class)
    protected Map<String, Long> doSaveFolders(Long userId, Long rootParentId, String[] parts) {

        Map<String, Long> pathToId = new HashMap<>();
        Long currentParentId = rootParentId;
        List<Long> newFolderIds = new ArrayList<>();
        StringBuilder currentPath = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String name = parts[i];

            // 拼接当前完整路径
            if (i > 0) currentPath.append('/');
            currentPath.append(name);
            String pathStr = currentPath.toString();

            // 查询当前层是否已存在
            UserFolder existing = userFolderMapper.selectOne(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .eq(UserFolder::getParentId, currentParentId)
                            .eq(UserFolder::getName, name)
                            .isNull(UserFolder::getDeletedAt)
                            .last("LIMIT 1")
            );

            if (existing != null) {
                currentParentId = existing.getId();
                pathToId.put(pathStr, currentParentId);
            } else {
                // 不存在，创建新文件夹
                UserFolder newFolder = new UserFolder();
                newFolder.setUserId(userId);
                newFolder.setParentId(currentParentId);
                newFolder.setName(name);
                try {
                    userFolderMapper.insert(newFolder);
                    currentParentId = newFolder.getId();
                    pathToId.put(pathStr, currentParentId);
                    newFolderIds.add(newFolder.getId());
                } catch (DuplicateKeyException e){
                    currentParentId = newFolder.getId();
                    pathToId.put(pathStr, currentParentId);
                    newFolderIds.add(newFolder.getId());
                }

            }
        }
        // 事务提交后 发送事件和清理缓存
        if (!newFolderIds.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            sendFolderSavedEvent(newFolderIds, userId);
                            cache.evictDirectoryCache(rootParentId, userId);
                        }
                    }
            );
        }

        return pathToId;
    }


}
