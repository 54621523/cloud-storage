package demo.cloud.file.service;

import com.baomidou.mybatisplus.extension.service.IService;
import demo.cloud.file.pojo.UserFolder;

import java.util.List;
import java.util.Map;

public interface UserFolderService extends IService<UserFolder> {

    Map<String, Long> saveFolders(Long userId, Long rootParentId, String fullPath);


    List<UserFolder> resolveNameConflicts(List<UserFolder> moveFiles, Long userId, Long parentId);

    /**
     * 校验父目录是否存在且不在回收站
     */
    void validateParent(Long userId, Long parentId, String itemName);
}
