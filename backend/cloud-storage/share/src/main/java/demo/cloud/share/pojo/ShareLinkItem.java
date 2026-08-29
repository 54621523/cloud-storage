package demo.cloud.share.pojo;


import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import demo.cloud.file.constant.FileItemType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShareLinkItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shareId;

    private Long targetId;

    private FileItemType targetType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
