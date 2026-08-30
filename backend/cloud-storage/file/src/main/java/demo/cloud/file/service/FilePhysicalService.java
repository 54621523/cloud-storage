package demo.cloud.file.service;

import com.baomidou.mybatisplus.extension.service.IService;
import demo.cloud.file.pojo.FilePhysical;

import java.util.Collection;

public interface FilePhysicalService extends IService<FilePhysical> {

    void increaseRef(Long id);

    
    void increaseRef(Collection<Long> id);

    
    void increaseRef(Long... id);


    
    void decreaseRef(Long id);

    
    void decreaseRef(Collection<Long> id);

    
    void decreaseRef(Long... id);

    Long getOrCreatePhysicalId(String md5, long size, String ossKey);
}
