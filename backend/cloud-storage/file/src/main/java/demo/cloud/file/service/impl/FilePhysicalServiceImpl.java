package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.file.mapper.FilePhysicalMapper;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.service.FilePhysicalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Slf4j
@Service
public class FilePhysicalServiceImpl extends ServiceImpl<FilePhysicalMapper, FilePhysical> implements FilePhysicalService {


    private final FilePhysicalMapper filePhysicalMapper;

    public FilePhysicalServiceImpl(FilePhysicalMapper filePhysicalMapper) {
        this.filePhysicalMapper = filePhysicalMapper;
    }


    @Override
    public void increaseRef(Long id) {
        filePhysicalMapper.batchIncrementRefCount(List.of(id));
    }

    @Override
    public void increaseRef(Collection<Long> id) {
        filePhysicalMapper.batchIncrementRefCount(id);
    }

    @Override
    public void increaseRef(Long... id) {
        filePhysicalMapper.batchIncrementRefCount(List.of(id));
    }


    @Override
    public void decreaseRef(Long id) {
        filePhysicalMapper.batchDecrementRefCount(List.of(id));
    }

    @Override
    public void decreaseRef(Collection<Long> id) {
        filePhysicalMapper.batchDecrementRefCount(id);
    }

    @Override
    public void decreaseRef(Long... id) {
        filePhysicalMapper.batchDecrementRefCount(List.of(id));
    }
}
