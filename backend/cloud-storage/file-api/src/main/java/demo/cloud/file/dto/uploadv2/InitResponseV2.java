package demo.cloud.file.dto.uploadv2;


import lombok.*;

import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class InitResponseV2 {

    String uploadId;

    Long chunkSize;

    List<String> presignedUrls;

    Set<Integer> uploadedChunks;

    Boolean isComplete;
}
