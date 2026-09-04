package demo.cloud.ai.service;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import demo.cloud.ai.nodes.IntentCheckNode;
import demo.cloud.ai.nodes.PreprocessNode;
import demo.cloud.ai.tools.SearchTool;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Service
@Slf4j
public class AgentLoopService {
    private static final int MAX_TURNS = 5;
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(30);

    @Qualifier("dashScopeChatModel")
    @Autowired
    private ChatModel chatModel;

    @Autowired
    private IntentCacheService cacheService;

    @Autowired
    private IntentCheckNode intentCheckNode;

    @Autowired
    private SearchTool searchTool;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private PreprocessNode preprocessNode;


    public Optional<OverAllState> runAgent(String sessionId, String query, List<String> docIds, Float ratio) throws GraphRunnerException, GraphStateException {
        ToolCallback toolCallback = FunctionToolCallback
                .builder("document_search", searchTool)
                .description("Get information from database")
                .inputType(SearchTool.LLMSearchRequest.class)
                .build();

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .addMetadata("allowed_doc_ids",List.of(0L,1L,2L,3L,4L,5L,6L,7L,8L,9L,10L,11L,12L,13L,14L))
                .build();

        //
        RedisSaver redisSaver = RedisSaver.builder().redisson(redissonClient).build();



        // 构建 ReactAgent，instruction 中使用占位符
        ReactAgent simpleRAGAgent = ReactAgent.builder()
                .name("rag_agent")
                .model(chatModel)
                .saver(redisSaver)
                .outputKey("answer")
                .hooks(ModelCallLimitHook.builder().runLimit(6).build())
                .instruction("""
你是一个智能助手，可以访问多个信息源来回答问题。
使用工具时：
1. 使用 document_search 搜索文档库, 你最多有五次机会
2. 基于检索到的信息生成准确、完整的答案
3. 如果信息不足，可以多次调用工具
4. 如果你有足够信心回答时，直接输出最终答案，不用再调用工具

当前用户背景信息：
{user_context}

对话指代消解后的查询：
{resolved_query}

原始用户问题（仅供参考）：
{query}

""")
                .tools(toolCallback)
                .build();
        StateGraph graph = new StateGraph()
                //查询重写 用户画像提取
                .addNode("preprocess", node_async(preprocessNode))
                // 意图短路
                .addNode("intentCheck", node_async(intentCheckNode))
                .addNode(simpleRAGAgent.name(), simpleRAGAgent.asNode(
                        true,
                        false
                ))
                .addEdge(StateGraph.START, "intentCheck")
                .addEdge("intentCheck", "preprocess")
                .addConditionalEdges("intentCheck",
                        edge_async(state -> state.value("_short_", "NO")),
                        Map.of(
                                "YES", StateGraph.END,
                                "NO", simpleRAGAgent.name()
                        )
                )
                .addEdge(simpleRAGAgent.name(), StateGraph.END);
        var memory = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder()
                        .register(memory)
                        .build())
                .build();
        CompiledGraph compile = graph.compile(compileConfig);


        Optional<OverAllState> invoke = compile.invoke(Map.of(
                "query", query,
                "allowed_doc_ids",List.of(0L,1L,2L,3L,4L,5L,6L,7L,8L,9L,10L,11L,12L,13L,14L)
        ),config);
        return invoke;
    }
}