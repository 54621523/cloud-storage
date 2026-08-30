package demo.cloud.file.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户文件实体
 * 对应表: user_file
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class UserFile implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 父文件夹ID
     */
    private Long parentId;

    /**
     * 用户自定义文件名
     */
    private String name;

    /**
     * 文件大小
     */
    private Long size;

    /**
     * 关联物理文件ID
     */
    private Long physicalId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除时间
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime deletedAt;

    /**
     * 获取文件扩展名
     */
    public String getExtension() {
        if (StringUtils.isBlank(name)) {
            return "";
        }
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0 && lastDot < name.length() - 1) {
            return name.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 判断是否为图片文件
     */
    public boolean isImage() {
        String ext = getExtension();
        return "jpg".equals(ext) || "jpeg".equals(ext) ||
                "png".equals(ext) || "gif".equals(ext) ||
                "bmp".equals(ext) || "webp".equals(ext);
    }

    /**
     * 判断是否为视频文件
     */
    public boolean isVideo() {
        String ext = getExtension();
        return "mp4".equals(ext) || "avi".equals(ext) ||
                "mov".equals(ext) || "wmv".equals(ext) ||
                "flv".equals(ext) || "mkv".equals(ext);
    }

    /**
     * 判断是否为音频文件
     */
    public boolean isAudio() {
        String ext = getExtension();
        return "mp3".equals(ext) || "wav".equals(ext) ||
                "flac".equals(ext) || "aac".equals(ext);
    }

    /**
     * 判断是否为文档文件
     */
    public boolean isDocument() {
        String ext = getExtension();
        return "pdf".equals(ext) || "doc".equals(ext) ||
                "docx".equals(ext) || "xls".equals(ext) ||
                "xlsx".equals(ext) || "ppt".equals(ext) ||
                "pptx".equals(ext) || "txt".equals(ext);
    }
}