package demo.cloud.file.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.pojo.UserFolder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserFolderMapper extends BaseMapper<UserFolder> {

    /**
     * 并发安全地创建文件夹（如果存在则忽略）
     * 利用 MySQL 的 ON DUPLICATE KEY UPDATE 语法
     */
    int insertOrUpdateIgnore(@Param("userId") Long userId,
                             @Param("parentId") Long parentId,
                             @Param("name") String name);


    Boolean checkPermissionByCTE(Long targetId, Long rootId);

    List<ItemIdentity> filterItemsUnderRoot(@Param("rootId") Long rootId,
                                            @Param("items") List<ItemIdentity> items);



    List<UserFolder> selectSubTreeBatch(@Param("rootIds") List<Long> rootIds);



    @Insert("INSERT IGNORE INTO user_folder (user_id, parent_id, name, create_time, update_time) " +
            "VALUES (#{userId}, #{parentId}, #{name}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(UserFolder newFolder);
}
