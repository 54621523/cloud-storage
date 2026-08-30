package demo.cloud.file.dto;


import demo.cloud.file.constant.FileItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "简易对象实体")
public class ItemIdentity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "文件/文件夹ID",
        example = "1001"
    )
    @NotNull(message = "文件/文件夹ID不能为空")
    @Min(value = 1, message = "ID必须大于0")
    private Long id;


    @Schema(description = "文件类型（文件/文件夹）",
        example = "FILE"
    )
    @NotNull(message = "类型不能为空")
    private FileItemType type;
}
