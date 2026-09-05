package demo.cloud.ai.tools;

import demo.cloud.ai.nodes.PreprocessNode;
import demo.cloud.ai.pojo.Experience;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ExperienceTool {

    @Autowired
    private PreprocessNode preprocessNode;



    public String readExperience(String name) {
        Experience exp = preprocessNode.experienceCache.get(name);
        return exp != null ? exp.getContent() : "未找到该经验";
    }
}
