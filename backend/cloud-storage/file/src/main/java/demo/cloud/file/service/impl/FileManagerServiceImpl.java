package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.common.pojo.PageResult;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.*;
import demo.cloud.file.pojo.UserFile;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.FileManagerService;
import demo.cloud.file.service.UserFileService;
import demo.cloud.file.service.UserRecycleBinService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
public class FileManagerServiceImpl implements FileManagerService {

    private final UserFileService userFileService;
    private final FolderService userFolderService;
    private final UserRecycleBinService userRecycleBinService;


    public FileManagerServiceImpl(UserFileService userFileService, FolderService FolderService, UserRecycleBinService userRecycleBinService) {
        this.userFileService = userFileService;
        this.userFolderService = FolderService;
        this.userRecycleBinService = userRecycleBinService;
    }

    // ====== Create ======

    @Override
    public void createFolder(CreateFolderRequest request, Long userId) {
        if (!userFolderService.isOwner(request.getParentId(), userId)) {
            throw new BusinessException(0,"无权访问该文件");
        }
        userFolderService.getOrCreateFolder(userId, request.getParentId(), List.of(request.getName()));
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


    // ====== Update ======


    @Override
    public void restore(RestoreRequest request, Long userId) {
        // 1. 从request中分离出文件Id和文件夹Id
        ItemGroup group = ItemGroup.from(request.getItems());
        // 2. 还原至文件系统
        if (!group.fileIds().isEmpty()) {
            userFileService.update(
                    new LambdaUpdateWrapper<UserFile>()
                            .eq(UserFile::getUserId, userId)
                            .in(UserFile::getId, group.fileIds())
                            .set(UserFile::getDeletedAt, null)
            );
        }
        if (!group.folderIds().isEmpty()) {
            userFolderService.update(
                    new LambdaUpdateWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .in(UserFolder::getId, group.folderIds())
                            .set(UserFolder::getDeletedAt, null)
            );
        }
    }


    @Override
    public void rename(RenameRequest request, Long userId) {
        if (request.getType().equals(FileItemType.FILE)) {
            userFileService.update(
                    new LambdaUpdateWrapper<UserFile>()
                            .eq(UserFile::getId, request.getId())
                            .eq(UserFile::getUserId, userId)
                            .isNull(UserFile::getDeletedAt)
                            .set(UserFile::getName, request.getNewName())
            );
            return;
        }
        if (request.getType().equals(FileItemType.FOLDER)) {
            userFolderService.update(
                    new LambdaUpdateWrapper<UserFolder>()
                            .eq(UserFolder::getId, request.getId())
                            .eq(UserFolder::getUserId, userId)
                            .isNull(UserFolder::getDeletedAt)
                            .set(UserFolder::getName, request.getNewName())
            );
            return;
        }

        throw new BusinessException(0,"无权访问该文件");
    }

    @Override
    public void moveTo(MoveRequest request, Long userId) {
        // 1. 从request中分离出文件Id和文件夹Id
        ItemGroup group = ItemGroup.from(request.getItems());
        // 1. 移动到目标文件夹下
        if (!group.fileIds().isEmpty()) {
            userFileService.update(
                    new LambdaUpdateWrapper<UserFile>()
                            .eq(UserFile::getUserId, userId)
                            .in(UserFile::getId, group.fileIds())
                            .isNull(UserFile::getDeletedAt)
                            .set(UserFile::getParentId, request.getParentId())
            );
        }
        if (!group.folderIds().isEmpty()) {
            userFolderService.update(
                    new LambdaUpdateWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .in(UserFolder::getId, group.folderIds())
                            .isNull(UserFolder::getDeletedAt)
                            .set(UserFolder::getParentId, request.getParentId())
            );
        }
    }
    // ====== Delete ======
    @Override
    public void deletePermanently(DeleteRequest request, Long userId) {
        // 1. 从request中分离出文件Id和文件夹Id
        ItemGroup group = ItemGroup.from(request.getItems());
        // 2. 执行彻底删除
        if (!group.fileIds().isEmpty()) {
            userFileService.remove(
                    new LambdaQueryWrapper<UserFile>()
                            .eq(UserFile::getUserId, userId)
                            .in(UserFile::getId, group.fileIds())
                            .isNotNull(UserFile::getDeletedAt)
            );
        }
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
    @Transactional(rollbackFor = Exception.class)
    public void moveToRecycleBin(DeleteRequest request, Long userId) {
        // 1. 从request中分离出文件Id和文件夹Id
        ItemGroup group = ItemGroup.from(request.getItems());
        // 2. 移动至回收站
        if (!group.fileIds().isEmpty()) {
            userFileService.update(
                    new LambdaUpdateWrapper<UserFile>()
                            .eq(UserFile::getUserId, userId)
                            .in(UserFile::getId, group.fileIds())
                            .set(UserFile::getDeletedAt, LocalDateTime.now())
            );
        }
        if (!group.folderIds().isEmpty()) {
            userFolderService.update(
                    new LambdaUpdateWrapper<UserFolder>()
                            .eq(UserFolder::getUserId, userId)
                            .in(UserFolder::getId, group.folderIds())
                            .set(UserFolder::getDeletedAt, LocalDateTime.now()));
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