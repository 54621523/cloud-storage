package demo.cloud.file.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidationHelper {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String DIR_CACHE_PREFIX = "dir:members:";

    public void evictDirectoryCache(Long parentId) {
        String key = DIR_CACHE_PREFIX + ":" + parentId;
        redisTemplate.delete(key);
    }
}