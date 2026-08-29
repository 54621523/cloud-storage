package demo.cloud.share.pojo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import demo.cloud.share.constant.ShareStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShareLink {


    @TableId(type = IdType.AUTO)
    private Long id;

    private String shareCode;

    private Long userId;

    private String password;

    private LocalDateTime expireTime;

    private ShareStatus status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private String displayName;


}
