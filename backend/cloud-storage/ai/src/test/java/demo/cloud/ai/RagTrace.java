package demo.cloud.ai;

import lombok.Data;

import java.util.List;

@Data
public class RagTrace {
    private String query;
    private List<String> contexts;
    private String answer;
    private String ground_truth;

}