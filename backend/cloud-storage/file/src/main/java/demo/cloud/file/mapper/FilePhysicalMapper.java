package demo.cloud.file.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.yulichang.base.MPJBaseMapper;
import demo.cloud.file.pojo.FilePhysical;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface FilePhysicalMapper extends BaseMapper<FilePhysical>, MPJBaseMapper<FilePhysical> {


    int batchIncrementRefCount(@Param("ids") Collection<Long> ids);

    int batchDecrementRefCount(@Param("ids") Collection<Long> ids);

    /**
     * 查询引用计数为0且创建时间超过指定天数的物理文件
     */
    @Select("SELECT * FROM file_physical WHERE ref_count = 0 AND create_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    List<FilePhysical> selectZeroRefFiles(@Param("days") int days);

    /**
     * 物理删除（仅在引用计数为0时）
     */
    @Update("DELETE FROM file_physical WHERE id = #{id} AND ref_count = 0")
    int deleteByIdIfZeroRef(@Param("id") Long id);
}