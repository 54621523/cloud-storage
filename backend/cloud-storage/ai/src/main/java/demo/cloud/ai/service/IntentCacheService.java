package demo.cloud.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class IntentCacheService {

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EmbeddingModel embeddingModel;

    private static final double SIMILARITY_THRESHOLD = 0.90;
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    // 获取所有意图 key（生产环境可用 SCAN 替代 KEYS）
    private List<String> getAllIntentKeys() {
        return redissonClient.getKeys().getKeysStreamByPattern("intent:*")
                .collect(Collectors.toList());
    }

    public Optional<String> getCachedAnswer(String query) {
        float[] queryEmbedding = embeddingModel.embed(query);
        List<String> keys = getAllIntentKeys();
        String bestKey = null;
        double bestScore = -1;
        for (String key : keys) {
            RMap<String, String> map = redissonClient.getMap(key);
            String storedEmbeddingJson = map.get("embedding");
            if (storedEmbeddingJson == null) continue;
            float[] storedEmbedding = parseEmbedding(storedEmbeddingJson);
            double score = cosineSimilarity(queryEmbedding, storedEmbedding);
            if (score > bestScore) {
                bestScore = score;
                bestKey = key;
            }
        }
        if (bestScore >= SIMILARITY_THRESHOLD && bestKey != null) {
            String answer = redissonClient.<String, String>getMap(bestKey).get("answer");
            log.info("Cache hit for query: {}, score: {}", query, bestScore);
            return Optional.ofNullable(answer);
        }
        return Optional.empty();
    }

    public void cacheAnswer(String query, String answer) throws JsonProcessingException {
        // 只有当 query 和 answer 非空且长度符合时写入
        if (query == null || answer == null || answer.length() < 20) return;
        float[] embedding = embeddingModel.embed(query);
        String id = UUID.randomUUID().toString();
        String key = "intent:" + id;
        RMap<String, String> map = redissonClient.getMap(key);
        map.put("query", query);
        map.put("embedding", objectMapper.writeValueAsString(embedding));
        map.put("answer", answer);
        map.put("createdAt", String.valueOf(System.currentTimeMillis()));
        map.expire(DEFAULT_TTL);
        log.info("Cached new intent: {}", id);
    }

    // 余弦相似度计算
    private double cosineSimilarity(float[] a, float[] b) {
        return 0.1;
    }

    private float[] parseEmbedding(String json) {
        // 使用 Jackson 反序列化
        float[] floats = objectMapper.convertValue(json, new TypeReference<float[]>() {
        });
        return floats;

    }
}