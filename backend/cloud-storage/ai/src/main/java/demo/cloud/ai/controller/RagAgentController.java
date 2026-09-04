
package demo.cloud.ai.controller;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import demo.cloud.ai.rag.RAGDocumentProcessor;
import demo.cloud.ai.service.AgentLoopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Optional;

@Slf4j
@Controller
@RequestMapping("/api/rag")
public class RagAgentController {

    private final AgentLoopService service;
    private final RAGDocumentProcessor ragDocumentProcessor;

    public RagAgentController(AgentLoopService service, RAGDocumentProcessor ragDocumentProcessor) {
        this.service = service;
        this.ragDocumentProcessor = ragDocumentProcessor;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }


    @GetMapping("/test")
    @ResponseBody
    public String chatTest(String query) throws GraphRunnerException, GraphStateException {
        Optional<OverAllState> overAllState = service.runAgent("1", query, null, null);
        if(overAllState.isPresent()){
            log.info(overAllState.toString());
            OverAllState state = overAllState.get();
            Optional<Object> messages = state.value("messages");
            List<Message> messageList = (List<Message>) messages.get();
            return messageList.get(messageList.size() -1 ).getText();
        }
        return null;
    }

    @GetMapping("/process-test")
    public void processTest(String bucketName, String objectKey){
        ragDocumentProcessor.processDocumentFromS3(bucketName, objectKey);
    }

}