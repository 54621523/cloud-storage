package demo.cloud.file.dto.upload;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "用于返回前端的上传进度查询返回体")
public class UploadProgress implements Serializable {

    @Schema(description = "会话ID",
            example = "550e8400e29b41d4a716446655440000"
    )
    private String sessionId;

    @Schema(description = "文件名",
            example = "file.ext"
    )
    private String fileName;

    @Schema(description = "上传状态",
        example = "UPLOADING"
    )
    private String status;

    @Schema(description = "已上传分片",
        example = "{0, 1, 2}"
    )
    private int uploadedChunks;

    @Schema(description = "总的分片数量",
        example = "100"
    )
    private int totalChunks;

    @Schema(description = "上传进度",
        example = "0.55"
    )
    private double progress;

    @Schema(description = "物理文件对象键",
            example = "path/to/file"
    )
    private String ossKey;

    public UploadProgress(String sessionId, String fileName, String status,
                          int uploadedChunks, int totalChunks, double progress) {
        this.sessionId = sessionId;
        this.fileName = fileName;
        this.status = status;
        this.uploadedChunks = uploadedChunks;
        this.totalChunks = totalChunks;
        this.progress = progress;
    }
}
