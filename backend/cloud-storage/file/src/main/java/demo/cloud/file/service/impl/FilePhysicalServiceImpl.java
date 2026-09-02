package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.file.mapper.FilePhysicalMapper;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.service.FilePhysicalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    /**
     * @param id_decrement
     */
    @Override
    public void decreaseRef(Map<Long, Long> id_decrement) {
        for(Map.Entry<Long, Long> entry : id_decrement.entrySet() ){
            filePhysicalMapper.decreaseRefCountByMap(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void decreaseRef(Long... id) {
        filePhysicalMapper.batchDecrementRefCount(List.of(id));
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW) // 可选，用于隔离
    public Long getOrCreatePhysicalId(String md5, long size, String ossKey) {
        // 1. 先查询是否存在（利用唯一索引，无锁）
        FilePhysical exist = filePhysicalMapper.selectOne(
                new LambdaQueryWrapper<FilePhysical>()
                        .eq(FilePhysical::getMd5, md5)
        );
        if (exist != null) {
            return exist.getId();
        }

        // 2. 不存在则尝试插入（使用 ON DUPLICATE KEY UPDATE）
        FilePhysical physical = new FilePhysical();
        physical.setMd5(md5);
        physical.setSize(size);
        physical.setOssKey(ossKey);
        physical.setCreateTime(LocalDateTime.now());
        physical.setUpdateTime(LocalDateTime.now());
        filePhysicalMapper.insertOrUpdateAndGet(physical);

        // 3. 再次查询（确保获取到 ID，可能是之前并发插入的）
        exist = filePhysicalMapper.selectOne(
                new LambdaQueryWrapper<FilePhysical>()
                        .eq(FilePhysical::getMd5, md5)
        );
        if (exist == null) {
            throw new BusinessException(0,"物理文件记录创建失败，md5=" + md5);
        }
        return exist.getId();
    }
}
