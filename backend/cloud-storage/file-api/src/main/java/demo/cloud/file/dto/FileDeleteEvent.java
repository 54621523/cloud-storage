package demo.cloud.file.dto;

import demo.cloud.file.constant.FileItemType;
import lombok.Builder;
import lombok.Data;

import java.util.Collection;

@Builder
@Data
public class FileDeleteEvent {
    private Collection<Long> ids;
    private Long userId;
    private FileItemType type;
}
