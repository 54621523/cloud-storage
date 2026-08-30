package demo.cloud.file.dto;

import demo.cloud.file.constant.FileItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileSavedEvent implements Serializable {
    private Long userFileId;      // 用户文件记录ID
    private String fileName;      // 用户文件名
    private Long userId;
    private FileItemType type;
}