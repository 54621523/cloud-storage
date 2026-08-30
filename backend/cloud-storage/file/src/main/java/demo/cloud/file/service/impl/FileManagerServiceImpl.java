package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.common.pojo.PageResult;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.*;
import demo.cloud.file.pojo.FileDocument;
import demo.cloud.file.pojo.UserFile;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.FileManagerService;
import demo.cloud.file.service.FilePhysicalService;
import demo.cloud.file.service.UserFileService;
import demo.cloud.file.service.UserRecycleBinService;
import demo.cloud.file.service.search.FileSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileManagerServiceImpl implements FileManagerService {

    private final UserFileService userFileService;
    private final FolderService userFolderService;
    private final UserRecycleBinService userRecycleBinService;
    private final FilePhysicalService filePhysicalService;

    private final FileSearchRepository fileSearchRepository;

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
        userFolderService.save(folder);
        }catch (DuplicateKeyException e) {
            throw new BusinessException(0, "该目录下已存在同名文件夹");
        }
    }

    /**
     *
     */
    @Override
    public void addDocument(Long id) {
        UserFile one = userFileService.getOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getId, id)
                .eq(UserFile::getUserId, 1L)
                .last("Limit 1")
        );
        FileDocument userDocument = new FileDocument();
        userDocument.setId(one.getId());
        userDocument.setName(one.getName());
        userDocument.setParentId(one.getParentId());
        userDocument.setSize(one.getSize());
        userDocument.setUserId(one.getUserId());
        userDocument.setUpdateTime(one.getUpdateTime());
        userDocument.setType(FileItemType.FILE);
        userDocument.setStatus(0);
        fileSearchRepository.addDocument(userDocument);

    }

    // ====== Read ======

    /**
     * 根方法
     * 获取指定目录下的虚拟文件列表
     */
        public List<VirtualFileVO> getVirtualFileList(Long parentId, Long userId) {

            // 0. 获取用户根目录
            if (parentId == null || parentId == 0L) {
                UserFolder rootFolder = userFolderService.getOne(
                        new LambdaQueryWrapper<UserFolder>()
                                .eq(UserFolder::getUserId, userId)
                                .eq(UserFolder::getParentId, 0L)
                );
                if (rootFolder == null) {
                    return new ArrayList<>();
                }
                parentId = rootFolder.getId();
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
            return mergeAndConvert(folders, files);
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
    public List<VirtualFileVO> search(String keyword, Long userId){
        PageResult<FileDocument> pageResult = fileSearchRepository.searchFile(keyword, userId, null, null, null, null, 1, 10);
        log.info("搜索引擎内数据为 {}", pageResult);
        return null;
    }


    // ====== Update ======


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(RestoreRequest request, Long userId) {
        ItemGroup group = ItemGroup.from(request.getItems());

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
            }
            // 批量更新
            for (UserFolder folder : resolvedFolders) {
                folder.setDeletedAt(null);
            }
            userFolderService.updateBatchById(resolvedFolders);
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
            return;
        }

        throw new BusinessException(0,"无权访问该文件");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveTo(MoveRequest request, Long userId) {
        userFolderService.validateParent(userId, request.getParentId(), null);
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
            moveFiles.forEach(file -> file.setParentId(request.getParentId()));
            List<UserFile> files = userFileService.resolveNameConflicts(moveFiles, userId, request.getParentId());

            userFileService.updateBatchById(files);
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
            if (group.folderIds().contains(request.getParentId())
                    ||
                    userFolderService.getBaseMapper().getFolderChildren(group.folderIds(), userId).contains(request.getParentId())
                ) {
                throw new BusinessException(0, "不能将文件夹移动到自身或其子文件夹");
            }
            moveFolders.forEach(folder -> folder.setParentId(request.getParentId()));
            List<UserFolder> folders = userFolderService.resolveNameConflicts(moveFolders, userId, request.getParentId());

            userFolderService.updateBatchById(folders);
        }
    }

    // ====== Delete ======
    @Override
    @Transactional
    public void deletePermanently(DeleteRequest request, Long userId) {
        ItemGroup group = ItemGroup.from(request.getItems());

        // 1. 处理文件删除及物理文件引用减少
        if (!group.fileIds().isEmpty()) {
            // 查询待删除的 UserFile 记录（包含 fileId 字段）
            List<UserFile> userFiles = userFileService.list(
                    new LambdaQueryWrapper<UserFile>()
                            .eq(UserFile::getUserId, userId)
                            .in(UserFile::getId, group.fileIds())
                            .isNotNull(UserFile::getDeletedAt)
            );
            if (!userFiles.isEmpty()) {
                // 收集所有物理文件 ID（去重）
                Set<Long> physicalFileIds = userFiles.stream()
                        .map(UserFile::getPhysicalId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                // 减少每个物理文件的引用计数（若引用归零则物理删除）
                filePhysicalService.decreaseRef(physicalFileIds);

                // 删除 UserFile 记录
                userFileService.remove(
                        new LambdaQueryWrapper<UserFile>()
                                .eq(UserFile::getUserId, userId)
                                .in(UserFile::getId, group.fileIds())
                                .isNotNull(UserFile::getDeletedAt)
                );
            }
        }

        // 2. 处理文件夹删除（文件夹本身不关联物理文件，暂无需额外操作）
        if (!group.folderIds().isEmpty()) {
            userFolderService.remove(
                    new LambdaQueryWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .in(UserFolder::getId, group.folderIds())
                            .isNotNull(UserFolder::getDeletedAt)
            );
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
        }
        if (!group.folderIds().isEmpty()) {
            userFolderService.update(
                    new LambdaUpdateWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .in(UserFolder::getId, group.folderIds())
                            .isNull(UserFolder::getDeletedAt)
                            .set(UserFolder::getDeletedAt, LocalDateTime.now())
            );
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
}