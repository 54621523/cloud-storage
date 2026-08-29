package demo.cloud.outer.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Builder
@ToString
public class DocumentMetadata {
    @JsonProperty("doc_id")
    @Builder.Default
    private Long docId = null;  // 默认 null

    @JsonAnyGetter
    private Map<String, Object> dynamicFields = new HashMap<>();

    @JsonAnySetter
    public void putDynamicField(String key, Object value) {
        dynamicFields.put(key, value);
    }

    // 提供一个方便的方法批量添加用户输入
    public void putAllUserFields(Map<String, Object> userInput) {
        if (userInput != null) {
            this.dynamicFields.putAll(userInput);
        }
    }
}