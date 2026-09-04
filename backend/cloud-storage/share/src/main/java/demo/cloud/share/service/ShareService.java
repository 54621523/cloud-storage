package demo.cloud.share.service;

import com.baomidou.mybatisplus.extension.service.IService;
import demo.cloud.common.pojo.PageResult;
import demo.cloud.file.dto.VirtualFileVO;
import demo.cloud.share.dto.CreateShareRequest;
import demo.cloud.share.dto.CreateShareResponse;
import demo.cloud.share.dto.ShareLinkVO;
import demo.cloud.share.dto.TransferRequest;
import demo.cloud.share.pojo.ShareLink;

import java.util.List;

public interface ShareService extends IService<ShareLink> {
    CreateShareResponse createShare(CreateShareRequest dto, Long userId);

    PageResult<ShareLinkVO> queryMyShare(Long pageNum, Long pageSize, Long userId);

    String verifyShare(String shareCode, String password);

    void deleteShareLink(Long id, Long userId);

    List<VirtualFileVO> getShareInfo(String shareToken, Long parentId, Long rootId);

    String generateDownloadUrl(String shareToken, Long targetId, Long rootId);

    void saveToMyDisk(String shareToken, Long userId, TransferRequest request);
}
