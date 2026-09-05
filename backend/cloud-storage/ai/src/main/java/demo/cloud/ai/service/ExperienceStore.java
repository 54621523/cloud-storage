package demo.cloud.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchApiException;
import com.meilisearch.sdk.model.Searchable;
import demo.cloud.ai.anno.MeiliIndexConfig;
import demo.cloud.ai.anno.MeiliSearchConfigHelper;
import demo.cloud.ai.pojo.Experience;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Data
@Component
public class ExperienceStore {

    private final Client meilisearchClient;
    private String indexName;
    private final ObjectMapper objectMapper;
    private String primaryKey;


    public ExperienceStore(Client meilisearchClient, ObjectMapper objectMapper) {
        MeiliIndexConfig config = MeiliSearchConfigHelper.extractConfig(Experience.class);
        this.meilisearchClient = meilisearchClient;
        this.indexName = config.getUid();
        this.primaryKey = config.getPrimaryKey();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void Init() {
        try {
            MeiliIndexConfig config = MeiliSearchConfigHelper.extractConfig(Experience.class);
            meilisearchClient.createIndex(config.getUid(), config.getPrimaryKey());
            // 尝试获取索引，若存在则直接使用
            Index index = meilisearchClient.getIndex(indexName);
            // 索引存在，更新设置（可选）
            updateIndexSettings(index);
        } catch (MeilisearchApiException e) {
            // 如果是索引不存在 (HTTP 404)
            if (e.getCode().equals("404")) {
                meilisearchClient.createIndex(indexName, "id");
                // 创建后获取索引
                Index index = meilisearchClient.getIndex(indexName);
                // 更新设置
                updateIndexSettings(index);
            } else {
                // 其他异常直接抛出
                throw new RuntimeException("Failed to initialize Meilisearch index", e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Meilisearch index", e);
        }
    }

    private void updateIndexSettings(Index index) {
        MeiliIndexConfig config = MeiliSearchConfigHelper.extractConfig(Experience.class);
        index.updateFilterableAttributesSettings(config.getFilterableAttributes());
        index.updateSearchableAttributesSettings(config.getSearchableAttributes());
    }





    public void add(List<Experience> experiences) {

        Index index = meilisearchClient.index(indexName);
        try {
            String s = objectMapper.writeValueAsString(experiences);
            index.addDocuments(s);
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }



    public Experience search(String name) {
        com.meilisearch.sdk.SearchRequest searchRequest = com.meilisearch.sdk.SearchRequest.builder()
                .filter(new String[]{name})
                .limit(1)
                .build();

        // 3. 执行搜索
        Index index = meilisearchClient.index(indexName);
        Searchable result = index.search(searchRequest); // 向量搜索时 query 可为空

        // 4. 转换结果为 Expericen 列表
        return result.getHits().stream()
                .map(hit -> {
                    Experience exp = new Experience();
                    exp.setId((String)hit.get("id"));
                    exp.setName((String)hit.get("name"));
                    exp.setDescription((String) hit.get("description"));
                    exp.setContent((String) hit.get("content"));
                    return exp;
                }).toList().get(0);
    }

    public List<Experience> listAllSummaries() {
        // 使用空查询，只返回摘要字段
        com.meilisearch.sdk.SearchRequest searchRequest = com.meilisearch.sdk.SearchRequest.builder()
                .q("") // 空查询返回所有文档
                .attributesToRetrieve(new String[]{"id", "name", "description", "type"})
                .limit(1000)
                .build();
        Index index = meilisearchClient.index(indexName);
        Searchable result = index.search(searchRequest);
        return result.getHits().stream()
                .map(hit -> Experience.builder()
                        .id((String) hit.get("id"))
                        .name((String) hit.get("name"))
                        .description((String) hit.get("description"))
                        .content((String) hit.get("content"))
                        .build())
                .collect(Collectors.toList());
    }
}