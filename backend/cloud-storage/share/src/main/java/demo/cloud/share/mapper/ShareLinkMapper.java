package demo.cloud.share.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import demo.cloud.share.dto.ShareLinkVO;
import demo.cloud.share.pojo.ShareLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;


@Mapper
public interface ShareLinkMapper extends BaseMapper<ShareLink> {
    List<ShareLinkVO> selectDTOList(IPage<ShareLinkVO> page, Long userId, @Param("ew") Wrapper<ShareLinkVO> wrapper);



    IPage<ShareLinkVO> selectShareLinkVO(Page<?> page, @Param("userId") Long userId);
}
