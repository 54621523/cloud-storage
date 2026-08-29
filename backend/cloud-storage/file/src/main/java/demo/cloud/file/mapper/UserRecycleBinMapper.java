package demo.cloud.file.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import demo.cloud.file.dto.RecycleFileVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserRecycleBinMapper {

    /**
     * 分页查询回收站（同时包含文件和文件夹）
     * @param page 分页对象（MyBatis-Plus 分页参数）
     * @param userId 用户ID
     * @return 分页结果，数据为 RecycleFileVO
     */
    IPage<RecycleFileVO> selectRecycleBin(IPage<?> page, @Param("userId") Long userId);
}