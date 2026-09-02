package demo.cloud.file.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.github.yulichang.base.MPJBaseService;
import demo.cloud.file.pojo.UserFile;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface UserFileService extends IService<UserFile>, MPJBaseService<UserFile> {



    void saveFile(UserFile userFile);

    void saveFiles(List<UserFile> fileList);

    void saveFiles(List<UserFile> fileList, int batchSize);


    List<UserFile> resolveNameConflicts(List<UserFile> moveFiles, Long userId, Long parentId);



    UserFile createUserFile(Long userId, Long parentId, String fileName, Long fileSize, Long physicalId);


    Set<Long> getFileChildren(Collection<Long> folderIds, Long userId);
}
