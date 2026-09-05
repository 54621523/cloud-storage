package demo.cloud.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.Embedder;
import com.meilisearch.sdk.model.EmbedderSource;
import com.meilisearch.sdk.model.Searchable;
import demo.cloud.ai.pojo.Experience;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
public class ExperienceStore {

    private final Client meilisearchClient;
    private final String indexName;
    private final ObjectMapper objectMapper;


    public ExperienceStore(Client meilisearchClient, String indexName, ObjectMapper objectMapper) {
        this.meilisearchClient = meilisearchClient;
        this.indexName = indexName;
        this.objectMapper = objectMapper;
    }

    public void Init(){
        Index index = meilisearchClient.getIndex(indexName);
        if(index == null){
            meilisearchClient.createIndex(indexName, "id");
            index = meilisearchClient.getIndex(indexName);
        }
        Embedder embedder = new Embedder();
        embedder.setSource(EmbedderSource.REST);
        embedder.setUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings");
        embedder.setApiKey("key");


    }





    public void add(List<Experience> experiences) {

        // 3. 批量索引到 Meilisearch（支持异步或同步）
        Index index = meilisearchClient.index(indexName);
        try {
            String s = objectMapper.writeValueAsString(experiences);
            index.addDocuments(s); // 同步，可改为异步
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }



    public List<Experience> search(SearchRequest request) {
        com.meilisearch.sdk.SearchRequest searchRequest = com.meilisearch.sdk.SearchRequest.builder()
                .q(request.getQuery())
                .rankingScoreThreshold(request.getSimilarityThreshold())
                .limit(request.getTopK())
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
                })
                .collect(Collectors.toList());
    }

    public List<Experience> listAllSummaries() {
        // 使用空查询，只返回摘要字段
        com.meilisearch.sdk.SearchRequest searchRequest = com.meilisearch.sdk.SearchRequest.builder()
                .q("") // 空查询返回所有文档
                .attributesToRetrieve(new String[]{"id", "name", "description", "type"})
                .limit(1000) // 根据实际情况调整
                .build();
        Index index = meilisearchClient.index(indexName);
        Searchable result = index.search(searchRequest);
        return result.getHits().stream()
                .map(hit -> Experience.builder()
                        .id((String) hit.get("id"))
                        .name((String) hit.get("name"))
                        .description((String) hit.get("description"))
                        .build())
                .collect(Collectors.toList());
    }
}