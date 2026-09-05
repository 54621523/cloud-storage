package demo.cloud.ai.pojo;

import demo.cloud.ai.anno.MSFiled;
import demo.cloud.ai.anno.MSIndex;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@MSIndex(uid = "experience_index", primaryKey = "id")
public class Experience  {

    String id;

    @MSFiled(openSearch = true)
    String name;

    @MSFiled(openSearch = true)
    String description;

    String content;

    @MSFiled(openFilter = true)
    String namespace;

//    List<String> references;
//
//    List<String> assets;
//
//    List<String> associatedTools;


    private LocalDateTime createdAt;


    private LocalDateTime updatedAt;
}
