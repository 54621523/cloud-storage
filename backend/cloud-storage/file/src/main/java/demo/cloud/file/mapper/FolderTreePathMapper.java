package demo.cloud.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import demo.cloud.file.pojo.TreePathNode;
import org.apache.ibatis.annotations.Mapper;


@Mapper
public interface FolderTreePathMapper extends BaseMapper<TreePathNode> {
}
