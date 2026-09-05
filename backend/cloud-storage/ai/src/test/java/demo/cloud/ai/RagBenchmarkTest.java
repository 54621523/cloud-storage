package demo.cloud.ai;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.Hybrid;
import com.meilisearch.sdk.model.Searchable;
import demo.cloud.ai.service.AgentLoopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
public class RagBenchmarkTest {

    private static final Logger traceLogger = LoggerFactory.getLogger("RAG_TRACE");

    @Autowired
    private Client meilisearchClient;

    private ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private AgentLoopService agentService;

    private List<String> allChunkTexts;   // 全库所有块

    @BeforeEach
    void setup() {
        allChunkTexts = TestData.createTestDocuments();
    }

    // ===== 测试1：检索质量评估 =====
    @Test
    void testRetrievalQuality() {
        List<TestData.TestQuery> queries = TestData.createTestQueries();
        Map<String, List<Map<String, Object>>> results = new HashMap<>();

        for (var q : queries) {
            // 假设返回 List<String>（chunk文本列表）
            List<String> retrieved = callSearchEndpoint(q.query());
            // 评估
            Map<String, Object> metrics = RetrievalEvaluator.evaluate(
                    retrieved, allChunkTexts, q.goldFacts()
            );
            results.computeIfAbsent(q.difficulty(), k -> new ArrayList<>())
                    .add(metrics);
        }

        // 输出汇总统计
        for (var entry : results.entrySet()) {
            String diff = entry.getKey();
            var list = entry.getValue();
            double avgP = list.stream().mapToDouble(m -> (double)m.get("p@5")).average().orElse(0);
            double avgR = list.stream().mapToDouble(m -> (double)m.get("r@5")).average().orElse(0);
            double avgMrr = list.stream().mapToDouble(m -> (double)m.get("mrr")).average().orElse(0);
            traceLogger.info("{} avg P@5={}, R@5={}, MRR={}", diff, avgP, avgR, avgMrr);
        }
    }

    // ===== 测试2：答案正确性 =====
    @Test
    void testEndToEndPerformance() throws GraphRunnerException, JsonProcessingException, GraphStateException {
        List<TestData.TestQuery> queries = TestData.createTestQueries();

        for (var q : queries) {
            String answer;
            List<String> contexts = new ArrayList<>();
            Optional<OverAllState> overAllState = agentService.runAgent("1", q.query(), null, null);
            if(overAllState.isPresent()){
                OverAllState state = overAllState.get();
                Optional<Object> messages = state.value("messages");
                List<Message> messageList = (List<Message>) messages.get();
                for (Message message: messageList){
                    if(message.getMessageType()== MessageType.TOOL){
                        ToolResponseMessage toolMsg = (ToolResponseMessage) message;
                        List<ToolResponseMessage.ToolResponse> responses = toolMsg.getResponses();
                        // 提取 responseData
                        for (var resp : responses) {
                            String data = resp.responseData();  // 这是 JSON 数组字符串
                            // 反序列化为 List<String>
                            List<String> docs = objectMapper.readValue(data, new TypeReference<>() {});
                            contexts.addAll(docs);
                        }
                    }
                }
                answer = messageList.get(messageList.size() - 1).getText();
                RagTrace ragTrace = new RagTrace();
                ragTrace.setContexts(contexts);
                ragTrace.setAnswer(answer);
                ragTrace.setGround_truth(q.expectedAnswer());
                ragTrace.setQuery(q.query());
                traceLogger.info("{}", ragTrace);
            }
        }
    }

    // 辅助：调用检索接口（具体实现根据你的API调整）
    private List<String> callSearchEndpoint(String query) {
        List<Long> docIds = List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L);
        String filter = "metadata.doc_id IN [" +
                docIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(", ")) +
                "]";
        String s = query;
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
}