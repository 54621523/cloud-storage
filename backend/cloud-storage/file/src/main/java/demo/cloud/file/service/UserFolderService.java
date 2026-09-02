package demo.cloud.file.service;

import com.baomidou.mybatisplus.extension.service.IService;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.pojo.UserFolder;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface UserFolderService extends IService<UserFolder> {

    Map<String, Long> saveFolders(Long userId, Long rootParentId, String fullPath);

    void saveFolder(UserFolder userFolder);


    List<UserFolder> resolveNameConflicts(List<UserFolder> moveFiles, Long userId, Long parentId);

    /**
     * 校验父目录是否存在且不在回收站
     */
    void validateParent(Long userId, Long parentId, String itemName);


    boolean checkPermissionByCTE(Long targetId, Long rootId);

    List<ItemIdentity> filterItemsUnderRoot(@Param("rootId") Long rootId,
                                            @Param("items") List<ItemIdentity> items);

    List<UserFolder> selectSubTreeBatch(@Param("rootIds") List<Long> rootIds);

    Set<Long> getFolderChildren(@Param("folderIds") Collection<Long> folderIds,
                                @Param("userId") Long userId);
}
