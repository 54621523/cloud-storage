package demo.cloud.file.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.github.yulichang.base.MPJBaseService;
import demo.cloud.file.pojo.UserFile;

import java.util.List;

public interface UserFileService extends IService<UserFile>, MPJBaseService<UserFile> {



    void saveFile(UserFile userFile);

    void saveFiles(List<UserFile> fileList);

    void saveFiles(List<UserFile> fileList, int batchSize);


    List<UserFile> resolveNameConflicts(List<UserFile> moveFiles, Long userId, Long parentId);
}
