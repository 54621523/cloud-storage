package demo.cloud.file.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.mapper.FolderTreePathMapper;
import demo.cloud.file.mapper.UserFolderMapper;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.UserFolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Param;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;


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
    public Long resolveAndCreateFolders(Long userId, Long targetParentId, String relativePath) {
        if (!self.isOwner(targetParentId, userId)) {
            // TODO 错误信息
            throw new IllegalArgumentException();
        }

        // 1. 边界处理
        if (StringUtils.isBlank(relativePath) || !StringUtils.contains(relativePath, "/")) {
            return targetParentId;
        }
        String folderPath = StringUtils.substringBeforeLast(relativePath, "/");
        if (StringUtils.isBlank(folderPath)) return targetParentId;
        List<String> folderNames = Arrays.asList(StringUtils.split(folderPath, '/'));

        // 2. 使用分布式锁，锁粒度为：用户 + 目标父目录
        String lockKey = "lock:folder:create:" + userId + ":" + targetParentId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                try {
                    // 3. 在锁的保护下，安全地逐层创建
                    return self.getOrCreateFolder(userId, targetParentId, folderNames);
                } finally {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                }
            } else {
                throw new RuntimeException("系统繁忙，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("操作被中断", e);
        }
    }


    @Transactional(rollbackFor = Exception.class)
    public Long getOrCreateFolder(Long userId, Long parentId, List<String> folderNames) {
        Long currentParentId = parentId;
        for (String folderName : folderNames) {
            if (StringUtils.isBlank(folderName)) continue;

            // 安全查询
            UserFolder existing = userFolderMapper.selectOne(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .eq(UserFolder::getParentId, currentParentId)
                            .eq(UserFolder::getName, folderName)
            );

            if (existing != null) {
                currentParentId = existing.getId();
            } else {
                userFolderMapper.insertOrUpdateIgnore(userId, currentParentId, folderName);

                // 再次查询获取真实 ID
                UserFolder newFolder = userFolderMapper.selectOne(
                        new LambdaQueryWrapper<UserFolder>()
                                .eq(UserFolder::getUserId, userId)
                                .eq(UserFolder::getParentId, currentParentId)
                                .eq(UserFolder::getName, folderName)
                );
                if (newFolder == null) throw new IllegalStateException("文件夹创建异常: " + folderName);

//                // (可选)
//                // 维护闭包表
//                // 指向自身的记录
//                List<TreePathNode> nodes = new ArrayList<>();
//                TreePathNode self = new TreePathNode();
//                self.setDescendantId(newFolder.getId());
//                self.setAncestorId(newFolder.getId());
//                self.setDepth(0);
//                nodes.add(self);
//                // 若存在目标父节点，则复制父节点信息
//                if (parentId != null) {
//                    // 获取所有子代为目标父节点的节点
//                    List<TreePathNode> parentNodes = userFolderTreeMapper.selectList(new LambdaQueryWrapper<TreePathNode>()
//                            .eq(TreePathNode::getDescendantId, parentId)
//                    );
//                    // 父代信息不变，子代信息修改为自身，递归深度 + 1
//                    for (TreePathNode node : parentNodes) {
//                        TreePathNode newNode = new TreePathNode();
//                        newNode.setAncestorId(node.getAncestorId());
//                        newNode.setDescendantId(self.getId());
//                        newNode.setDepth(node.getDepth() + 1);
//                        nodes.add(newNode);
//                    }
//                }
//                // 插入闭包表
//                userFolderTreeMapper.insert(nodes);
                currentParentId = newFolder.getId();
            }
        }
        return currentParentId;
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



}
