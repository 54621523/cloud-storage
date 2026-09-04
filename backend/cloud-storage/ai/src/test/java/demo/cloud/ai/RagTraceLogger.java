package demo.cloud.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class RagTraceLogger {
    private static final Logger log = LoggerFactory.getLogger("RAG_TRACE");

    public static void trace(Map<String, Object> record) {
        log.info("{}", record);  // 或直接 log.info(JsonUtils.toJson(record))
    }
}