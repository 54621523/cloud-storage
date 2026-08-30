package demo.cloud.file.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.mapper.FolderTreePathMapper;
import demo.cloud.file.mapper.UserFolderMapper;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.UserFolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Param;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@RequiredArgsConstructor
@Service
public class FolderService extends ServiceImpl<UserFolderMapper, UserFolder> implements UserFolderService {

    private final UserFolderMapper userFolderMapper;
    private final RedissonClient redissonClient;
    private final FolderTreePathMapper userFolderTreeMapper;


    @Autowired
    @Lazy
    private FolderService self;


    public boolean isOwner(Long folderId, Long userId) {
        if (folderId == null || userId == null) return false;
        return userFolderMapper.exists(
                new LambdaQueryWrapper<UserFolder>()
                        .eq(UserFolder::getId, folderId)
                        .eq(UserFolder::getUserId, userId)
        );
    }


    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> saveFolders(Long userId, Long rootParentId, String fullPath) {
        // 1. 按 "/" 分割路径
        String[] parts = fullPath.split("/");
        if (parts.length == 0) {
            return new HashMap<>();
        }

        Map<String, Long> pathToId = new HashMap<>();
        Long currentParentId = rootParentId;
        StringBuilder currentPath = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String folderName = parts[i];
            // 构建当前相对路径（从 A 开始）
            if (i > 0) {
                currentPath.append('/');
            }
            currentPath.append(folderName);
            String pathKey = currentPath.toString();

            // 如果缓存中已存在，直接更新父级ID并跳过
            if (pathToId.containsKey(pathKey)) {
                currentParentId = pathToId.get(pathKey);
                continue;
            }

            // 2. 尝试 INSERT IGNORE
            UserFolder newFolder = new UserFolder();
            newFolder.setUserId(userId);
            newFolder.setParentId(currentParentId);
            newFolder.setName(folderName);
            // 设置其他默认字段（如创建时间）

            int affected = userFolderMapper.insertIgnore(newFolder);
            Long folderId;
            if (affected > 0) {
                // 插入成功，MyBatis 自动填充 ID
                folderId = newFolder.getId();
            } else {
                // 插入失败（唯一键冲突），查询已存在记录
                UserFolder existing = userFolderMapper.selectOne(
                        new LambdaQueryWrapper<UserFolder>()
                                .eq(UserFolder::getUserId, userId)
                                .eq(UserFolder::getParentId, currentParentId)
                                .eq(UserFolder::getName, folderName)
                                .isNull(UserFolder::getDeletedAt)
                );
                if (existing == null) {
                    // 防御性处理
                    throw new RuntimeException("文件夹创建失败，请重试");
                }
                folderId = existing.getId();
            }

            // 缓存并更新父级ID
            pathToId.put(pathKey, folderId);
            currentParentId = folderId;
        }

        return pathToId;
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

    List<ItemIdentity> filterItemsUnderRoot(@Param("rootId") Long rootId,
                                            @Param("items") List<ItemIdentity> items){
        return userFolderMapper.filterItemsUnderRoot(rootId, items);
    }


    List<UserFolder> selectSubTreeBatch(@Param("rootIds") List<Long> rootIds){
        return userFolderMapper.selectSubTreeBatch(rootIds);
    }


    private String generateUniqueName(String originalName) {
        // 生成带时间戳和随机数的名称
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String random = UUID.randomUUID().toString().substring(0, 4);
        return originalName + "_" + timestamp + "_" + random;
    }



}
