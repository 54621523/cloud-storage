package demo.cloud.share.dto;

import demo.cloud.file.dto.ItemIdentity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransferRequest {

    @Schema(description = "目标文件夹id（访问者视角下的文件列表）",
            example = "1001"
    )
    private Long targetFolderId;

    @Schema(description = "待转存的文件/文件夹标识",
            example = "[{\"id\": 100, \"type\": \"FILE\"}, {\"id\": 200, \"type\": \"FOLDER\"}]"
    )
    private List<ItemIdentity> items;

    @Schema(description = "源文件夹的顶级文件夹id（访问者视角下的文件列表）",
            example = "1001"
    )
    private Long rootId;
}
