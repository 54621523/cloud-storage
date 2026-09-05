package demo.cloud.ai.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import demo.cloud.ai.pojo.Experience;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.LocalDateTime;
import java.util.*;


@Slf4j
public class ExperienceLearningService {

    private final ObjectMapper objectMapper;

    private final ChatModel chatModel;
    private final ExperienceStore experienceStore;

    public ExperienceLearningService(ObjectMapper objectMapper, ChatModel chatModel, ExperienceStore experienceStore) {
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
        this.experienceStore = experienceStore;
    }

    private List<Experience> parseAndSaveExperiences(String jsonOutput) {
        if (jsonOutput == null || jsonOutput.trim().isEmpty()) {
            log.warn("LLM返回为空，无经验可提取");
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(jsonOutput);
            if (!root.isArray()) {
                log.error("JSON根节点不是数组，实际类型：{}", root.getNodeType());
                return Collections.emptyList();
            }

            List<Experience> savedExperiences = new ArrayList<>();
            for (JsonNode node : root) {
                // 提取字段（字段缺失时提供默认值）
                String name = node.path("name").asText("未命名经验");
                String description = node.path("description").asText("");
                String content = node.path("content").asText("");

                // 构建Experience对象
                Experience exp = new Experience();
                exp.setId(UUID.randomUUID().toString());   // 生成唯一ID
                exp.setName(name);
                exp.setDescription(description);
                exp.setContent(content);
                exp.setCreatedAt(LocalDateTime.now());
                exp.setUpdatedAt(LocalDateTime.now());
                savedExperiences.add(exp);
            }
            experienceStore.add(savedExperiences);

            log.info("成功提取并保存 {} 条经验", savedExperiences.size());
            return savedExperiences;

        } catch (Exception e) {
            log.error("解析或保存经验失败，原始JSON：{}", jsonOutput, e);
            // 根据业务需要可选择抛出RuntimeException或返回空列表
            return Collections.emptyList();
        }
    }


    /**
     * LLM提取经验
     */
    private List<Experience> llmExtractExperiences(OverAllState state) {
        //TODO: 记录任务成功/不成功
        //      成功记录提取，不成功短路
        String systemPrompt = """
				你是智能学习系统的提取器。从Agent执行中提取可复用经验。
				
				提取类型：
				1. COMMON：需求理解、通用知识、解决思路、最佳实践、安全边界
				2. REACT：多步处理策略、决策流程、任务编排方法
				3. TOOL：单个工具的使用前提、调用方式、适用边界、常见注意事项
				4. User: 用户背景知识，核心共识
				
				JSON格式输出：
				```json
				[
				  {
				    "type": "COMMON|REACT|TOOL",
				    "name": "简短标题（10字内）",
				    "description": "核心要点（50字内）",
				    "content": "详细内容（200字内，重点可复用性）",
				  }
				]
				```
				
				要求：
				- 只提取有价值、可复用的经验
				- 内容简洁、结构化，避免冗长
				- 不要包含具体对话或执行细节
				- 提炼通用模式和方法
				- 无经验返回 []
				""";

        String userPrompt = buildContextSummary(state);

        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ));

            ChatResponse response = chatModel.call(prompt);
            String jsonOutput = response.getResult().getOutput().getText().trim();

            return null;

        } catch (Exception e) {
            log.error("提取经验出错", e);
            throw e;
        }
    }

    /**
     * 构建上下文摘要
     */
    private String buildContextSummary(OverAllState state) {
        StringBuilder summary = new StringBuilder();

        // 用户输入
        state.value("query", String.class).ifPresent(input ->
                summary.append("用户输入: ").append(truncate(input, 200)).append("\n\n")
        );

        Optional<Object> messagesOpt = state.value("messages");
        List<Message> messages;
        messages = messagesOpt.map(object -> (List<Message>) object).orElseGet(List::of);
        extractRecentHistory(messages, 1);

        return summary.toString();
    }


    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private List<String> extractRecentHistory(List<Message> messages, int maxTurns) {
        // 从最新开始倒序提取，最多 maxTurns 轮（每轮可能包含 user + assistant）
        List<String> historyParts = new ArrayList<>();
        List<String> toolParts = new ArrayList<>();
        int count = 0;

        for (int i = messages.size() - 1; i >= 0 && count < maxTurns * 2; i--) {
            Message msg = messages.get(i);
            String content = msg.getText();

            if (msg instanceof ToolResponseMessage) {
                toolParts.add(content);
            } else if (msg.getMessageType() == MessageType.USER ||
                    msg.getMessageType() == MessageType.ASSISTANT) {
                historyParts.add(content); // 逆序收集
                if (msg.getMessageType() == MessageType.USER) count++;
            }
        }

        Collections.reverse(historyParts); // 翻转为正序
        Collections.reverse(toolParts);

        String historyStr = String.join("\n", historyParts);
        String toolStr = String.join("\n", toolParts);
        return Collections.singletonList(historyStr);
    }
}
