package demo.cloud.share.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import demo.cloud.share.mq.DelayedRabbitConfig;
import demo.cloud.share.pojo.ShareLink;
import demo.cloud.share.pojo.ShareLinkItem;
import demo.cloud.share.service.ShareService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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

    private final RabbitTemplate rabbitTemplate;

    @DubboReference(timeout = 3000)
    private FileDubboService fileDubboService;

    @DubboReference
    private UserFolderDubboService userFolderDubboService;



    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ====== Constants ======
    private static final String SHARE_CACHE_PREFIX = "share:valid:";
    private static final long CACHE_TTL_SECONDS = 7200;

    private static final String NULL_VALUE = "NULL";
    private static final int BASE_TTL_SECONDS = 600;      //
    private static final int RANDOM_TTL_OFFSET = 120;     //

    // ====== Constructor ======
    public ShareServiceImpl(JwtUtil jwtUtil,
                            ShareLinkMapper shareLinkMapper,
                            ShareLinkItemMapper shareLinkItemMapper,
                            PasswordEncoder passwordEncoder, ObjectMapper objectMapper,
                            RabbitTemplate rabbitTemplate) {
        this.jwtUtil = jwtUtil;
        this.shareLinkMapper = shareLinkMapper;
        this.shareLinkItemMapper = shareLinkItemMapper;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
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
            shareLink.setPassword(request.getPassword());
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
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 发送延迟消息，延迟时间 = expireTime - now()
                        if (shareLink.getExpireTime() != null) {
                            long delayMs = Duration.between(LocalDateTime.now(), shareLink.getExpireTime()).toMillis();

                            if (delayMs > 0) {
                                rabbitTemplate.convertAndSend(
                                        DelayedRabbitConfig.DELAYED_EXCHANGE,
                                        DelayedRabbitConfig.DELAYED_ROUTING_KEY,
                                        shareLink.getShareCode(),
                                        message -> {
                                            message.getMessageProperties().setHeader("x-delay", delayMs);
                                            return message;
                                        }
                                );
                            }
                        }
                    }
                }
        );
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
        String storedPassword = validShareInfo.getPassword();
        if (StringUtils.isNotBlank(storedPassword)) {
            if (StringUtils.isBlank(password)) {
                throw new BusinessException(ResultCode.SHARE_PASSWORD_REQUIRED);
            }
            boolean passwordMatch = password.equals(storedPassword);

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
    private String buildCacheKey(String shareCode, Long rootId, Long parentId) {
        return String.format("share:info:%s:%d:%d", shareCode, rootId, parentId);
    }

    @Override
    public List<VirtualFileVO> getShareInfo(String shareToken, Long parentId, Long rootId) {

        // 1. 验证分享 Token
        String shareCode = jwtUtil.getShareCodeFromToken(shareToken);
        ShareCacheEntity validShareInfo = getValidShareInfo(shareCode);
        Long linkId = validShareInfo.getId();
        String cacheKey = buildCacheKey(shareCode, rootId, parentId);
        if (linkId == null) {
            redisTemplate.opsForValue().set(cacheKey, NULL_VALUE, 2, TimeUnit.MINUTES);
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }

        String cachedJson = (String) redisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            try {
                // 使用 Jackson 反序列化
                log.info("命中缓存");
                return objectMapper.readValue(cachedJson, new TypeReference<List<VirtualFileVO>>() {});
            } catch (JsonProcessingException e) {
                log.error("反序列化缓存失败，缓存 Key: {}", cacheKey, e);
                redisTemplate.delete(cacheKey);
            }
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
            redisTemplate.opsForValue().set(cacheKey, NULL_VALUE, 2, TimeUnit.MINUTES);
            throw new BusinessException(ResultCode.SHARE_NOT_PERMISSION);
        }
        List<VirtualFileVO> result = fileDubboService.listWithParentId(parentId, rootId);
        if(result == null || result.isEmpty()){

        }
        try {
            String json = objectMapper.writeValueAsString(result);
            long ttl = BASE_TTL_SECONDS + ThreadLocalRandom.current().nextInt(-RANDOM_TTL_OFFSET, RANDOM_TTL_OFFSET + 1);
            redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(Math.max(ttl, 1)));
        } catch (JsonProcessingException e) {
            log.error("序列化缓存失败", e);
        }
        return result;
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
            LambdaQueryWrapper<ShareLinkItem> wrapper = new LambdaQueryWrapper<ShareLinkItem>()
                            .eq(ShareLinkItem::getShareId, linkId);
            wrapper.and(w -> {
                boolean hasFiles = !group.fileIds().isEmpty();
                boolean hasFolders = !group.folderIds().isEmpty();
                if (!hasFiles && !hasFolders) {
                    w.apply("1=0"); // 无条件返回空
                    return;
                }
                // 先处理文件部分
                if (hasFiles) {
                    w.in(ShareLinkItem::getTargetId, group.fileIds())
                            .eq(ShareLinkItem::getTargetType, FileItemType.FILE);
                }
                // 再通过 or 连接文件夹部分
                if (hasFolders) {
                    if (hasFiles) {
                        w.or();
                    }
                    w.in(ShareLinkItem::getTargetId, group.folderIds())
                            .eq(ShareLinkItem::getTargetType, FileItemType.FOLDER);
                }
            });
            List<ShareLinkItem> hitItems = shareLinkItemMapper.selectList(wrapper);

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

    /**
     * @param id
     * @param userId
     */
    @Override
    public void deleteShareLink(Long id, Long userId) {
        ShareLink shareLink = shareLinkMapper.selectOne(
                new LambdaQueryWrapper<ShareLink>()
                        .eq(ShareLink::getUserId, userId)
                        .eq(ShareLink::getId, id)
        );
        if(shareLink == null){
            return;
        }
        shareLinkMapper.delete(
                new LambdaQueryWrapper<ShareLink>()
                        .eq(ShareLink::getUserId, userId)
                        .eq(ShareLink::getId, id)
        );
        shareLinkItemMapper.delete(
                new LambdaQueryWrapper<ShareLinkItem>()
                        .eq(ShareLinkItem::getShareId, id)
        );
        String cachedKey = SHARE_CACHE_PREFIX + shareLink.getShareCode();
        redisTemplate.delete(cachedKey);
    }

    // ========================================
    // ====== PRIVATE METHODS ======
    // ========================================

    private String generateShareCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 获取有效的分享链接缓存实体
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
    @NoArgsConstructor
    private static class ShareCacheEntity {
        private Long id;
        private LocalDateTime expireTime;
        private ShareStatus status;
        private String password;
    }
}