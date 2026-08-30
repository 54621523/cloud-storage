package demo.cloud.file.service;


import demo.cloud.common.pojo.PageResult;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.*;

import java.util.List;

public interface FileManagerService {





    // ====== Create ======
    void createFolder(CreateFolderRequest request, Long userId);

    void addDocument(Long id, FileItemType type);


    // ====== Read ======
    List<VirtualFileVO> getVirtualFileList(Long parentId, Long userId);

    List<VirtualFileVO> getVirtualFolderListOnly(Long parentId, Long userID);

    List<VirtualFileVO> getVirtualFileList(List<Long> fileIds, List<Long> folderIds );

    PageResult<RecycleFileVO> queryMyRecycleBin(Long pageNum, Long pageSize, Long userId);

    PageResult<VirtualFileVO> search(String keyword, Long userId, Integer page, Integer size);

    // ====== Update ======

    void rename(RenameRequest request, Long userId);
    void moveTo(MoveRequest request, Long userId);

    void moveToRecycleBin(DeleteRequest request, Long userId);
    void restore(RestoreRequest request, Long userId);


    // ====== Delete ======
    void deletePermanently(DeleteRequest request, Long userId);



}
