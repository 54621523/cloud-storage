package demo.cloud.file.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import demo.cloud.file.dto.FilePhysicalDTO;
import demo.cloud.file.dto.ItemGroup;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.dto.VirtualFileVO;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.pojo.UserFile;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.FileDubboService;
import demo.cloud.file.service.FileManagerService;
import demo.cloud.file.service.FilePhysicalService;
import demo.cloud.file.service.UserFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@DubboService
@RequiredArgsConstructor
@Slf4j
public class FileDubboServiceImpl implements FileDubboService {


    private final FileManagerService fileManagerService;

    private final UserFileService userFileService;
    private final FolderService userFolderService;
    private final FilePhysicalService filePhysicalService;

    private final S3Presigner s3Presigner;


    @Override
    public FilePhysicalDTO getPhysicalFileByUserIdAndUserFileId(Long userFileId, Long userId) {
//        UserFile one = userFileService.getOne(
//                new LambdaQueryWrapper<UserFile>()
//                        .eq(UserFile::getId, userFileId)
//                        .eq(UserFile::getUserId, userId));
//        FilePhysical one1 = filePhysicalService.getOne(
//                new LambdaQueryWrapper<FilePhysical>()
//                        .eq(FilePhysical::getId, one.getPhysicalId())
//                        .select(FilePhysical::getOssKey)
//                        .select(FilePhysical::getId));
//        FilePhysicalDTO filePhysicalDTO = new FilePhysicalDTO();
//        BeanUtils.copyProperties(one1,dto);
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
            // TODO
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
    @Transactional(rollbackFor = Exception.class)
    public void copyBatch(List<ItemIdentity> validItems, Long userId, Long targetFolderId) {
        long methodStart = System.currentTimeMillis();
        log.info("========== copyBatch 开始 ========== validItems数量: {}, userId: {}, targetFolderId: {}",
                validItems == null ? 0 : validItems.size(), userId, targetFolderId);

        if (validItems == null || validItems.isEmpty()) {
            log.warn("转存文件不能为空");
            return;
        }
        if (targetFolderId == null) {
            throw new IllegalArgumentException("目标文件夹不能为空");
        }
        boolean exists = userFolderService.exists(
                new LambdaQueryWrapper<UserFolder>()
                        .eq(UserFolder::getId, targetFolderId)
                        .eq(UserFolder::getUserId, userId)
        );
        if(!exists){
            throw new IllegalArgumentException("目标文件夹不属于该用户");
        }


        // ==================== 1. 查询所有需要复制的源数据 ====================
        long step1Start = System.currentTimeMillis();
        ItemGroup group = ItemGroup.from(validItems);

        // 批量查询所有源文件夹（含子孙）
        List<UserFolder> allFoldersToCopy = new ArrayList<>();
        if (!group.folderIds().isEmpty()) {
            allFoldersToCopy = userFolderService.selectSubTreeBatch(group.folderIds());
        }
        log.info("步骤1-1: 查询源文件夹树完成，文件夹数量: {}, 耗时: {}ms",
                allFoldersToCopy.size(), System.currentTimeMillis() - step1Start);

        // 收集所有文件夹ID（用于查询文件）
        Set<Long> allFolderIds = allFoldersToCopy.stream()
                .map(UserFolder::getId)
                .collect(Collectors.toSet());
        allFolderIds.addAll(group.folderIds());

        // 查询所有关联文件
        long fileQueryStart = System.currentTimeMillis();
        List<UserFile> allFilesToCopy = new ArrayList<>();
        if (!allFolderIds.isEmpty()) {
            allFilesToCopy.addAll(userFileService.list(
                    new LambdaQueryWrapper<UserFile>().in(UserFile::getParentId, allFolderIds)
            ));
        }
        if (!group.fileIds().isEmpty()) {
            allFilesToCopy.addAll(userFileService.list(
                    new LambdaQueryWrapper<UserFile>().in(UserFile::getId, group.fileIds())
            ));
        }
        allFilesToCopy = allFilesToCopy.stream().distinct().collect(Collectors.toList());
        log.info("步骤1-2: 查询文件完成，文件数量: {}, 耗时: {}ms",
                allFilesToCopy.size(), System.currentTimeMillis() - fileQueryStart);
        log.info("步骤1总计耗时: {}ms", System.currentTimeMillis() - step1Start);

        // ==================== 2. 数据预处理 ====================
        long step2Start = System.currentTimeMillis();
        Map<Long, List<UserFolder>> parentToChildrenMap = allFoldersToCopy.stream()
                .filter(f -> !group.folderIds().contains(f.getId()))
                .collect(Collectors.groupingBy(UserFolder::getParentId));

        Map<Long, UserFolder> sourceFolderMap = allFoldersToCopy.stream()
                .collect(Collectors.toMap(UserFolder::getId, Function.identity()));

        Map<Long, Long> oldToNewFolderMap = new HashMap<>();
        log.info("步骤2 数据预处理耗时: {}ms", System.currentTimeMillis() - step2Start);

        // ==================== 3. 目标目录缓存 ====================
        Map<Long, Map<String, Long>> targetCache = new HashMap<>();

        // 辅助方法：批量加载目标父目录下的已有子文件夹
        AtomicInteger loadCount = new AtomicInteger(0); // 统计loadTargetChildren调用次数
        BiConsumer<Set<Long>, Long> loadTargetChildren = (parentIds, uid) -> {
            long loadStart = System.currentTimeMillis();
            loadCount.incrementAndGet();
            Set<Long> missing = parentIds.stream()
                    .filter(pid -> !targetCache.containsKey(pid))
                    .collect(Collectors.toSet());
            if (missing.isEmpty()) {
                log.debug("loadTargetChildren 命中缓存，跳过查询");
                return;
            }

            List<UserFolder> existing = userFolderService.list(
                    new LambdaQueryWrapper<UserFolder>()
                            .in(UserFolder::getParentId, missing)
                            .eq(UserFolder::getUserId, uid)
                            .isNull(UserFolder::getDeletedAt)
            );
            for (UserFolder f : existing) {
                targetCache.computeIfAbsent(f.getParentId(), k -> new HashMap<>())
                        .put(f.getName(), f.getId());
            }
            for (Long pid : missing) {
                targetCache.putIfAbsent(pid, new HashMap<>());
            }
            log.debug("loadTargetChildren 查询完成，parentIds: {}, 查询到已有文件夹: {}, 耗时: {}ms",
                    missing, existing.size(), System.currentTimeMillis() - loadStart);
        };

        // ==================== 4. 处理根文件夹 ====================
        long step4Start = System.currentTimeMillis();
        // 预先加载目标根目录下的已有文件夹
        loadTargetChildren.accept(Collections.singleton(targetFolderId), userId);
        Map<String, Long> rootChildMap = targetCache.get(targetFolderId);

        for (Long rootOldId : group.folderIds()) {
            UserFolder sourceRoot = sourceFolderMap.get(rootOldId);
            if (sourceRoot == null) continue;

            Long existingId = rootChildMap.get(sourceRoot.getName());
            if (existingId != null) {
                oldToNewFolderMap.put(rootOldId, existingId);
            } else {
                UserFolder newRoot = new UserFolder();
                BeanUtils.copyProperties(sourceRoot, newRoot, "id", "createTime", "updateTime", "deleted");
                newRoot.setUserId(userId);
                newRoot.setParentId(targetFolderId);
                newRoot.setName(sourceRoot.getName());
                userFolderService.save(newRoot);

                oldToNewFolderMap.put(rootOldId, newRoot.getId());
                rootChildMap.put(sourceRoot.getName(), newRoot.getId());
            }
        }
        log.info("步骤4 处理根文件夹完成，根文件夹数量: {}, 耗时: {}ms",
                group.folderIds().size(), System.currentTimeMillis() - step4Start);

        // ==================== 5. 复制子孙文件夹（BFS） ====================
        long step5Start = System.currentTimeMillis();
        int totalSubFolders = 0;
        Deque<Long> queue = new ArrayDeque<>(group.folderIds());

        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            List<Long> currentLevelNodes = new ArrayList<>(levelSize);
            for (int i = 0; i < levelSize; i++) {
                currentLevelNodes.add(queue.poll());
            }

            Set<Long> targetParentIds = currentLevelNodes.stream()
                    .map(oldToNewFolderMap::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (!targetParentIds.isEmpty()) {
                loadTargetChildren.accept(targetParentIds, userId);
            }

            for (Long currentOldId : currentLevelNodes) {
                Long newParentId = oldToNewFolderMap.get(currentOldId);
                if (newParentId == null) continue;

                List<UserFolder> children = parentToChildrenMap.getOrDefault(currentOldId, Collections.emptyList());
                if (children.isEmpty()) continue;

                Map<String, Long> childMap = targetCache.get(newParentId);
                for (UserFolder childSource : children) {
                    Long existingChildId = childMap.get(childSource.getName());
                    if (existingChildId != null) {
                        oldToNewFolderMap.put(childSource.getId(), existingChildId);
                    } else {
                        UserFolder newChild = new UserFolder();
                        BeanUtils.copyProperties(childSource, newChild, "id", "createTime", "updateTime", "deleted");
                        newChild.setUserId(userId);
                        newChild.setParentId(newParentId);
                        newChild.setName(childSource.getName());
                        userFolderService.save(newChild);

                        oldToNewFolderMap.put(childSource.getId(), newChild.getId());
                        childMap.put(childSource.getName(), newChild.getId());
                    }
                    queue.offer(childSource.getId());
                    totalSubFolders++;
                }
            }
        }
        log.info("步骤5 复制子孙文件夹完成，共处理子文件夹: {}, loadTargetChildren调用次数: {}, 耗时: {}ms",
                totalSubFolders, loadCount.get(), System.currentTimeMillis() - step5Start);

        // ==================== 6. 复制文件 ====================
        long step6Start = System.currentTimeMillis();
        List<UserFile> newFileList = new ArrayList<>();

        if (!allFilesToCopy.isEmpty()) {
            // 1. 收集所有目标父目录ID
            Set<Long> targetParentIds = allFilesToCopy.stream()
                    .map(f -> oldToNewFolderMap.getOrDefault(f.getParentId(), targetFolderId))
                    .collect(Collectors.toSet());

            // 2. 查询目标目录下已存在的文件（当前用户，未删除）
            List<UserFile> existingFiles = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .in(UserFile::getParentId, targetParentIds)
                            .eq(UserFile::getUserId, userId)
                            .isNull(UserFile::getDeletedAt)
            );

            // 3. 构建存在映射 (parentId + name) -> fileId
            Map<String, Long> existMap = existingFiles.stream()
                    .collect(Collectors.toMap(
                            f -> f.getParentId() + "-" + f.getName(),
                            UserFile::getId,
                            (a, b) -> a
                    ));

            // 4. 仅插入不存在的文件（已存在的跳过）
            for (UserFile sourceFile : allFilesToCopy) {
                Long newParentId = oldToNewFolderMap.getOrDefault(sourceFile.getParentId(), targetFolderId);
                String key = newParentId + "-" + sourceFile.getName();
                if (existMap.containsKey(key)) {
                    // 重命名后加入列表
                    UserFile newFile = new UserFile();
                    BeanUtils.copyProperties(sourceFile, newFile, "id", "createTime", "updateTime", "deleted");
                    newFile.setUserId(userId);
                    newFile.setParentId(newParentId);
                    newFile.setName(generateNameWithTimestamp(sourceFile.getName()));
                    newFileList.add(newFile);
                    continue;
                }
                UserFile newFile = new UserFile();
                BeanUtils.copyProperties(sourceFile, newFile, "id", "createTime", "updateTime", "deleted");
                newFile.setUserId(userId);
                newFile.setParentId(newParentId);
                newFile.setName(sourceFile.getName());
                newFileList.add(newFile);
            }
        }

        if (!newFileList.isEmpty()) {
            long insertStart = System.currentTimeMillis();
            // 直接批量插入（因为已经过滤掉冲突文件，不会触发 DuplicateKeyException）
            userFileService.saveBatch(newFileList, 1000);  // 改用普通批量插入，无需重试
            log.info("步骤6-1 批量插入文件耗时: {}ms, 实际插入文件数: {}",
                    System.currentTimeMillis() - insertStart, newFileList.size());
        } else {
            log.info("步骤6: 所有文件均已存在，无需插入");
        }
        log.info("步骤6 复制文件总耗时: {}ms", System.currentTimeMillis() - step6Start);

        log.info("========== copyBatch 总耗时: {}ms ==========", System.currentTimeMillis() - methodStart);
    }


    private String generateNameWithTimestamp(String originalName) {
        int dotIndex = originalName.lastIndexOf(".");
        String baseName = (dotIndex == -1) ? originalName : originalName.substring(0, dotIndex);
        String extension = (dotIndex == -1) ? "" : originalName.substring(dotIndex);
        // 格式化为毫秒级时间戳，确保同一毫秒内重试也能区分
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        return baseName + "_" + timestamp + extension;
    }
}
