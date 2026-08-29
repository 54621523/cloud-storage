package demo.cloud.outer.pojo;


import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRequest {

    private String message;

    private String sessionId;

}
