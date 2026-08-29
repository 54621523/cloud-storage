package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import demo.cloud.file.dto.RecycleFileVO;
import demo.cloud.file.mapper.UserRecycleBinMapper;
import demo.cloud.file.service.UserRecycleBinService;
import org.springframework.stereotype.Service;

@Service
public class UserRecycleBinServiceImpl implements UserRecycleBinService {


    private final UserRecycleBinMapper userRecycleBinMapper;

    public UserRecycleBinServiceImpl(UserRecycleBinMapper userRecycleBinMapper) {
        this.userRecycleBinMapper = userRecycleBinMapper;
    }


    /**
     * @param page
     * @param userId
     * @return
     */
    @Override
    public IPage<RecycleFileVO> selectRecycleBin(IPage<RecycleFileVO> page, Long userId) {
       return userRecycleBinMapper.selectRecycleBin(page, userId);
    }
}
