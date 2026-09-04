package demo.cloud.file.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

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
    }
}