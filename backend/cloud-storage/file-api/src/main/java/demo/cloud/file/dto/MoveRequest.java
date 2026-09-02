package demo.cloud.file.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "移动文件/文件夹的请求体")
public class MoveRequest {

    @Schema(description = "待删除的文件/文件夹标识列表（支持混合删除）",
            example = "[{\"id\": 100, \"type\": \"FILE\"}, {\"id\": 200, \"type\": \"FOLDER\"}]"
    )
    List<ItemIdentity> items;

    @Schema(description = "目标父目录Id",
        example = "1001"
    )
    Long targetParentId;

    @Schema(description = "目标父目录Id",
    example = "2002")
    Long sourceParentId;
}
