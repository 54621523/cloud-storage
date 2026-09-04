package demo.cloud.ai.pojo;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class UserContext {
    private Map<String, Object> profile;      // 用户画像
    private Map<String, Object> preferences;  // 偏好
    private Map<String, Object> sceneMemory;  // 场景记忆

    public static UserContext empty() {
        return builder()
                .profile(Map.of())
                .preferences(Map.of())
                .sceneMemory(Map.of())
                .build();
    }

    public boolean isNotEmpty() {
        return !profile.isEmpty() || !preferences.isEmpty() || !sceneMemory.isEmpty();
    }

    @Override
    public String toString() {
        String profileStr = (profile == null || profile.isEmpty()) ? "无" : profile.toString();
        String prefStr = (preferences == null || preferences.isEmpty()) ? "无" : preferences.toString();
        String memoryStr = (sceneMemory == null || sceneMemory.isEmpty()) ? "无" : sceneMemory.toString();
        return String.format("用户画像：%s\n偏好：%s\n场景记忆：%s",
                profileStr, prefStr, memoryStr);
    }
}