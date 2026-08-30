package demo.cloud.file.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.file.dto.UserRootFolderDTO;
import demo.cloud.file.mapper.UserFolderMapper;
import demo.cloud.file.pojo.UserFolder;
import demo.cloud.file.service.UserFolderDubboService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@DubboService(interfaceClass = UserFolderDubboService.class)
@Service
@Slf4j
@RequiredArgsConstructor
public class UserFolderServiceImpl extends ServiceImpl<UserFolderMapper, UserFolder> implements UserFolderDubboService {

    private final UserFolderMapper userFolderMapper;


    @Override
    public UserRootFolderDTO getRootFolder(Long userId) {
        UserFolder userFolder = userFolderMapper.selectOne(new LambdaQueryWrapper<UserFolder>()
                .eq(UserFolder::getUserId, userId)
                .eq(UserFolder::getParentId, 0L)
        );
        return UserRootFolderDTO.builder()
                .rootFolderID(userFolder.getId())
                .rootFolderName(userFolder.getName())
                .build();
    }

    /**
     * @param userId
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserRootFolderDTO createRootFolder(Long userId) {
        UserFolder userFolder = new UserFolder();
        userFolder.setName("我的文件");
        userFolder.setUserId(userId);
        userFolderMapper.insert(userFolder);
        UserRootFolderDTO dto = new UserRootFolderDTO();
        dto.setRootFolderID(userFolder.getId());
        dto.setRootFolderName(userFolder.getName());
        return dto;
    }

    /**
     * @param parentId
     * @param userId
     * @return
     */
    @Override
    public boolean isOwner(Long parentId, Long userId) {
        return userFolderMapper.exists(
                new LambdaQueryWrapper<UserFolder>()
                        .eq(UserFolder::getId, parentId)
                        .eq(UserFolder::getUserId, userId)
                        .isNull(UserFolder::getDeletedAt)
        );
    }
}