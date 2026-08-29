package demo.cloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "服务间用于传递物理文件信息的数据实体")
public class FilePhysicalDTO implements Serializable {

    private static final long serialVersionUID = 1L;


    /**
     * 主键ID
     */
    @Schema(description = "物理文件ID",
            example = "1001"
    )
    private Long id;

    /**
     * OSS 存储路径
     */
    @Schema(description = "物理文件对象键",
        example = "path/to/file"
    )
    private String ossKey;
}