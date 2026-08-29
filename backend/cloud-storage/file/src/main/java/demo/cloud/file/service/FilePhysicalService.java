package demo.cloud.file.service;

import com.baomidou.mybatisplus.extension.service.IService;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.pojo.UploadSession;

public interface FilePhysicalService extends IService<FilePhysical> {
    FilePhysical getByMd5(String md5);

    void increaseRef(Long id);

    FilePhysical processPhysicalFile(UploadSession session, String md5);
}
