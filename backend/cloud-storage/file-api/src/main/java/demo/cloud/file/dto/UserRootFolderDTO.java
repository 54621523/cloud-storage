package demo.cloud.file.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@Schema(description = "服务间传输用户根目录信息的数据实体")
public class UserRootFolderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "根目录ID",
        example = "1001"
    )
    Long rootFolderID;

    @Schema(description = "根目录名称",
        example = "root"
    )
    String rootFolderName;

}
