package demo.cloud.file.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.yulichang.base.MPJBaseMapper;
import demo.cloud.file.pojo.UserFile;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserFileMapper extends BaseMapper<UserFile>, MPJBaseMapper<UserFile> {


    List<Long> getAllowedDocIdsByUserId(Long userId);

    String getFilenameByPhysicalIdWithUserId(Long docId, Long userId);

    String getOssKeyByUserFileId(Long userFileId);
}