package demo.cloud.file.service;

import demo.cloud.file.dto.UserRootFolderDTO;

public interface UserFolderDubboService {

    UserRootFolderDTO getRootFolder(Long userId);
}
