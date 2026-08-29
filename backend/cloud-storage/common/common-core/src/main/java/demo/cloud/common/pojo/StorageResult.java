package demo.cloud.common.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StorageResult {
    private String bucketName;      // 桶名
    private String objectName;      // 目标对象名（物理路径）
    private Long fileSize;          // 真实的物理大小
}