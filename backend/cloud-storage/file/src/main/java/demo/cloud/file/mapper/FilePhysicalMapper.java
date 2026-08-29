package demo.cloud.file.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.yulichang.base.MPJBaseMapper;
import demo.cloud.file.pojo.FilePhysical;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface FilePhysicalMapper extends BaseMapper<FilePhysical>, MPJBaseMapper<FilePhysical> {

    /**
     * 根据MD5查询物理文件
     */
    @Select("SELECT * FROM file_physical WHERE md5 = #{md5} limit 1")
    FilePhysical selectByMd5(@Param("md5") String md5);

    /**
     * 插入物理文件记录
     */
    int insert(FilePhysical filePhysical);

    /**
     * 增加引用计数
     */
    @Update("UPDATE file_physical SET ref_count = ref_count + 1 WHERE id = #{id}")
    int incrementRefCount(@Param("id") Long id);

    /**
     * 减少引用计数
     */
    @Update("UPDATE file_physical SET ref_count = ref_count - 1 WHERE id = #{id} AND ref_count > 0")
    int decrementRefCount(@Param("id") Long id);

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