package demo.cloud.file.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CacheInvalidationHelper {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DIR_CACHE_PREFIX = "dir:members:";

    public void evictDirectoryCache(Long parentId, Long userId) {

        String key = DIR_CACHE_PREFIX + userId + ":" + parentId;
        log.info("{} 缓存失效", key);
        redisTemplate.delete(key);
        log.info("断点");
    }

    public void evictUserCache(Long parentId, Long userId){

    }

    public void evictDirectoryCacheBatch(Collection<Long> parentIds, Long userId) {
        if (parentIds == null || parentIds.isEmpty()) return;
        List<String> keys = parentIds.stream()
                .map(pid -> DIR_CACHE_PREFIX + userId + ":" + pid)
                .collect(Collectors.toList());
        redisTemplate.unlink(keys);
        log.info("批量失效目录缓存，数量：{}", keys.size());
    }
}