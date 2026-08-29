
package demo.cloud.outer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.yulichang.base.MPJBaseMapper;
import demo.cloud.outer.pojo.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends MPJBaseMapper<ChatSession>,BaseMapper<ChatSession> {

}