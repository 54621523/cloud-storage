package demo.cloud.file.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;


@Data
public class UserFolder {


    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long parentId;

    private String name;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除时间
     */
    private LocalDateTime deletedAt;

}
