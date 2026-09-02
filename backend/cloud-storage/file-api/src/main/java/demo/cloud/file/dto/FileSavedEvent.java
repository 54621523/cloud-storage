package demo.cloud.file.dto;

import demo.cloud.file.constant.FileItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileSavedEvent implements Serializable {
    private List<Long> ids;
    private Long userId;
    private FileItemType type;
}