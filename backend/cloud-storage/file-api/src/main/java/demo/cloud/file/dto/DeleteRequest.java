package demo.cloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;


@Data
@Schema(description = "批量删除请求体")
public class DeleteRequest {

    @Schema(description = "待删除的文件/文件夹标识列表（支持混合删除）",
            example = "[{\"id\": 100, \"type\": \"FILE\"}, {\"id\": 200, \"type\": \"FOLDER\"}]"
    )
    private List<ItemIdentity> items;
}
