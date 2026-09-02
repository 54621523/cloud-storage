package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import demo.cloud.auth.dubboService.UserQuotaDubboService;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.common.pojo.PageResult;
import demo.cloud.file.cache.CacheInvalidationHelper;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.*;
import demo.cloud.file.pojo.FileDocument;
import demo.cloud.file.pojo.UserFile;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.*;
import demo.cloud.file.service.search.FileSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.apache.seata.tm.api.transaction.TransactionHook;
import org.apache.seata.tm.api.transaction.TransactionHookManager;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileManagerServiceImpl implements FileManagerService {

    private final UserFileService userFileService;
    private final UserFolderService userFolderService;
    private final UserRecycleBinService userRecycleBinService;
    private final FilePhysicalService filePhysicalService;
    private final ObjectMapper objectMapper;
    private final CacheInvalidationHelper cache;
    private final UserQuotaDubboService quotaDubboService;

    private final RabbitTemplate rabbitTemplate;
    private static final String EXCHANGE_NAME = "file.exchange";


    private final FileSearchRepository fileSearchRepository;
    // ================= 缓存实现 ========================
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String DIR_CACHE_PREFIX = "dir:members:";
    private static final long BASE_TTL_SECONDS = 600;       // 10分钟
    private static final int OFFSET_SECONDS = 120;          // 随机偏移 ±2分钟

    // ====== Create ======

    @Transactional
    @Override
    public void createFolder(CreateFolderRequest request, Long userId) {
        userFolderService.validateParent(userId, request.getParentId(), null);
        UserFolder folder = new UserFolder();
        folder.setParentId(request.getParentId());
        folder.setName(request.getName());
        folder.setUserId(userId);
        try{
            userFolderService.saveFolder(folder);
            cache.evictDirectoryCache(request.getParentId());
        }catch (DuplicateKeyException e) {
            throw new BusinessException(0, "该目录下已存在同名文件夹");
        }
    }

    /**
     *
     */
    public void addDocument(Long id, FileItemType type) {
        if (type == null) {
            throw new IllegalArgumentException("类型不能为空");
        }

        FileDocument doc = new FileDocument();
        doc.setId(id);
        doc.setType(type);
        doc.setStatus(0); // 默认正常

        if (type == FileItemType.FILE) {
            // 查询文件表
            UserFile file = userFileService.getOne(
                    new LambdaQueryWrapper<UserFile>()
                            .eq(UserFile::getId, id)
                            .last("LIMIT 1")
            );
            if (file == null) {
                log.warn("文件不存在，id: {}", id);
                return;
            }
            doc.setName(file.getName());
            doc.setParentId(file.getParentId());
            doc.setSize(file.getSize() != null ? file.getSize() : 0L);
            doc.setUserId(file.getUserId());
            doc.setUpdateTime(file.getUpdateTime());

            // 解析扩展名
            String name = file.getName();
            if (name != null && name.contains(".")) {
                int lastDot = name.lastIndexOf('.');
                doc.setNamePure(name.substring(0, lastDot));
                doc.setExtension(name.substring(lastDot + 1));
            } else {
                doc.setNamePure(name);
                doc.setExtension(null);
            }
            // contentPreview 可后续异步填充
        } else if (type == FileItemType.FOLDER) {
            // 查询文件夹表
            UserFolder folder = userFolderService.getOne(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getId, id)
                            .last("LIMIT 1")
            );
            if (folder == null) {
                log.warn("文件夹不存在，id: {}", id);
                return;
            }
            doc.setName(folder.getName());
            doc.setParentId(folder.getParentId());
            doc.setSize(0L);                     // 文件夹无大小
            doc.setUserId(folder.getUserId());
            doc.setUpdateTime(folder.getUpdateTime());

            // 文件夹无扩展名，直接用名字作为 namePure
            doc.setNamePure(folder.getName());
            doc.setExtension(null);
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        fileSearchRepository.addDocument(doc);
        log.info("ES索引添加成功，id: {}, type: {}", id, type);
    }

    public void addDocuments(List<ItemIdentity> entries) {
        if (entries.isEmpty()) {
            return;
        }
        ItemGroup group = ItemGroup.from(entries);

        // 1. 按类型分组，分别处理
        List<FileDocument> docList = new ArrayList<>();

        // 2. 批量处理文件
        if (!group.fileIds().isEmpty()) {
            List<UserFile> files = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .in(UserFile::getId, group.fileIds())
            );
            for (UserFile file : files) {
                FileDocument doc = buildFileDocument(file);
                docList.add(doc);
            }
        }

        // 3. 批量处理文件夹
        if (!group.folderIds().isEmpty()) {
            List<UserFolder> folders = userFolderService.list(
                    new LambdaQueryWrapper<UserFolder>()
                            .in(UserFolder::getId, group.folderIds())
            );
            for (UserFolder folder : folders) {
                FileDocument doc = buildFolderDocument(folder);
                docList.add(doc);
            }
        }

        // 4. 批量写入 Meilisearch
        if (!docList.isEmpty()) {
            // 分批写入，防止单次请求体过大（Meilisearch 默认限制 100MB，建议每批 1000 条）
            int batchSize = 1000;
            for (int i = 0; i < docList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, docList.size());
                List<FileDocument> batch = docList.subList(i, end);
                fileSearchRepository.addDocuments(batch);
                log.info("批量索引写入成功，批次：{}-{}，共 {} 条", i, end-1, batch.size());
            }
        }
    }

    public void addDocuments(List<Long> ids, FileItemType type) {
        if (ids.isEmpty()) {
            return;
        }
        List<FileDocument> docList = new ArrayList<>();
        // 处理文件
        if (type.equals(FileItemType.FILE)) {
            List<UserFile> files = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .in(UserFile::getId, ids)
            );
            for (UserFile file : files) {
                FileDocument doc = buildFileDocument(file);
                docList.add(doc);
            }
        }

        // 处理文件夹
        if (type.equals(FileItemType.FOLDER)) {
            List<UserFolder> folders = userFolderService.list(
                    new LambdaQueryWrapper<UserFolder>()
                            .in(UserFolder::getId, ids)
            );
            for (UserFolder folder : folders) {
                FileDocument doc = buildFolderDocument(folder);
                docList.add(doc);
            }
        }
        // 4. 批量写入 Meilisearch
        if (!docList.isEmpty()) {
            // 分批写入，防止单次请求体过大（Meilisearch 默认限制 100MB，建议每批 1000 条）
            int batchSize = 1000;
            for (int i = 0; i < docList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, docList.size());
                List<FileDocument> batch = docList.subList(i, end);
                fileSearchRepository.addDocuments(batch);
                log.info("批量索引写入成功，批次：{}-{}，共 {} 条", i, end-1, batch.size());
            }
        }
    }

    // ====== Read ======

    /**
     * 根方法
     * 获取指定目录下的虚拟文件列表
     */
    public List<VirtualFileVO> getVirtualFileList(Long parentId, Long userId) {

        String cacheKey = DIR_CACHE_PREFIX + ":" + parentId;
        String cachedJson = (String) redisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            try {
                // 使用 Jackson 反序列化
                return objectMapper.readValue(cachedJson, new TypeReference<List<VirtualFileVO>>() {});
            } catch (JsonProcessingException e) {
                log.error("反序列化缓存失败，缓存 Key: {}", cacheKey, e);
                // 损坏的缓存直接删除，避免反复出错
                cache.evictDirectoryCache(parentId);
            }
        }

        // 1.1. 构建文件夹查询条件
        LambdaQueryWrapper<UserFolder> folderWrapper = new LambdaQueryWrapper<UserFolder>()
                .eq(UserFolder::getParentId, parentId)
                .isNull(UserFolder::getDeletedAt);
        // 1.2. 构建文件查询条件
        LambdaQueryWrapper<UserFile> fileWrapper = new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getParentId, parentId)
                .isNull(UserFile::getDeletedAt);
        // 2. 如果传入用户名说明使用用户名鉴权
        if (userId != null) {
            folderWrapper.eq(UserFolder::getUserId, userId);
            fileWrapper.eq(UserFile::getUserId, userId);
        }

        // 3. 查询
        List<UserFolder> folders = userFolderService.list(folderWrapper);
        List<UserFile> files = userFileService.list(fileWrapper);

        // 4. 转换并排序返回
        List<VirtualFileVO> result = mergeAndConvert(folders, files);
        try {
            String json = objectMapper.writeValueAsString(result);
            long ttl = BASE_TTL_SECONDS + ThreadLocalRandom.current().nextInt(-OFFSET_SECONDS, OFFSET_SECONDS + 1);
            redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(Math.max(ttl, 1)));
        } catch (JsonProcessingException e) {
            log.error("序列化缓存失败", e);
        }
        return result;
    }

    @Override
    public List<VirtualFileVO> getVirtualFolderListOnly(Long parentId, Long userId) {
        // 1.1. 构建文件夹查询条件
        LambdaQueryWrapper<UserFolder> folderWrapper = new LambdaQueryWrapper<UserFolder>()
                .eq(UserFolder::getParentId, parentId)
                .isNull(UserFolder::getDeletedAt);
        // 2. 如果传入用户名说明使用用户名鉴权
        if (userId != null) {
            folderWrapper.eq(UserFolder::getUserId, userId);
        }

        // 3. 查询
        List<UserFolder> folders = userFolderService.list(folderWrapper);

        // 4. 转换并排序返回
        return mergeAndConvert(folders, Collections.emptyList());
    }

    @Override
    public List<VirtualFileVO> getVirtualFileList(List<Long> fileIds, List<Long> folderIds) {
        List<UserFile> files = new ArrayList<>();
        List<UserFolder> folders = new ArrayList<>();
        if (folderIds != null && !folderIds.isEmpty()) {
            folders = userFolderService.list(
                    new LambdaQueryWrapper<UserFolder>()
                            .in(UserFolder::getId, folderIds)
                            .isNull(UserFolder::getDeletedAt)
            );
        }
        if (fileIds != null && !fileIds.isEmpty()) {
            files = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .in(UserFile::getId, fileIds)
                            .isNull(UserFile::getDeletedAt)
            );
        }
        return mergeAndConvert(folders, files);
    }

    @Override
    public PageResult<RecycleFileVO> queryMyRecycleBin(Long pageNum, Long pageSize, Long userId) {
        Page<RecycleFileVO> page = new Page<>(pageNum, pageSize);
        IPage<RecycleFileVO> recycleFileVOPage = userRecycleBinService.selectRecycleBin(page, userId);
        return PageResult.of(recycleFileVOPage);
    }


    @Override
    public PageResult<VirtualFileVO> search(String keyword, Long userId, Integer page, Integer size) {
        int currentPage = (page == null || page < 1) ? 1 : page;
        int pageSize = (size == null || size < 1) ? 10 : size;

        // 1. 从 搜索引擎 查询
        PageResult<FileDocument> pageResult = fileSearchRepository.searchFile(
                keyword, userId, null, null, null, null, currentPage, pageSize
        );
        log.info("搜索引擎返回原始数据：{}", pageResult);

        // 2. 一键转换并返回
        return convertPage(pageResult);
    }


    // ====== Update ======


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(RestoreRequest request, Long userId) {
        ItemGroup group = ItemGroup.from(request.getItems());
        Set<Long> parentIdsToEvict = new HashSet<>(); // 收集需要清除缓存的目录ID
        // 1. 处理文件恢复（存在同名则重命名）
        if (!group.fileIds().isEmpty()) {
            List<UserFile> files = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .in(UserFile::getId, group.fileIds())
                            .eq(UserFile::getUserId, userId)
                            .isNotNull(UserFile::getDeletedAt)
            );
            if (files.size() != group.fileIds().size()) {
                throw new BusinessException(0, "部分项目不存在或已被删除，请刷新后重试");
            }

            // 按原始父目录分组
            Map<Long, List<UserFile>> fileGroupMap = files.stream()
                    .collect(Collectors.groupingBy(UserFile::getParentId));

            List<UserFile> resolvedFiles = new ArrayList<>();
            for (Map.Entry<Long, List<UserFile>> entry : fileGroupMap.entrySet()) {
                Long actualParentId = entry.getKey();
                userFolderService.validateParent(userId, actualParentId, entry.getValue().get(0).getName());
                resolvedFiles.addAll(
                        userFileService.resolveNameConflicts(entry.getValue(), userId, actualParentId)
                );
                parentIdsToEvict.add(actualParentId);
            }

            // 批量更新（清空 deleted_at + 更新 name）
            for (UserFile file : resolvedFiles) {
                file.setDeletedAt(null); // 清空删除标记
            }
            userFileService.updateBatchById(resolvedFiles); // 批量更新
        }

        // 2. 处理文件夹恢复（存在同名则重命名）
        if (!group.folderIds().isEmpty()) {
            List<UserFolder> folders = userFolderService.list(
                    new LambdaQueryWrapper<UserFolder>()
                            .in(UserFolder::getId, group.folderIds())
                            .eq(UserFolder::getUserId, userId)
                            .isNotNull(UserFolder::getDeletedAt)
            );
            if (folders.size() != group.folderIds().size()) {
                throw new BusinessException(0, "部分项目不存在或已被删除，请刷新后重试");
            }
            Map<Long, List<UserFolder>> folderGroupMap = folders.stream()
                    .collect(Collectors.groupingBy(UserFolder::getParentId));

            List<UserFolder> resolvedFolders = new ArrayList<>();
            for (Map.Entry<Long, List<UserFolder>> entry : folderGroupMap.entrySet()) {
                Long actualParentId = entry.getKey();
                userFolderService.validateParent(userId, actualParentId, entry.getValue().get(0).getName());
                resolvedFolders.addAll(
                        userFolderService.resolveNameConflicts(entry.getValue(), userId, entry.getKey())
                );
                parentIdsToEvict.add(actualParentId);
            }
            // 批量更新
            for (UserFolder folder : resolvedFolders) {
                folder.setDeletedAt(null);
            }
            userFolderService.updateBatchById(resolvedFolders);
        }
        for (Long parentId : parentIdsToEvict) {
            cache.evictDirectoryCache(parentId);
        }
    }

    /**
     * @param id
     * @param type
     */
    @Override
    public void renameDocument(Long id, FileItemType type, String newName) {
        if(type.equals(FileItemType.FOLDER)){
            UserFolder one = userFolderService.getOne(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getId, id)
            );
            FileDocument fileDocument = buildFolderDocument(one);
            fileDocument.setName(newName);
            fileSearchRepository.updateDocument(fileDocument);
        }
        if(type.equals(FileItemType.FILE)){
            UserFile one = userFileService.getOne(
                    new LambdaQueryWrapper<UserFile>()
                            .eq(UserFile::getId, id)
            );
            FileDocument fileDocument = buildFileDocument(one);
            fileDocument.setName(newName);
            fileSearchRepository.updateDocument(fileDocument);
        }
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(RenameRequest request, Long userId) {
        if (request.getType().equals(FileItemType.FILE)) {
            try {
                boolean updated =  userFileService.update(
                        new LambdaUpdateWrapper<UserFile>()
                                .eq(UserFile::getId, request.getId())
                                .eq(UserFile::getUserId, userId)
                                .isNull(UserFile::getDeletedAt)
                                .set(UserFile::getName, request.getNewName())
                );
                if (!updated) {
                    throw new BusinessException(0, "文件不存在或已被删除");
                }
            }catch (DuplicateKeyException e){
                throw new BusinessException(0,"文件名已被占用");
            }
            UserFile one = userFileService.getOne(
                    new LambdaQueryWrapper<UserFile>()
                            .eq(UserFile::getId, request.getId())
                            .isNull(UserFile::getDeletedAt)
            );
            cache.evictDirectoryCache(one.getParentId());
            return;
        }
        if (request.getType().equals(FileItemType.FOLDER)) {
            try{
                boolean updated = userFolderService.update(
                        new LambdaUpdateWrapper<UserFolder>()
                                .eq(UserFolder::getId, request.getId())
                                .eq(UserFolder::getUserId, userId)
                                .isNull(UserFolder::getDeletedAt)
                                .set(UserFolder::getName, request.getNewName())
                );
                if (!updated){
                    throw new BusinessException(0, "文件不存在或已被删除");
                }
            }catch (DuplicateKeyException e){
                throw new BusinessException(0,"文件夹名已被占用");
            }
            UserFolder one = userFolderService.getOne(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getId, request.getId())
                            .isNull(UserFolder::getDeletedAt)
            );
            cache.evictDirectoryCache(one.getParentId());
            return;
        }

        throw new BusinessException(0,"无权访问该文件");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveTo(MoveRequest request, Long userId) {
        userFolderService.validateParent(userId, request.getTargetParentId(), null);
        // 1. 从request中分离出文件Id和文件夹Id
        ItemGroup group = ItemGroup.from(request.getItems());
        if (!group.fileIds().isEmpty()) {
            List<UserFile> moveFiles = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .in(UserFile::getId, group.fileIds())
                            .eq(UserFile::getUserId, userId)
                            .isNull(UserFile::getDeletedAt)
            );
            if (moveFiles.size() != group.fileIds().size()) {
                throw new BusinessException(0, "部分项目不存在或已被删除，请刷新后重试");
            }
            moveFiles.forEach(file -> file.setParentId(request.getTargetParentId()));
            List<UserFile> files = userFileService.resolveNameConflicts(moveFiles, userId, request.getTargetParentId());


            userFileService.updateBatchById(files);
            // 目标目录清除
            cache.evictDirectoryCache(request.getTargetParentId());
            // 源目录清除
            Long parentId = files.get(0).getParentId();
            cache.evictDirectoryCache(parentId);
        }
        if (!group.folderIds().isEmpty()) {
            List<UserFolder> moveFolders = userFolderService.list(
                    new LambdaQueryWrapper<UserFolder>()
                            .in(UserFolder::getId, group.folderIds())
                            .eq(UserFolder::getUserId, userId)
                            .isNull(UserFolder::getDeletedAt)
            );
            if (moveFolders.size() != group.folderIds().size()) {
                throw new BusinessException(0, "部分项目不存在或已被删除，请刷新后重试");
            }
            if (group.folderIds().contains(request.getTargetParentId())
                    ||
                    userFolderService.getFolderChildren(group.folderIds(), userId).contains(request.getTargetParentId())
                ) {
                throw new BusinessException(0, "不能将文件夹移动到自身或其子文件夹");
            }
            moveFolders.forEach(folder -> folder.setParentId(request.getTargetParentId()));
            List<UserFolder> folders = userFolderService.resolveNameConflicts(moveFolders, userId, request.getTargetParentId());

            userFolderService.updateBatchById(folders);
            // 目标缓存
            cache.evictDirectoryCache(request.getTargetParentId());
            // 源缓存
            Long parentId = folders.get(0).getParentId();
            cache.evictDirectoryCache(parentId);
        }
    }

    // ====== Delete ======
    @Override
    @GlobalTransactional(rollbackFor = Exception.class)  // 若需跨服务配额回退
    @Transactional
    public void deletePermanently(DeleteRequest request, Long userId) {
        ItemGroup group = ItemGroup.from(request.getItems());

        // 1. 校验顶层文件夹是否在回收站（deleted_at IS NOT NULL）
        Set<Long> validTopFolderIds = Collections.emptySet();
        if (!group.folderIds().isEmpty()) {
            List<UserFolder> validFolders = userFolderService.list(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .in(UserFolder::getId, group.folderIds())
                            .isNotNull(UserFolder::getDeletedAt)
            );
            validTopFolderIds = validFolders.stream().map(UserFolder::getId).collect(Collectors.toSet());
        }

        // 2. 获取所有子文件夹（含顶层）—— 无 deleted_at 过滤，物理删除所有子孙
        Set<Long> allFolderIds = new HashSet<>(validTopFolderIds);
        if (!validTopFolderIds.isEmpty()) {
            allFolderIds.addAll(userFolderService.getFolderChildren(new ArrayList<>(validTopFolderIds), userId));
        }

        // 3. 获取所有子文件（含子文件夹下的文件）
        Set<Long> allFileIds = new HashSet<>();
        if (!validTopFolderIds.isEmpty()) {
            allFileIds.addAll(userFileService.getFileChildren(new ArrayList<>(validTopFolderIds), userId));
        }

        // 4. 添加用户直接指定的文件（仅限在回收站中的）
        if (!group.fileIds().isEmpty()) {
            List<UserFile> validFiles = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .eq(UserFile::getUserId, userId)
                            .in(UserFile::getId, group.fileIds())
                            .isNotNull(UserFile::getDeletedAt)
            );
            allFileIds.addAll(validFiles.stream().map(UserFile::getId).collect(Collectors.toSet()));
        }

        // 5. 处理文件删除（物理文件引用计数 + 配额回退）
        if (!allFileIds.isEmpty()) {
            List<UserFile> userFiles = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .eq(UserFile::getUserId, userId)
                            .in(UserFile::getId, allFileIds)
            );
            if (!userFiles.isEmpty()) {
                // 收集物理文件 ID
                Map<Long, Long> physicalIdToDeleteCount = userFiles.stream()
                        .map(UserFile::getPhysicalId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

                // 减少引用计数
                if (!physicalIdToDeleteCount.isEmpty()) {
                    filePhysicalService.decreaseRef(physicalIdToDeleteCount);
                }

                // 计算总大小并回退配额
                long totalSize = userFiles.stream().mapToLong(UserFile::getSize).sum();
                boolean quotaSuccess = quotaDubboService.subtractUsedQuota(userId, totalSize);
                if (!quotaSuccess) {
                    throw new BusinessException(0,"配额回退失败");
                }

                // 删除 UserFile 记录
                userFileService.remove(
                        new LambdaQueryWrapper<UserFile>()
                                .eq(UserFile::getUserId, userId)
                                .in(UserFile::getId, allFileIds)
                );
            }
            registerDeleteEvent(allFileIds, userId, FileItemType.FILE);
        }

        // 6. 删除文件夹记录
        if (!allFolderIds.isEmpty()) {
            userFolderService.remove(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .in(UserFolder::getId, allFolderIds)
            );
            registerDeleteEvent(allFolderIds, userId, FileItemType.FOLDER);
        }
    }

    /**
     * @param ids
     * @param type
     */
    @Override
    public void deleteDocuments(Collection<Long> ids, FileItemType type) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        // 1. 根据类型确定前缀
        String prefix = (type == FileItemType.FILE) ? "file_" : "folder_";

        // 2. 拼接主键列表
        List<String> searchIds = ids.stream()
                .map(id -> prefix + id)
                .collect(Collectors.toList());

        // 3. 分批删除（Meilisearch 对单次请求体大小有限制，建议每批 1000 条）
        int batchSize = 1000;
        for (int i = 0; i < searchIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, searchIds.size());
            List<String> batch = searchIds.subList(i, end);

            // 调用 Repository 或直接使用 Meilisearch 客户端的批量删除 API
            fileSearchRepository.deleteDocuments(batch);

            log.info("批量删除索引成功，批次：{}-{}，共 {} 条", i, end - 1, batch.size());
        }
    }

    @Override
    @Transactional
    public void moveToRecycleBin(DeleteRequest request, Long userId) {
        ItemGroup group = ItemGroup.from(request.getItems());
        if (!group.fileIds().isEmpty()) {
            userFileService.update(
                    new LambdaUpdateWrapper<UserFile>()
                            .eq(UserFile::getUserId, userId)
                            .in(UserFile::getId, group.fileIds())
                            .isNull(UserFile::getDeletedAt)
                            .set(UserFile::getDeletedAt, LocalDateTime.now())
            );
            UserFile one = userFileService.getOne(
                    new LambdaQueryWrapper<UserFile>()
                            .eq(UserFile::getId, group.fileIds().get(0))
            );
            cache.evictDirectoryCache(one.getParentId());
        }
        if (!group.folderIds().isEmpty()) {
            userFolderService.update(
                    new LambdaUpdateWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .in(UserFolder::getId, group.folderIds())
                            .isNull(UserFolder::getDeletedAt)
                            .set(UserFolder::getDeletedAt, LocalDateTime.now())
            );
            UserFolder one = userFolderService.getOne(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getId, group.folderIds().get(0))
            );
            cache.evictDirectoryCache(one.getParentId());
        }
    }

    // ======================== 工具方法 ===================================

    /**
     * 内存组装：将文件夹和文件合并转换为 VirtualFileVO，并按规则排序
     */
    private List<VirtualFileVO> mergeAndConvert(List<UserFolder> folders, List<UserFile> files) {
        List<VirtualFileVO> result = new ArrayList<>();

        // 1. 转换文件夹
        for (UserFolder folder : folders) {
            VirtualFileVO vo = new VirtualFileVO();
            vo.setId(folder.getId());
            vo.setName(folder.getName());
            vo.setParentId(folder.getParentId());
            vo.setType(FileItemType.FOLDER);
            vo.setUpdateTime(folder.getUpdateTime() != null
                    ? folder.getUpdateTime()
                    : null);
            result.add(vo);
        }

        // 2. 转换文件
        for (UserFile file : files) {
            VirtualFileVO vo = new VirtualFileVO();
            vo.setId(file.getId());
            vo.setName(file.getName());
            vo.setParentId(file.getParentId());
            vo.setType(FileItemType.FILE);
            vo.setSize(file.getSize());
            vo.setUpdateTime(file.getUpdateTime() != null
                    ? file.getUpdateTime()
                    : null);
            result.add(vo);
        }

        // 3. 排序：文件夹排在前面，文件排在后面；同类之间按更新时间倒序（最新的在最前）
        result.sort(Comparator
                // 首先按 type 降序排（"folder" > "file"）
                .comparing(VirtualFileVO::getType).reversed()
                // 其次按 date 降序排（注意：如果 date 为 "-"，排在最后）
                .thenComparing(VirtualFileVO::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder()))
        );

        return result;
    }

    private PageResult<VirtualFileVO> convertPage(PageResult<FileDocument> source) {
        if (source == null) {
            return new PageResult<>();
        }

        // 转换列表
        List<VirtualFileVO> voList = source.getList().stream()
                .map(this::convertToVO)   // 单个转换方法（见下面）
                .collect(Collectors.toList());

        // 复制分页信息
        PageResult<VirtualFileVO> target = new PageResult<>();
        BeanUtils.copyProperties(source, target);
        target.setList(voList);

        return target;
    }

    // 单个转换方法（可复用）
    private VirtualFileVO convertToVO(FileDocument doc) {
        VirtualFileVO vo = new VirtualFileVO();
        BeanUtils.copyProperties(doc, vo);
        return vo;
    }

    private void registerDeleteEvent(Set<Long> ids, Long userId, FileItemType type) {
        TransactionHookManager.registerHook(new TransactionHook() {
            @Override
            public void afterCommit() {
                sendDeleteEvent(ids, userId, type);
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

    private void sendDeleteEvent(Set<Long> fileId, Long userId, FileItemType type) {
        FileDeleteEvent event = FileDeleteEvent.builder()
                .ids(fileId)
                .userId(userId)
                .type(type)
                .build();
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, "file.deleted", event);
    }


    private void sendRenameEvent(Set<Long> fileId, Long userId, FileItemType type) {
        FileDeleteEvent event = FileDeleteEvent.builder()
                .ids(fileId)
                .userId(userId)
                .type(type)
                .build();
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, "file.rename", event);
    }

    private FileDocument buildFileDocument(UserFile file) {
        FileDocument doc = new FileDocument();
        doc.setSearchId("file_" + file.getId());
        doc.setId(file.getId());
        doc.setType(FileItemType.FILE);
        doc.setStatus(0);
        doc.setName(file.getName());
        doc.setParentId(file.getParentId());
        doc.setSize(file.getSize() != null ? file.getSize() : 0L);
        doc.setUserId(file.getUserId());
        doc.setUpdateTime(file.getUpdateTime());

        // 解析文件名，分离纯名和扩展名
        parseFileName(doc, file.getName());

        // contentPreview 可后续异步填充
        return doc;
    }

    private FileDocument buildFolderDocument(UserFolder folder) {
        FileDocument doc = new FileDocument();
        doc.setSearchId("folder_" + folder.getId());
        doc.setId(folder.getId());
        doc.setType(FileItemType.FOLDER);
        doc.setStatus(0);
        doc.setName(folder.getName());
        doc.setParentId(folder.getParentId());
        doc.setSize(0L);
        doc.setUserId(folder.getUserId());
        doc.setUpdateTime(folder.getUpdateTime());

        // 文件夹无扩展名，纯名就是文件夹名
        doc.setNamePure(folder.getName());
        doc.setExtension(null);
        return doc;
    }

    private void parseFileName(FileDocument doc, String fileName) {
        if (StringUtils.isBlank(fileName)) {
            doc.setNamePure(fileName);
            doc.setExtension(null);
            return;
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {   // 有扩展名，且不在首位
            doc.setNamePure(fileName.substring(0, lastDot));
            doc.setExtension(fileName.substring(lastDot + 1));
        } else {
            // 无扩展名（如隐藏文件 .gitignore 被视作纯名）
            doc.setNamePure(fileName);
            doc.setExtension(null);
        }
    }
}