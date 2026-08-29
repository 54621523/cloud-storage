package demo.cloud.file.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("folder_tree_path")
public class TreePathNode {


    @TableId(type = IdType.AUTO)
    Long id;

    // 父代Id
    Long ancestorId;

    // 子代Id
    Long descendantId;

    // 递归深度
    Integer depth;


}
