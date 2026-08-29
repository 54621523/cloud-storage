package demo.cloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "批量还原请求体")
public class RestoreRequest {

    @Schema(description = "待还原的文件/文件夹标识列表",
            example = "[{\"id\": 100, \"type\": \"FILE\"}, {\"id\": 200, \"type\": \"FOLDER\"}]"
    )
    private List<ItemIdentity> items;

}
