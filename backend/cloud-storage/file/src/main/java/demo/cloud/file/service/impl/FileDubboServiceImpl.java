package demo.cloud.file.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import demo.cloud.auth.dubboService.UserQuotaDubboService;
import demo.cloud.file.dto.FilePhysicalDTO;
import demo.cloud.file.dto.ItemGroup;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.dto.VirtualFileVO;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.pojo.UserFile;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.BeanUtils;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@DubboService
@RequiredArgsConstructor
@Slf4j
public class FileDubboServiceImpl implements FileDubboService {


    private final FileManagerService fileManagerService;

    private final UserFileService userFileService;
    private final UserFolderService userFolderService;
    private final FilePhysicalService filePhysicalService;
    private final UserQuotaDubboService quotaDubboService;

    private final S3Presigner s3Presigner;


    @Override
    public FilePhysicalDTO getPhysicalFileByUserIdAndUserFileId(Long userFileId, Long userId) {
        MPJLambdaWrapper<UserFile> wrapper = JoinWrappers.lambda(UserFile.class)
                .select(FilePhysical::getId)
                .select(FilePhysical::getOssKey)
                .innerJoin(FilePhysical.class, FilePhysical::getId, UserFile::getPhysicalId)
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getId, userFileId);
        FilePhysicalDTO filePhysicalDTO = userFileService.selectJoinOne(FilePhysicalDTO.class, wrapper);
        return filePhysicalDTO;
    }

    /**
     * @param id
     * @return
     */
    @Override
    public String generateDownloadUrl(Long id) {
        MPJLambdaWrapper<UserFile> wrapper = new MPJLambdaWrapper<UserFile>()
                .selectAs(UserFile::getName, "displayName")                // 选取 UserFile 的 name
                .selectAs(FilePhysical::getOssKey, "ossKey")           // 选取 FilePhysical 的 ossKey
                .innerJoin(FilePhysical.class, FilePhysical::getId, UserFile::getPhysicalId) // 连接条件
                .eq(UserFile::getId, id)
                .last("LIMIT 1");
        Map<String, Object> result = userFileService.selectJoinMap(wrapper);

        if(result== null){
            return "";
        }
        String displayName = (String) result.get("displayName");
        String ossKey = (String) result.get("ossKey");


        String encodedFileName = URLEncoder.encode(displayName, StandardCharsets.UTF_8);
        String contentDisposition = "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName;

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket("documents")
                .key(ossKey)
                .responseContentDisposition(contentDisposition)
                .build();

        GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedGetObjectRequest =
                s3Presigner.presignGetObject(getObjectPresignRequest);

        String presignedUrl = presignedGetObjectRequest.url().toExternalForm();
        log.info("预签名URL {}", presignedUrl);
        return presignedUrl;

    }

    @Override
    public List<Long> getAllowedDocIdsByUserId(Long userId) {
        List<Object> objects = userFileService.listObjs(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .select(UserFile::getPhysicalId));
        return objects.stream().map(object -> ((BigInteger)object).longValue()).toList();
    }

    @Override
    public String getFilenameByPhysicalIdWithUserId(Long docId, Long userId) {
        return userFileService.getObj(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getPhysicalId, docId)
                .select(UserFile::getName), Object::toString
        );
    }

    @Override
    public List<VirtualFileVO> listWithParentId(Long targetId, Long rootId) {

        // 1. 验证目标目录是否在根Id的管辖下
        boolean hasPermission = userFolderService.checkPermissionByCTE(targetId, rootId);
        if (!hasPermission) {
            return Collections.emptyList();
        }

        // 2. 直接获取目标目录下所有对象
        return fileManagerService.getVirtualFileList(targetId, null);
    }

    @Override
    public List<VirtualFileVO> list(List<Long> fileIds, List<Long> folderIds) {
        return fileManagerService.getVirtualFileList(fileIds, folderIds);
    }


    @Override
    public List<ItemIdentity> filterItemsUnderRoot(Long rootId, List<ItemIdentity> items) {
        return userFolderService.filterItemsUnderRoot(rootId, items);
    }


    @Override
    @GlobalTransactional(rollbackFor = Exception.class)
    public void copyBatch(List<ItemIdentity> validItems, Long userId, Long targetFolderId) {
        // ============ 1. 前置校验 ============
        if (validItems == null || validItems.isEmpty()) return;
        if (targetFolderId == null) throw new IllegalArgumentException("目标文件夹不能为空");

        // 检查目标文件夹归属
        boolean targetExists = userFolderService.exists(
                new LambdaQueryWrapper<UserFolder>()
                        .eq(UserFolder::getId, targetFolderId)
                        .eq(UserFolder::getUserId, userId)
        );
        if (!targetExists) throw new IllegalArgumentException("目标文件夹不属于该用户");

        // ============ 2. 获取所有源数据（文件夹树 + 文件） ============
        ItemGroup group = ItemGroup.from(validItems);

        // 2.1 查询所有源文件夹（包括子孙）
        List<UserFolder> allFolders = new ArrayList<>();
        if (!group.folderIds().isEmpty()) {
            allFolders = userFolderService.selectSubTreeBatch(group.folderIds());
        }
        Set<Long> allFolderIds = allFolders.stream().map(UserFolder::getId).collect(Collectors.toSet());
        allFolderIds.addAll(group.folderIds());

        // 2.2 查询所有关联文件
        List<UserFile> allFiles = new ArrayList<>();
        if (!allFolderIds.isEmpty()) {
            allFiles.addAll(userFileService.list(
                    new LambdaQueryWrapper<UserFile>().in(UserFile::getParentId, allFolderIds)
            ));
        }
        if (!group.fileIds().isEmpty()) {
            allFiles.addAll(userFileService.list(
                    new LambdaQueryWrapper<UserFile>().in(UserFile::getId, group.fileIds())
            ));
        }
        allFiles = allFiles.stream().distinct().collect(Collectors.toList());

        // 构建源文件夹映射（快速取用）
        Map<Long, UserFolder> folderMap = allFolders.stream()
                .collect(Collectors.toMap(UserFolder::getId, Function.identity()));

        // 构建父子关系
        Map<Long, List<UserFolder>> parentToChildren = allFolders.stream()
                .filter(f -> !group.folderIds().contains(f.getId()))
                .collect(Collectors.groupingBy(UserFolder::getParentId));

        // ============ 3. BFS 复制文件夹 ============
        Map<Long, Long> oldToNew = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>(group.folderIds());

        // 逐层处理，维护目标父目录下的已有文件夹缓存（批量查询）
        while (!queue.isEmpty()) {
            // 取出当前层的所有节点
            Set<Long> currentLevel = new HashSet<>();
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                currentLevel.add(queue.poll());
            }

            // 为当前层的每个节点确定目标父目录ID
            Map<Long, Long> currentOldToNewParent = new HashMap<>();
            for (Long oldId : currentLevel) {
                Long newParentId = oldToNew.getOrDefault(oldId, targetFolderId);
                // 如果 oldId 是根节点（在 group.folderIds() 中），则 newParentId 是 targetFolderId
                // 如果 oldId 是子孙节点，则 newParentId 已在 oldToNew 中
                currentOldToNewParent.put(oldId, newParentId);
            }

            // 批量查询这些目标父目录下已有的子文件夹（去重）
            Set<Long> targetParentIds = new HashSet<>(currentOldToNewParent.values());
            List<UserFolder> existingChildren = userFolderService.list(
                    new LambdaQueryWrapper<UserFolder>()
                            .in(UserFolder::getParentId, targetParentIds)
                            .eq(UserFolder::getUserId, userId)
                            .isNull(UserFolder::getDeletedAt)
            );
            // 构建 (parentId, name) -> folderId 的映射
            Map<String, Long> existingMap = existingChildren.stream()
                    .collect(Collectors.toMap(
                            f -> f.getParentId() + "-" + f.getName(),
                            UserFolder::getId,
                            (a, b) -> a
                    ));

            // 处理当前层的每个文件夹
            for (Long oldId : currentLevel) {
                UserFolder source = folderMap.get(oldId);
                if (source == null) continue;

                Long newParentId = currentOldToNewParent.get(oldId);
                String key = newParentId + "-" + source.getName();
                Long existingId = existingMap.get(key);
                if (existingId != null) {
                    oldToNew.put(oldId, existingId);
                } else {
                    // 新建文件夹
                    UserFolder newFolder = new UserFolder();
                    BeanUtils.copyProperties(source, newFolder, "id", "createTime", "updateTime", "deletedAt");
                    newFolder.setUserId(userId);
                    newFolder.setParentId(newParentId);
                    userFolderService.save(newFolder);
                    oldToNew.put(oldId, newFolder.getId());
                    // 将新文件夹加入 existingMap，供同层其他节点可能复用（但同层不会重复）
                    existingMap.put(key, newFolder.getId());
                }
                // 将当前文件夹的子节点入队（下一层）
                List<UserFolder> children = parentToChildren.getOrDefault(oldId, Collections.emptyList());
                for (UserFolder child : children) {
                    queue.offer(child.getId());
                }
            }
        }

        // ============ 4. 复制文件 ============
        if (allFiles.isEmpty()) return;
        // ---------- 4.0 计算待复制文件总大小 ----------
        long totalFileSize = allFiles.stream()
                .mapToLong(UserFile::getSize)   // 假设 UserFile 中有 size 字段（long）
                .sum();

        if (totalFileSize > 0) {
            // 远程调用配额服务（Dubbo + Seata 全局事务）
            boolean deducted = quotaDubboService.subtractUsedQuota(userId, totalFileSize);
            if (!deducted) {
                throw new IllegalStateException("用户可用配额不足，无法完成复制操作");
            }
        }


        // 收集所有目标父目录ID（用于查询冲突）
        Set<Long> targetParentIds = allFiles.stream()
                .map(f -> oldToNew.getOrDefault(f.getParentId(), targetFolderId))
                .collect(Collectors.toSet());

        // 查询目标目录下已存在的文件（当前用户，未删除）
        List<UserFile> existingFiles = userFileService.list(
                new LambdaQueryWrapper<UserFile>()
                        .in(UserFile::getParentId, targetParentIds)
                        .eq(UserFile::getUserId, userId)
                        .isNull(UserFile::getDeletedAt)
        );
        Map<String, Long> existFileMap = existingFiles.stream()
                .collect(Collectors.toMap(
                        f -> f.getParentId() + "-" + f.getName(),
                        UserFile::getId,
                        (a, b) -> a
                ));

        // 生成待插入文件列表
        List<UserFile> newFiles = new ArrayList<>();
        for (UserFile source : allFiles) {
            Long newParentId = oldToNew.getOrDefault(source.getParentId(), targetFolderId);
            String key = newParentId + "-" + source.getName();
            UserFile newFile = new UserFile();
            BeanUtils.copyProperties(source, newFile, "id", "createTime", "updateTime", "deleted");
            newFile.setUserId(userId);
            newFile.setParentId(newParentId);
            if (existFileMap.containsKey(key)) {
                newFile.setName(generateNameWithTimestamp(source.getName()));
            } else {
                newFile.setName(source.getName());
            }
            newFiles.add(newFile);
        }

        if (!newFiles.isEmpty()) {
            userFileService.saveBatch(newFiles, 1000);
        }
        //TODO 缓存失效
    }


    private String generateNameWithTimestamp(String originalName) {
        int dotIndex = originalName.lastIndexOf(".");
        String baseName = (dotIndex == -1) ? originalName : originalName.substring(0, dotIndex);
        String extension = (dotIndex == -1) ? "" : originalName.substring(dotIndex);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        return baseName + "_" + timestamp + extension;
    }
}
