package demo.cloud.ai.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import demo.cloud.ai.service.IntentCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class IntentCheckNode implements NodeAction {

    @Autowired
    private IntentCacheService cacheService;

    /**
     * @param state
     * @return
     * @throws Exception
     */
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String query = state.value("query").map(v -> (String)v).orElse("");
//        Optional<String> cached = cacheService.getCachedAnswer(query);
//        if (cached.isPresent()) {
//            // 直接短路，设置最终答案
//            return Map.of(
//                    "answer", cached,
//                    "_short_", "YES"
//            );
//        }
        log.info("用户原始输入为 {}", query);
        return Map.of(
                "_short_", "NO"
        );
    }
}