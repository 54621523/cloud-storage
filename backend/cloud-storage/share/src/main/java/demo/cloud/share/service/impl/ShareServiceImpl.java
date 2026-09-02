package demo.cloud.share.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import demo.cloud.common.exception.BusinessException;
import demo.cloud.common.pojo.PageResult;
import demo.cloud.common.pojo.ResultCode;
import demo.cloud.common.util.JwtUtil;
import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.dto.ItemGroup;
import demo.cloud.file.dto.ItemIdentity;
import demo.cloud.file.dto.VirtualFileVO;
import demo.cloud.file.service.FileDubboService;
import demo.cloud.file.service.UserFolderDubboService;
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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShareServiceImpl extends ServiceImpl<ShareLinkMapper, ShareLink> implements ShareService {

    // ====== Dependencies ======
    private final JwtUtil jwtUtil;
    private final ShareLinkMapper shareLinkMapper;
    private final ShareLinkItemMapper shareLinkItemMapper;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @DubboReference(timeout = 3000)
    private FileDubboService fileDubboService;

    @DubboReference
    private UserFolderDubboService userFolderDubboService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ====== Constants ======
    private static final int MAX_PASSWORD_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final String SHARE_CACHE_PREFIX = "share:valid:";
    private static final long CACHE_TTL_SECONDS = 7200;

    // ====== Constructor ======
    public ShareServiceImpl(JwtUtil jwtUtil,
                            ShareLinkMapper shareLinkMapper,
                            ShareLinkItemMapper shareLinkItemMapper,
                            PasswordEncoder passwordEncoder, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.shareLinkMapper = shareLinkMapper;
        this.shareLinkItemMapper = shareLinkItemMapper;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    // ========================================
    // ====== CREATE ======
    // ========================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateShareResponse createShare(CreateShareRequest request, Long userId) {
        CreateShareResponse response = new CreateShareResponse();

        // 1. 生成分享码
        String shareCode = generateShareCode();
        response.setShareCode(shareCode);

        // 2. 保存分享主表
        ShareLink shareLink = new ShareLink();
        shareLink.setShareCode(shareCode);
        shareLink.setUserId(userId);
        // 密码加密存储
        if (StringUtils.isNotBlank(request.getPassword())) {
            String encodedPassword = passwordEncoder.encode(request.getPassword());
            shareLink.setPassword(encodedPassword);
            response.setPassword(request.getPassword());
        } else {
            shareLink.setPassword(null);
        }

        shareLink.setExpireTime(request.getExpireTime());
        shareLink.setStatus(ShareStatus.ACTIVE);
        shareLink.setDisplayName(request.getDisplayName());
        shareLinkMapper.insert(shareLink);
        response.setId(shareLink.getId());

        if (request.getExpireTime() != null) {
            response.setExpireTime(request.getExpireTime());
        }

        // 3. 保存明细表
        List<ShareLinkItem> items = request.getItems().stream().map(item -> {
            ShareLinkItem linkItem = new ShareLinkItem();
            linkItem.setShareId(shareLink.getId());
            linkItem.setTargetId(item.getTargetId());
            linkItem.setTargetType(item.getTargetType());
            return linkItem;
        }).toList();
        shareLinkItemMapper.insert(items);

        return response;
    }

    // ========================================
    // ====== READ ======
    // ========================================

    @Override
    public String verifyShare(String shareCode, String password) {
        ShareCacheEntity validShareInfo = getValidShareInfo(shareCode);
        Long linkId = validShareInfo.getId();
        if (linkId == null) {
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }

        // 密码验证
        String storedPasswordHash = validShareInfo.getPasswordHash();
        if (StringUtils.isNotBlank(storedPasswordHash)) {
            if (StringUtils.isBlank(password)) {
                throw new BusinessException(ResultCode.SHARE_PASSWORD_REQUIRED);
            }
            boolean passwordMatch = passwordEncoder.matches(password, storedPasswordHash);

            if (!passwordMatch) {
                throw new BusinessException(ResultCode.SHARE_PASSWORD_ERROR);
            }
        }
        return jwtUtil.generateShareToken(shareCode);
    }

    @Override
    public PageResult<ShareLinkVO> queryMyShare(Long pageNum, Long pageSize, Long userId) {
        LambdaQueryWrapper<ShareLinkVO> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(ShareLinkVO::getCreateTime);

        Page<ShareLinkVO> page = new Page<>(pageNum, pageSize);
        IPage<ShareLinkVO> pageResult = shareLinkMapper.selectShareLinkVO(page, userId);
        return PageResult.of(pageResult);
    }

    @Override
    public List<VirtualFileVO> getShareInfo(String shareToken, Long parentId, Long rootId) {
        // 1. 验证分享 Token
        String shareCode = jwtUtil.getShareCodeFromToken(shareToken);
        ShareCacheEntity validShareInfo = getValidShareInfo(shareCode);
        Long linkId = validShareInfo.getId();
        if (linkId == null) {
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }

        // 2. 进入分享页面的虚拟根目录
        if (rootId == 0) {
            List<ShareLinkItem> shareLinkItems = shareLinkItemMapper.selectList(
                    new LambdaQueryWrapper<ShareLinkItem>()
                            .eq(ShareLinkItem::getShareId, linkId)
            );
            return getVirtualFile(shareLinkItems);
        }

        // 3. 进入分享页面的具体文件夹
        boolean exists = shareLinkItemMapper.exists(
                new LambdaQueryWrapper<ShareLinkItem>()
                        .eq(ShareLinkItem::getShareId, linkId)
                        .eq(ShareLinkItem::getTargetId, rootId)
        );
        if (!exists) {
            throw new BusinessException(ResultCode.SHARE_NOT_PERMISSION);
        }
        return fileDubboService.listWithParentId(parentId, rootId);
    }

    @Override
    public String generateDownloadUrl(String shareToken, Long id, Long rootId) {

        String shareCode = jwtUtil.getShareCodeFromToken(shareToken);
        ShareCacheEntity validShareInfo = getValidShareInfo(shareCode);
        Long linkId = validShareInfo.getId();
        if (linkId == null) {
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }

        // 分享页面虚根
        if (rootId == 0) {
            boolean exists = shareLinkItemMapper.exists(
                    new LambdaQueryWrapper<ShareLinkItem>()
                            .eq(ShareLinkItem::getShareId, linkId)
                            .eq(ShareLinkItem::getTargetId, id)
            );
            if (!exists) {
                throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
            }
            return fileDubboService.generateDownloadUrl(id);
        }

        // 进入具体文件夹
        boolean exists = shareLinkItemMapper.exists(
                new LambdaQueryWrapper<ShareLinkItem>()
                        .eq(ShareLinkItem::getShareId, linkId)
                        .eq(ShareLinkItem::getTargetId, rootId)
        );
        if (!exists) {
            throw new BusinessException(ResultCode.SHARE_NOT_PERMISSION);
        }
        return fileDubboService.generateDownloadUrl(id);
    }

    // ========================================
    // ====== UPDATE ======
    // ========================================

    @Override
    @GlobalTransactional(rollbackFor = Exception.class, name = "saveToMyDisk")
    public void saveToMyDisk(String shareToken, Long userId, TransferRequest request) {
        // 1. 验证目标文件夹是否属于该用户
        if (!userFolderDubboService.isOwner(request.getTargetFolderId(), userId)) {
            throw new BusinessException(ResultCode.SHARE_NOT_PERMISSION);
        }

        // 2. Token 与分享链接校验
        String shareCode = jwtUtil.getShareCodeFromToken(shareToken);
        ShareCacheEntity validShareInfo = getValidShareInfo(shareCode);
        Long linkId = validShareInfo.getId();
        if (linkId == null) {
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }

        List<ItemIdentity> requestItems = request.getItems();
        Long rootId = request.getRootId();

        // 3. 过滤合法条目
        List<ItemIdentity> validItems;
        if (rootId == 0) {
            // 分支 A：根视图过滤
            ItemGroup group = ItemGroup.from(requestItems);
            List<ShareLinkItem> hitItems = shareLinkItemMapper.selectList(
                    new LambdaQueryWrapper<ShareLinkItem>()
                            .eq(ShareLinkItem::getShareId, linkId)
                            .and(w -> w
                                    .in(ShareLinkItem::getTargetId, group.fileIds())
                                    .eq(ShareLinkItem::getTargetType, FileItemType.FILE)
                                    .or()
                                    .in(ShareLinkItem::getTargetId, group.folderIds())
                                    .eq(ShareLinkItem::getTargetType, FileItemType.FOLDER)
                            )
                            .select(ShareLinkItem::getTargetId, ShareLinkItem::getTargetType)
            );

            Set<ItemIdentity> validSet = hitItems.stream()
                    .map(item -> new ItemIdentity(item.getTargetId(), item.getTargetType()))
                    .collect(Collectors.toSet());

            validItems = requestItems.stream()
                    .filter(validSet::contains)
                    .collect(Collectors.toList());
        } else {
            // 分支 B：子文件夹过滤
            validItems = fileDubboService.filterItemsUnderRoot(rootId, requestItems);
            if (validItems.size() < requestItems.size()) {
                log.info("分享转存：rootId={}，过滤掉 {} 个非法条目",
                        rootId, requestItems.size() - validItems.size());
            }
        }

        if (validItems.isEmpty()) {
            throw new BusinessException(ResultCode.SHARE_INVALID_ITEM);
        }

        // 4. 执行转存
        fileDubboService.copyBatch(validItems, userId, request.getTargetFolderId());
    }

    // ========================================
    // ====== DELETE ======
    // ========================================

    // 目前暂无删除分享链接的接口，后续可在此扩展：
    // public void deleteShare(Long shareId, Long userId) { ... }
    // public void cancelShare(String shareCode, Long userId) { ... }

    // ========================================
    // ====== PRIVATE METHODS ======
    // ========================================

    private String generateShareCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 获取有效的分享链接缓存实体（含密码哈希）
     * @return 有效实体，若无效返回 null
     */
    public ShareCacheEntity getValidShareInfo(String shareCode) {
        String cacheKey = SHARE_CACHE_PREFIX + shareCode;
        // 1. 尝试从缓存读取
        String cachedJson = (String) redisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            try {
                ShareCacheEntity entity = objectMapper.readValue(cachedJson, ShareCacheEntity.class);
                // 2. 业务校验（即使缓存未过期，也要验证业务状态）
                if (entity.getExpireTime() != null && entity.getExpireTime().isBefore(LocalDateTime.now())) {
                    redisTemplate.delete(cacheKey);
                    return null;
                }
                if (entity.getStatus() != ShareStatus.ACTIVE) {
                    redisTemplate.delete(cacheKey);
                    return null;
                }
                return entity;
            } catch (JsonProcessingException e) {
                log.error("反序列化缓存失败，清除缓存", e);
                redisTemplate.delete(cacheKey);
            }
        }

        // 3. 缓存未命中，查询数据库（查询所有必要字段）
        ShareLink shareLink = shareLinkMapper.selectOne(
                new LambdaQueryWrapper<ShareLink>()
                        .eq(ShareLink::getShareCode, shareCode)
                        .select(ShareLink::getId, ShareLink::getExpireTime,
                                ShareLink::getStatus, ShareLink::getPassword) // 查密码哈希
        );
        if (shareLink == null) {
            return null;
        }

        // 4. 业务过期/状态校验
        if (shareLink.getExpireTime() != null && shareLink.getExpireTime().isBefore(LocalDateTime.now())) {
            return null;
        }
        if (shareLink.getStatus() != ShareStatus.ACTIVE) {
            return null;
        }

        // 5. 写入缓存
        ShareCacheEntity cacheEntity = new ShareCacheEntity(
                shareLink.getId(),
                shareLink.getExpireTime(),
                shareLink.getStatus(),
                shareLink.getPassword() // 可能为 null
        );
        try {
            String json = objectMapper.writeValueAsString(cacheEntity);
            redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(CACHE_TTL_SECONDS));
        } catch (JsonProcessingException e) {
            log.error("序列化缓存失败", e);
        }

        return cacheEntity;
    }

    // 缓存失效方法
    public void evictShareCache(String shareCode) {
        redisTemplate.delete(SHARE_CACHE_PREFIX + shareCode);
    }

    private List<VirtualFileVO> getVirtualFile(List<ShareLinkItem> shareLinkItems) {
        if (shareLinkItems == null || shareLinkItems.isEmpty()) {
            return new ArrayList<>();
        }

        Map<FileItemType, List<Long>> typeToIdsMap = shareLinkItems.stream()
                .collect(Collectors.groupingBy(
                        ShareLinkItem::getTargetType,
                        Collectors.mapping(ShareLinkItem::getTargetId, Collectors.toList())
                ));

        List<Long> fileIds = typeToIdsMap.getOrDefault(FileItemType.FILE, new ArrayList<>());
        List<Long> folderIds = typeToIdsMap.getOrDefault(FileItemType.FOLDER, new ArrayList<>());

        return fileDubboService.list(fileIds, folderIds);
    }

    @Data
    @AllArgsConstructor
    private static class ShareCacheEntity {
        private Long id;
        private LocalDateTime expireTime;
        private ShareStatus status;
        private String passwordHash;
    }

//    private void checkPasswordAttempts(String shareCode) {
//        String key = "share:password:attempts:" + shareCode;
//        Integer attempts = passwordRedisTemplate.opsForValue().get(key);
//        if (attempts != null && attempts >= MAX_PASSWORD_ATTEMPTS) {
//            throw new BusinessException(ResultCode.TOO_MANY_PASSWORD_ATTEMPTS);
//        }
//    }
//
//    private void recordPasswordAttempt(String shareCode, boolean success) {
//        String key = "share:password:attempts:" + shareCode;
//        if (success) {
//            passwordRedisTemplate.delete(key);
//        } else {
//            passwordRedisTemplate.opsForValue().increment(key);
//            passwordRedisTemplate.expire(key, LOCK_DURATION);
//        }
//    }
}