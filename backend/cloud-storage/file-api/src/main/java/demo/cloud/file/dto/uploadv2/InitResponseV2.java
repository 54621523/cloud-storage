package demo.cloud.file.dto.uploadv2;


import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
public class InitResponseV2 {

    String uploadId;

    Long chunkSize;

    List<String> presignedUrls;

    Set<Integer> uploadedChunks;

    Boolean isComplete;
}
