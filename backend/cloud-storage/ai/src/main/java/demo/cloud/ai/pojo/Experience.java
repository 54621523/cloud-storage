package demo.cloud.ai.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Experience {

    String id;

    String name;

    String description;

    String content;

//    List<String> references;
//
//    List<String> assets;
//
//    List<String> associatedTools;


    private LocalDateTime createdAt;


    private LocalDateTime updatedAt;
}
