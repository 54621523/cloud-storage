package demo.cloud.ai.service;


import demo.cloud.ai.pojo.UserContext;
import org.springframework.stereotype.Component;

@Component
public class UserContextService {
    public UserContext getUserContext(String sessionId) {
        return UserContext.empty();
    }
}
