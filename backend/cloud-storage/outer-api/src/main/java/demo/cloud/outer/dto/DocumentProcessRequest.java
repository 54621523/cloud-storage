package demo.cloud.outer.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "RAG处理请求体")
public class DocumentProcessRequest {

    @Schema(description = "用户文件ID",
            example = "1001"
    )
    private Long id;
}
