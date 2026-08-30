package demo.cloud.share.dto;

import demo.cloud.file.dto.ItemIdentity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
    @NotNull(message = "目标文件夹不能为空")
    @Min(value = 0, message = "目标文件夹ID无效")
    private Long targetFolderId;

    @Schema(description = "待转存的文件/文件夹标识",
            example = "[{\"id\": 100, \"type\": \"FILE\"}, {\"id\": 200, \"type\": \"FOLDER\"}]"
    )
    @NotEmpty(message = "转存内容不能为空")
    @Valid
    private List<ItemIdentity> items;

    @Schema(description = "源文件夹的顶级文件夹id（访问者视角下的文件列表）",
            example = "1001"
    )
    @NotNull
    @Min(value = 0, message = "根文件夹ID无效")
    private Long rootId;
}
