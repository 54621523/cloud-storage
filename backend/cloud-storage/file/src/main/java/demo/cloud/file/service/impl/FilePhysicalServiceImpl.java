package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.file.mapper.FilePhysicalMapper;
import demo.cloud.file.pojo.FilePhysical;
import demo.cloud.file.pojo.UploadSession;
import demo.cloud.file.service.FilePhysicalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FilePhysicalServiceImpl extends ServiceImpl<FilePhysicalMapper, FilePhysical> implements FilePhysicalService {


    private final FilePhysicalMapper filePhysicalMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String NULL_PLACEHOLDER = "NULL";
    private final ObjectMapper objectMapper;
    private final String CACHE_KEY_PREFIX = "file:physical:md5";
    private final long CACHE_TTL = 24;  //24小时

    public FilePhysicalServiceImpl(FilePhysicalMapper filePhysicalMapper, RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.filePhysicalMapper = filePhysicalMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public FilePhysical getByMd5(String md5) {
        if(StringUtils.isBlank(md5)){
            return null;
        }

        // 1. 先从缓存获取
        String cacheKey = CACHE_KEY_PREFIX + md5;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return objectMapper.convertValue(cached, new TypeReference<FilePhysical>() {});
        }

        // 缓存未命中
        // 2. 从数据库查询
        try {
            FilePhysical filePhysical = filePhysicalMapper.selectOne(
                    new LambdaQueryWrapper<FilePhysical>()
                            .eq(FilePhysical::getMd5, md5)
                            .last("LIMIT 1")
            );
            return filePhysical;

            //TODO 写入缓存或者使用Spring cache
        }catch (Exception e){
            log.error("查询已存在文件Md5失败,Md5: {}",md5,e);
            return null;
        }
    }

    /**
     * @param id
     */
    @Override
    public void increaseRef(Long id) {
        filePhysicalMapper.incrementRefCount(id);
    }

    public FilePhysical processPhysicalFile(UploadSession session, String md5) {
        // 创建新的物理文件记录
        FilePhysical physical = new FilePhysical();
        physical.setMd5(md5);
        physical.setSize(session.getFileSize());
        physical.setOssKey(session.getOssKey());
        physical.setRefCount(1);
        filePhysicalMapper.insert(physical);
        log.info("[创建物理文件] MD5: {}, PhysicalId: {}", md5, physical.getId());
        return physical;
    }
}
