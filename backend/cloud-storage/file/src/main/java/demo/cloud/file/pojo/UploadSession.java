package demo.cloud.file.pojo;

import lombok.*;

import java.io.Serializable;


@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UploadSession implements Serializable {

    private String sessionId;
    private String status;  // INIT, UPLOADING, COMPLETED, FAILED, CANCELLED
    private Long userId;

    private String fileName;
    private long fileSize;
    private String md5;
    private String ossKey;

    private int totalChunks;
    private Long parentId;
    private String relativePath;
    private Boolean isFolderUpload;

    private String presignedUrl;

}