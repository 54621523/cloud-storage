package demo.cloud.file.constant;


import com.baomidou.mybatisplus.annotation.EnumValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "文件/文件夹类型", enumAsRef = true)
public enum FileItemType {

    FILE(1),
    FOLDER(0);

    @EnumValue
    private final int code;

    FileItemType(int code) {
        this.code = code;
    }

    public static FileItemType fromCode(Integer code) {
        if (code == null) return null;
        for (FileItemType type : FileItemType.values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的文件类型code: " + code);
    }
}
