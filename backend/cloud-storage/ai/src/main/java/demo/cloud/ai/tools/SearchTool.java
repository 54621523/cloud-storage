package demo.cloud.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.Hybrid;
import com.meilisearch.sdk.model.Searchable;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@Component
@Slf4j
public class SearchTool implements BiFunction<SearchTool.LLMSearchRequest, ToolContext, List<String>> {

    @Autowired
    private Client meilisearchClient;

    @Autowired
    private ObjectMapper objectMapper;



    @Override
    public List<String> apply(LLMSearchRequest request, ToolContext toolContext) {
        List<Long> docIds = (List<Long>) toolContext.getContext().get("allowed_doc_ids");
        if (docIds == null || docIds.isEmpty()) {
            // 降级：使用默认值（或抛出异常）
            docIds = List.of();
            log.info("allowed_doc_ids not found in ToolContext");
        }
        String filter = "metadata.doc_id IN [" +
                docIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(", ")) +
                "]";
        String s = request.getQuery();
        SearchRequest searchReq = SearchRequest.builder()
                .q(s)
                .filter(new String[]{filter})
                .hybrid(Hybrid.builder()
                        .semanticRatio(0.6)
                        .embedder("default")
                        .build())
                .limit(5)
                .build();
        Searchable search = meilisearchClient.getIndex("document_index").search(searchReq);
        List<Document> documents = convertHits(search.getHits());
        List<String> result = documents.stream()
                .map(Document::getText)
                .toList();
        return result;
    }



    @SuppressWarnings("unchecked")
    protected List<Document> convertHits(ArrayList<HashMap<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> documents = new ArrayList<>();
        for (HashMap<String, Object> hit : hits) {
            String text = (String) hit.get("text");
            if (text == null) continue;
            Map<String, Object> metadata = (Map<String, Object>) hit.get("metadata");
            if (metadata == null) metadata = new HashMap<>();
            // 额外把 id 也放入 metadata
            metadata.put("_meili_id", hit.get("id"));
            documents.add(new Document(text, metadata));
        }
        return documents;
    }

    @Data
    public static class LLMSearchRequest {
        private String query;
    }
}