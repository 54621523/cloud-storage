package demo.cloud.share.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.common.oss.service.MinioService;
import demo.cloud.common.pojo.PageResult;
import demo.cloud.common.pojo.ResultCode;
import demo.cloud.common.util.JwtUtil;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.dto.VirtualFileVO;
import demo.cloud.file.service.FileDubboService;
import demo.cloud.share.constant.ShareStatus;
import demo.cloud.share.dto.CreateShareRequest;
import demo.cloud.share.dto.CreateShareResponse;
import demo.cloud.share.dto.ShareLinkVO;
import demo.cloud.share.dto.TransferRequest;
import demo.cloud.share.mapper.ShareLinkItemMapper;
import demo.cloud.share.mapper.ShareLinkMapper;
import demo.cloud.share.pojo.ShareLink;
import demo.cloud.share.pojo.ShareLinkItem;
import demo.cloud.share.service.ShareService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShareServiceImpl extends ServiceImpl<ShareLinkMapper, ShareLink> implements ShareService {


    private final JwtUtil jwtUtil;
    private final MinioService minioService;
    private final ShareLinkMapper shareLinkMapper;
    private final ShareLinkItemMapper shareLinkItemMapper;


    @DubboReference(timeout = 3000)
    private FileDubboService fileDubboService;

    public ShareServiceImpl(JwtUtil jwtUtil, MinioService minioService, ShareLinkMapper shareLinkMapper, ShareLinkItemMapper shareLinkItemMapper) {
        this.jwtUtil = jwtUtil;
        this.minioService = minioService;
        this.shareLinkMapper = shareLinkMapper;
        this.shareLinkItemMapper = shareLinkItemMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateShareResponse createShare(CreateShareRequest request, Long userId) {
        CreateShareResponse response = new CreateShareResponse();
        if (StringUtils.isNotBlank(request.getPassword())) {
            response.setPassword(request.getPassword());
        }
        if (request.getExpireTime() != null) {
            response.setExpireTime(request.getExpireTime());
        }

        // 1. 生成分享码
        String shareCode = generateShareCode();
        response.setShareCode(shareCode);
        // 2. 保存分享主表
        ShareLink shareLink = new ShareLink();
        shareLink.setShareCode(shareCode);
        shareLink.setUserId(userId);
        shareLink.setPassword(request.getPassword());
        shareLink.setExpireTime(request.getExpireTime());
        shareLink.setStatus(ShareStatus.ACTIVE);
        shareLink.setDisplayName(request.getDisplayName());
        shareLinkMapper.insert(shareLink);
        response.setId(shareLink.getId());
        // 3. 保存明细表
        List<ShareLinkItem> items = request.getItems().stream().map(item -> {
            ShareLinkItem linkItem = new ShareLinkItem();
            linkItem.setShareId(shareLink.getId());
            linkItem.setTargetId(item.getTargetId());
            linkItem.setTargetType(item.getTargetType());
            return linkItem;
        }).toList();
        // 4. 插入表
        shareLinkItemMapper.insert(items);
        return response;
    }

    private String generateShareCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public PageResult<ShareLinkVO> queryMyShare(Long pageNum, Long pageSize, Long userId) {
        LambdaQueryWrapper<ShareLinkVO> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(ShareLinkVO::getCreateTime);

        Page<ShareLinkVO> page = new Page<>(pageNum, pageSize);
        IPage<ShareLinkVO> pageResult = shareLinkMapper.selectShareLinkVO(page, userId);
        return PageResult.of(pageResult);
    }

    @Override
    public String verifyShare(String shareCode, String password) {
        ShareLink shareLink = shareLinkMapper.selectOne(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getShareCode, shareCode)
                .eq(ShareLink::getStatus, 1)
                .last("LIMIT 1"));
        if (shareLink == null) {
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }
        if (shareLink.getExpireTime() != null && shareLink.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.SHARE_EXPIRED);
        }
        if (StringUtils.isNotBlank(shareLink.getPassword())
                && !shareLink.getPassword().equals(password)) {
            throw new BusinessException(ResultCode.SHARE_PASSWORD_ERROR);
        }
        return jwtUtil.generateShareToken(shareCode);
    }

    @Override
    public List<VirtualFileVO> getShareInfo(String shareToken, Long parentId, Long rootId) {

        // 1.验证shareToken对应的分享链接
        String shareCode = jwtUtil.getShareCodeFromToken(shareToken);
        ShareLink shareLink = shareLinkMapper.selectOne(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getShareCode, shareCode));
        if (shareLink == null) throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        // 2. 进入分享页面的虚拟根目录
        if (rootId == 0) {
            List<ShareLinkItem> shareLinkItems = shareLinkItemMapper.selectList(new LambdaQueryWrapper<ShareLinkItem>()
                    .eq(ShareLinkItem::getShareId, shareLink.getId()));
            return getVirtualFile(shareLinkItems);
        }
        // 3. 进入了分享页面的具体文件夹
        else {
            // 3.1 验证rootId是否在分享链接管辖下
            boolean exists = shareLinkItemMapper.exists(
                    new LambdaQueryWrapper<ShareLinkItem>()
                            .eq(ShareLinkItem::getShareId, shareLink.getId())
                            .eq(ShareLinkItem::getTargetId, rootId));
            if (!exists) {
                throw new BusinessException(ResultCode.SHARE_NOT_PERMISSION);
            }
            return fileDubboService.listWithParentId(parentId, rootId);
        }
    }

    private List<VirtualFileVO> getVirtualFile(List<ShareLinkItem> shareLinkItems) {
        if (shareLinkItems == null || shareLinkItems.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 按照 targetType 分组，提取 targetId
        Map<FileItemType, List<Long>> typeToIdsMap = shareLinkItems.stream()
                .collect(Collectors.groupingBy(
                        ShareLinkItem::getTargetType,
                        Collectors.mapping(ShareLinkItem::getTargetId, Collectors.toList())
                ));

        List<Long> fileIds = typeToIdsMap.getOrDefault(FileItemType.FILE, new ArrayList<>());
        List<Long> folderIds = typeToIdsMap.getOrDefault(FileItemType.FOLDER, new ArrayList<>());

        // 2. 调用底层服务获取合并并排序后的 VO 列表
        List<VirtualFileVO> virtualFiles = fileDubboService.list(fileIds, folderIds);

        return virtualFiles;
    }

    @Override
    public String generateDownloadUrl(String shareToken, Long id, Long rootId) {
        //TODO
        String shareCode = jwtUtil.getShareCodeFromToken(shareToken);
        ShareLink shareLink = shareLinkMapper.selectOne(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getShareCode, shareCode));
        if (shareLink == null) throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        // 分享页面虚根
        if(rootId == 0){
            boolean exists = shareLinkItemMapper.exists(
                    new LambdaQueryWrapper<ShareLinkItem>()
                            .eq(ShareLinkItem::getShareId, shareLink.getId())
                            .eq(ShareLinkItem::getTargetId, id)
            );

            if (exists) {
                return fileDubboService.getOssKey(id);
            } else {
                throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
            }
        }
        else{
            boolean exists = shareLinkItemMapper.exists(
                    new LambdaQueryWrapper<ShareLinkItem>()
                            .eq(ShareLinkItem::getShareId, shareLink.getId())
                            .eq(ShareLinkItem::getTargetId, rootId));
            if (!exists) {
                throw new BusinessException(ResultCode.SHARE_NOT_PERMISSION);
            }
            return fileDubboService.getOssKey(id);
        }

    }

    @Override
    public void saveToMyDisk(String shareToken, Long userId, TransferRequest request) {
        // TODO 验证目标文件夹是否属于该用户
        // 1. Token 与分享链接校验
        String shareCode = jwtUtil.getShareCodeFromToken(shareToken);
        ShareLink shareLink = shareLinkMapper.selectOne(
                new LambdaQueryWrapper<ShareLink>().eq(ShareLink::getShareCode, shareCode)
        );
        if (shareLink == null) throw new BusinessException(ResultCode.SHARE_NOT_FOUND);

        List<ItemIdentity> requestItems = request.getItems();
        if (requestItems == null || requestItems.isEmpty()) {
            throw new BusinessException(ResultCode.SHARE_INVALID_ITEM);
        }

        List<ItemIdentity> validItems;
        Long rootId = request.getRootId();

        if (rootId == null || rootId == 0) {
            // ---------- 分支 A：根视图过滤 ----------
            // 构造 (target_id = ? AND target_type = ?) OR ... 条件
            List<ShareLinkItem> hitItems = shareLinkItemMapper.selectList(
                    new LambdaQueryWrapper<ShareLinkItem>()
                            .eq(ShareLinkItem::getShareId, shareLink.getId())
                            .and(wrapper -> {
                                for (ItemIdentity item : requestItems) {
                                    wrapper.or(sub -> sub
                                            .eq(ShareLinkItem::getTargetId, item.getId())
                                            .eq(ShareLinkItem::getTargetType, item.getType())
                                    );
                                }
                            })
                            .select(ShareLinkItem::getTargetId, ShareLinkItem::getTargetType)
            );
            // 构建 Set<ItemIdentity>
            Set<ItemIdentity> validSet = hitItems.stream()
                    .map(item -> new ItemIdentity(item.getTargetId(), item.getTargetType()))
                    .collect(Collectors.toSet());
            validItems = requestItems.stream()
                    .filter(validSet::contains)
                    .collect(Collectors.toList());
        } else {
            // ---------- 分支 B：子文件夹过滤 ----------
            validItems = fileDubboService.filterItemsUnderRoot(rootId, requestItems);
            // 记录过滤日志
            if (validItems.size() < requestItems.size()) {
                log.info("分享转存：rootId={}，过滤掉 {} 个非法条目", rootId, requestItems.size() - validItems.size());
            }
        }

        if (validItems.isEmpty()) {
            throw new BusinessException(ResultCode.SHARE_INVALID_ITEM);
        }

        // 5. 执行转存（文件系统负责递归复制、事务、重名处理）
        fileDubboService.copyBatch(validItems, userId, request.getTargetFolderId());
    }
}