package demo.cloud.outer.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 专门用于 MQ 传递的一问一答组合包
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessagePair {
    private ChatMessage userMessage;
    private ChatMessage assistantMessage;
}