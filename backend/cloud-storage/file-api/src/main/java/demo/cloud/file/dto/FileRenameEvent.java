package demo.cloud.file.dto;

import demo.cloud.file.constant.FileItemType;
import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class FileRenameEvent {
    private Long id;
    private Long userId;
    private FileItemType type;
    private String newName;
}
