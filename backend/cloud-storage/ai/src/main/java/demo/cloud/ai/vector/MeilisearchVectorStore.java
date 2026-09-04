package demo.cloud.ai.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.Hybrid;
import com.meilisearch.sdk.model.Searchable;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@Builder
public class MeilisearchVectorStore implements VectorStore {

    private final Client meilisearchClient;
    private final String indexName;
    private final ObjectMapper objectMapper;


    public MeilisearchVectorStore(Client meilisearchClient, String indexName, ObjectMapper objectMapper) {
        this.meilisearchClient = meilisearchClient;
        this.indexName = indexName;
        this.objectMapper = objectMapper;
    }

    @Override
    public void add(List<Document> documents) {

        // 3. 批量索引到 Meilisearch（支持异步或同步）
        Index index = meilisearchClient.index(indexName);
        try {
            String s = objectMapper.writeValueAsString(documents);
            index.addDocuments(s); // 同步，可改为异步
        }
         catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void delete(List<String> idList) {
        Index index = meilisearchClient.index(indexName);
        index.deleteDocuments(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
//        // 将 Spring AI 的 Filter.Expression 转换为 Meilisearch 的过滤语法
//        String filterString = convertFilterToMeilisearch(filterExpression);
//        Index index = meilisearchClient.index(indexName);
//        // Meilisearch 的删除不支持过滤表达式，但可以结合 search + 删除
//        // 或者利用 Meilisearch 的 filter 参数先查出要删除的 ID，再批量删除
//        // 简单起见，可以通过搜索获取匹配的文档 ID
//        SearchResult searchResult = index.search("", new com.meilisearch.sdk.model.SearchRequest()
//                .setFilter(filterString)
//                .setLimit(1000) // 一次最多1000，可分批
//        );
//        List<String> ids = searchResult.getHits().stream()
//                .map(hit -> (String) hit.get("id"))
//                .collect(Collectors.toList());
//        if (!ids.isEmpty()) {
//            index.deleteDocuments(ids);
//        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        // 1. 构造 Meilisearch 的向量搜索请求

        Hybrid hybrid = Hybrid.builder()
                .embedder("default")
                .semanticRatio(0.5)
                .build();
        com.meilisearch.sdk.SearchRequest searchRequest = com.meilisearch.sdk.SearchRequest.builder()
                .q(request.getQuery())
//                .vector()
                .rankingScoreThreshold(request.getSimilarityThreshold())
                .showRankingScore(true)
                .limit(request.getTopK())
                .hybrid(hybrid)
                .build();

        // 3. 执行搜索
        Index index = meilisearchClient.index(indexName);
        Searchable result = index.search(searchRequest); // 向量搜索时 query 可为空

        // 4. 转换结果为 Spring AI Document 列表
        return result.getHits().stream()
                .map(hit -> {
                    Document doc = new Document(
                            (String) hit.get("id"),
                            (String) hit.get("content"),
                            (Map<String, Object>) hit.get("metadata")
                    );
                    return doc;
                })
                .collect(Collectors.toList());
    }
}