package demo.cloud.file.dto.uploadv2;


import lombok.Data;

import java.util.List;

@Data
public class MergeRequestV2 {

    private String uploadId;
    private List<PartInfo> parts;
}
