package demo.cloud.file.service;

import demo.cloud.file.dto.FilePhysicalDTO;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.dto.VirtualFileVO;

import java.util.List;

public interface FileDubboService {


    FilePhysicalDTO getPhysicalFileByUserIdAndUserFileId(Long userFileId, Long userId);

    String getOssKey(Long id);

    List<Long> getAllowedDocIdsByUserId(Long userId);

    String getFilenameByPhysicalIdWithUserId(Long docId,Long userId);

    List<VirtualFileVO> listWithParentId(Long parentId, Long rootId);

    List<VirtualFileVO> list(List<Long> fileIds, List<Long> folderIds);

    List<ItemIdentity> filterItemsUnderRoot(Long rootId, List<ItemIdentity> items);

    void copyBatch(List<ItemIdentity> validItems, Long userId, Long targetFolderId);
}
