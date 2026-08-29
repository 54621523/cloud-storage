package demo.cloud.file.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import demo.cloud.file.dto.RecycleFileVO;
import org.apache.ibatis.annotations.Param;

public interface UserRecycleBinService {

    IPage<RecycleFileVO> selectRecycleBin(IPage<RecycleFileVO> page, @Param("userId") Long userId);
}
