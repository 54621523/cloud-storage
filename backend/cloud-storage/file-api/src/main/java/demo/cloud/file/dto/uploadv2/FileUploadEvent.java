package demo.cloud.file.dto.uploadv2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadEvent implements Serializable {
    private Long physicalId;      // 用户文件记录ID
    private String ossKey;        // OSS存储路径
    private String bucket;        // Bucket名称
    private String fileName;      // 原始文件名
    private Long userId;
    private String uploadId;      // 分片上传ID（备用）
}