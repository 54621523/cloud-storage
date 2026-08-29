package demo.cloud.file.service;

import com.baomidou.mybatisplus.extension.service.IService;
import demo.cloud.file.pojo.UserFolder;

import java.util.Map;

public interface UserFolderService extends IService<UserFolder> {

    Map<String, Long> saveFolders(Long userId, Long rootParentId, String fullPath);
}
