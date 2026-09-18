package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.dao.SocialLikeMapper;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dao.SocialUserSettingMapper;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.entity.SocialUserSetting;
import com.hengde.social.vo.SocialVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 志愿者的帖子（Row 23）：发帖 / 修改 / 删除 / 帖子流（最新 / 最热 / 关注 / 官方 / 搜索）/ 详情 / TA的主页 / 分享。
 *
 * <p><b>先发后审</b>（D7）：发出即按可见性显示，{@code review_status} 落「待审核」留给治理批；修改后重回待审核。</p>
 *
 * <p><b>可见性只认 {@link SocialPostMapper#VISIBLE}</b>——详情、点赞、评论、分享前置判断都用同一段 SQL，
 * 不在 Java 里再写一份「关注了才能看」。</p>
 *
 * @author hengde
 */
@Service
public class SocialPostService {

    private SocialPostMapper postMapper;
    private SocialLikeMapper likeMapper;
    private SocialUserSettingMapper settingMapper;
    private SocialGateService gate;
    private SocialCardService cardService;
    private SocialMediaService mediaService;
    private HotRankService hotRankService;
    private SocialKeywordService keywordService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setKeywordService(SocialKeywordService keywordService) {
        this.keywordService = keywordService;
    }

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Autowired
    public void setLikeMapper(SocialLikeMapper likeMapper) {
        this.likeMapper = likeMapper;
    }

    @Autowired
    public void setSettingMapper(SocialUserSettingMapper settingMapper) {
        this.settingMapper = settingMapper;
    }

    @Autowired
    public void setGate(SocialGateService gate) {
        this.gate = gate;
    }

    @Autowired
    public void setCardService(SocialCardService cardService) {
        this.cardService = cardService;
    }

    @Autowired
    public void setMediaService(SocialMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Autowired
    public void setHotRankService(HotRankService hotRankService) {
        this.hotRankService = hotRankService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================= 写 =================

    public Long publish(Long volunteerId, SocialDTOs.PostSave dto) {
        gate.requireActor(volunteerId);
        SocialPost post = fromDto(dto);
        post.setAuthorType(SocialCodes.AUTHOR_VOLUNTEER);
        post.setAuthorId(volunteerId);
        return transactionTemplate.execute(s -> {
            gate.assertNotRestricted(volunteerId, SanctionScope.COMMUNITY_POST, "发帖");
            postMapper.insert(post);
            return post.getId();
        });
    }

    /**
     * 相册上传同步发帖（Row 30「默认勾选发送到交流平台」，由 {@code AlbumSocialSyncListener} 调）。
     *
     * <p>与普通发帖同样过四层门（已实名、禁止发帖闸门）与关键词风控；照片只收本系统传到 {@code album/} 下的，最多取前 9 张；
     * 正文为「【相册标题】评论」，可见性不限制。</p>
     */
    public Long publishFromAlbum(Long volunteerId, String albumTitle, String comment, java.util.List<String> photoUrls) {
        gate.requireActor(volunteerId);
        java.util.List<String> urls = mediaService.validateAlbumPhotos(photoUrls);
        String text = "【" + (albumTitle == null ? "活动相册" : albumTitle) + "】" + (comment == null ? "" : comment.trim());
        SocialDTOs.PostSave dto = new SocialDTOs.PostSave();
        dto.setContent(text.length() > SocialCodes.MAX_CONTENT ? text.substring(0, SocialCodes.MAX_CONTENT) : text);
        SocialPost post = fromDtoTrusted(dto, urls);
        post.setAuthorType(SocialCodes.AUTHOR_VOLUNTEER);
        post.setAuthorId(volunteerId);
        return transactionTemplate.execute(s -> {
            gate.assertNotRestricted(volunteerId, SanctionScope.COMMUNITY_POST, "发帖");
            postMapper.insert(post);
            return post.getId();
        });
    }

    // ================= 爱心企业发帖（V4 爱心企业批·社区段；企业身份由 api 的企业端闸门保证已审核通过） =================

    /**
     * 企业发帖：与志愿者发帖同一张表、同一套审核与关键词风控，作者名与头像存快照。
     *
     * <p>企业没有关注关系，也没有「不让TA看」，所以<b>不收可见性</b>（一律公开）；处置闸门是志愿者账号的东西，企业不走它。</p>
     */
    public Long publishForEnterprise(Long enterpriseId, String name, String avatarUrl, SocialDTOs.PostSave dto) {
        if (enterpriseId == null) {
            throw new BusinessException("企业不能为空");
        }
        SocialPost post = fromDto(dto);
        post.setAuthorType(SocialCodes.AUTHOR_ENTERPRISE);
        post.setAuthorId(enterpriseId);
        post.setVisibility(SocialCodes.VISIBLE_ALL);
        post.setAuthorSnapshotName(name);
        post.setAuthorSnapshotAvatar(avatarUrl);
        return transactionTemplate.execute(s -> {
            postMapper.insert(post);
            return post.getId();
        });
    }

    /** 企业改自己的帖子（改完重回待审核，同志愿者）。 */
    public void updateForEnterprise(Long enterpriseId, Long postId, SocialDTOs.PostSave dto) {
        SocialPost values = fromDto(dto);
        int rows = postMapper.update(null, Wrappers.<SocialPost>lambdaUpdate()
                .eq(SocialPost::getId, postId)
                .eq(SocialPost::getAuthorType, SocialCodes.AUTHOR_ENTERPRISE)
                .eq(SocialPost::getAuthorId, enterpriseId)
                .set(SocialPost::getContent, values.getContent())
                .set(SocialPost::getMediaType, values.getMediaType())
                .set(SocialPost::getMediaUrls, values.getMediaUrls())
                .set(SocialPost::getAllowComment, values.getAllowComment())
                .set(SocialPost::getAllowLike, values.getAllowLike())
                .set(SocialPost::getReviewStatus, SocialCodes.REVIEW_PENDING)
                .set(SocialPost::getReviewLevel, 0)
                .set(SocialPost::getKeywordHit, values.getKeywordHit())
                .set(SocialPost::getKeywordHits, values.getKeywordHits())
                .set(SocialPost::getEditTime, LocalDateTime.now())
                .set(SocialPost::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("帖子不存在");
        }
    }

    /** 企业删自己的帖子（软删走显式 SQL，{@code @TableLogic} 列不能经 wrapper set）。 */
    public void deleteForEnterprise(Long enterpriseId, Long postId) {
        if (postMapper.softDeleteByEnterprise(postId, enterpriseId) != 1) {
            throw new BusinessException("帖子不存在");
        }
    }

    /** 企业看自己的帖子（含审核中与被驳回的，给企业自己看状态）。 */
    public PageResult<SocialVOs.Post> ownPostsOfEnterprise(Long enterpriseId, PageQuery query) {
        long total = postMapper.selectCount(Wrappers.<SocialPost>lambdaQuery()
                .eq(SocialPost::getAuthorType, SocialCodes.AUTHOR_ENTERPRISE)
                .eq(SocialPost::getAuthorId, enterpriseId));
        List<SocialPost> rows = postMapper.selectList(Wrappers.<SocialPost>lambdaQuery()
                .eq(SocialPost::getAuthorType, SocialCodes.AUTHOR_ENTERPRISE)
                .eq(SocialPost::getAuthorId, enterpriseId)
                .orderByDesc(SocialPost::getCreateTime)
                .orderByDesc(SocialPost::getId)
                .last("LIMIT " + ((long) (query.getPage() - 1) * query.getSize()) + ", " + query.getSize()));
        List<SocialVOs.Post> vos = toVOs(null, rows);
        for (int i = 0; i < rows.size(); i++) {
            vos.get(i).setMine(true);
            vos.get(i).setReviewStatusLabel(com.hengde.social.constant.SocialGovCodes.reviewLabel(rows.get(i).getReviewStatus()));
        }
        return PageResult.of(vos, total, query.getPage(), query.getSize());
    }

    /** 企业主页的帖子（志愿者看，只列看得到的）。 */
    public PageResult<SocialVOs.Post> enterprisePosts(Long viewer, Long enterpriseId, PageQuery query) {
        gate.requireViewer(viewer);
        long total = postMapper.countEnterpriseVisible(viewer, enterpriseId);
        List<SocialPost> rows = postMapper.selectEnterpriseVisible(viewer,
                enterpriseId, (long) (query.getPage() - 1) * query.getSize(), query.getSize());
        return PageResult.of(toVOs(viewer, rows), total, query.getPage(), query.getSize());
    }

    /** 修改（只能改自己的）：正文、图片 / 视频、可见性、评论 / 点赞开关；改完重回待审核。 */
    public void update(Long volunteerId, Long postId, SocialDTOs.PostSave dto) {
        gate.requireActor(volunteerId);
        SocialPost p = fromDto(dto);
        Integer n = transactionTemplate.execute(s -> {
            gate.assertNotRestricted(volunteerId, SanctionScope.COMMUNITY_POST, "修改帖子");
            return postMapper.update(null, Wrappers.<SocialPost>lambdaUpdate()
                    .eq(SocialPost::getId, postId)
                    .eq(SocialPost::getAuthorType, SocialCodes.AUTHOR_VOLUNTEER)
                    .eq(SocialPost::getAuthorId, volunteerId)
                    .set(SocialPost::getContent, p.getContent())
                    .set(SocialPost::getMediaType, p.getMediaType())
                    .set(SocialPost::getMediaUrls, p.getMediaUrls())
                    .set(SocialPost::getVisibility, p.getVisibility())
                    .set(SocialPost::getAllowComment, p.getAllowComment())
                    .set(SocialPost::getAllowLike, p.getAllowLike())
                    .set(SocialPost::getReviewStatus, SocialCodes.REVIEW_PENDING)
                    .set(SocialPost::getReviewLevel, 0)
                    .set(SocialPost::getKeywordHit, p.getKeywordHit())
                    .set(SocialPost::getKeywordHits, p.getKeywordHits())
                    .set(SocialPost::getEditTime, LocalDateTime.now())
                    .set(SocialPost::getUpdateTime, LocalDateTime.now()));
        });
        if (n == null || n == 0) {
            throw new BusinessException("帖子不存在");
        }
    }

    /** 删除自己的帖子（逻辑删除；评论不连带删，帖子看不到了评论自然也看不到）。 */
    public void delete(Long volunteerId, Long postId) {
        if (postMapper.softDeleteByAuthor(postId, volunteerId) == 0) {
            throw new BusinessException("帖子不存在");
        }
    }

    /** 分享（Row 23「分享量」）：看得到才能分享，只记次数。 */
    public void share(Long viewer, Long postId) {
        gate.requireViewer(viewer);
        requireVisible(viewer, postId);
        postMapper.incShare(postId);
        hotRankService.bumpShare(postId);
    }

    // ================= 读 =================

    public PageResult<SocialVOs.Post> feed(Long viewer, String tab, String keyword, PageQuery query) {
        gate.requireViewer(viewer);
        String kw = escapeLike(keyword);
        String t = tab == null ? SocialCodes.TAB_LATEST : tab;
        if (!Set.of(SocialCodes.TAB_LATEST, SocialCodes.TAB_HOT, SocialCodes.TAB_FOLLOWING, SocialCodes.TAB_OFFICIAL).contains(t)) {
            throw new BusinessException("不认识的页签：" + tab);
        }
        if (SocialCodes.TAB_HOT.equals(t) && kw == null) {
            List<Long> ranked = hotRankService.topIds();
            if (!ranked.isEmpty()) {
                return hotPage(viewer, ranked, query);
            }
            t = SocialCodes.TAB_LATEST;
        }
        if (SocialCodes.TAB_HOT.equals(t)) {
            t = SocialCodes.TAB_LATEST;
        }
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialPost> rows = postMapper.selectVisible(viewer, t, null, kw, null, offset, query.getSize());
        long total = postMapper.countVisible(viewer, t, null, kw, null);
        return PageResult.of(toVOs(viewer, rows), total, query.getPage(), query.getSize());
    }

    /** TA的主页 / 自己主页的帖子（看的人不同，看得到的不同）。 */
    public PageResult<SocialVOs.Post> userPosts(Long viewer, Long userId, PageQuery query) {
        gate.requireViewer(viewer);
        cardService.requireCard(userId);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialPost> rows = postMapper.selectVisible(viewer, null, userId, null, null, offset, query.getSize());
        long total = postMapper.countVisible(viewer, null, userId, null, null);
        return PageResult.of(toVOs(viewer, rows), total, query.getPage(), query.getSize());
    }

    /** 帖子详情：查看量 +1（按打开次数计），记热度。 */
    public SocialVOs.Post detail(Long viewer, Long postId) {
        gate.requireViewer(viewer);
        SocialPost p = requireVisible(viewer, postId);
        if (postMapper.incView(postId) == 1) {
            p.setViewCount(p.getViewCount() + 1);
            hotRankService.bumpView(postId);
        }
        return toVOs(viewer, List.of(p)).get(0);
    }

    // ================= 内部（同模块的点赞 / 评论也用） =================

    SocialPost requireVisible(Long viewer, Long postId) {
        SocialPost p = postId == null ? null : postMapper.selectVisibleById(viewer, postId);
        if (p == null) {
            throw new BusinessException("帖子不存在或你无权查看");
        }
        return p;
    }

    /** 帖子允许评论，且（志愿者帖）作者没有在主页禁止评论。 */
    boolean commentable(SocialPost p, SocialUserSetting authorSetting) {
        return Integer.valueOf(1).equals(p.getAllowComment())
                && (authorSetting == null || !Integer.valueOf(1).equals(authorSetting.getForbidComment()));
    }

    boolean likeable(SocialPost p, SocialUserSetting authorSetting) {
        return Integer.valueOf(1).equals(p.getAllowLike())
                && (authorSetting == null || !Integer.valueOf(1).equals(authorSetting.getForbidLike()));
    }

    SocialUserSetting authorSetting(SocialPost p) {
        return Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType()) ? settingMapper.selectById(p.getAuthorId()) : null;
    }

    List<SocialVOs.Post> toVOs(Long viewer, List<SocialPost> posts) {
        if (posts.isEmpty()) {
            return List.of();
        }
        Set<Long> volunteerAuthors = new HashSet<>();
        posts.stream().filter(p -> Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType()))
                .forEach(p -> volunteerAuthors.add(p.getAuthorId()));
        Map<Long, SocialVOs.Author> authors = cardService.volunteers(volunteerAuthors);
        Map<Long, SocialUserSetting> settings = new HashMap<>();
        if (!volunteerAuthors.isEmpty()) {
            settingMapper.selectBatchIds(volunteerAuthors).forEach(s -> settings.put(s.getVolunteerId(), s));
        }
        Set<Long> liked = viewer == null ? Set.of()
                : new HashSet<>(likeMapper.selectLikedPostIds(viewer, posts.stream().map(SocialPost::getId).toList()));
        List<SocialVOs.Post> out = new ArrayList<>();
        for (SocialPost p : posts) {
            boolean official = Integer.valueOf(SocialCodes.AUTHOR_OFFICIAL).equals(p.getAuthorType());
            // 企业帖的 author_id 是企业 id：按它去取志愿者的设置 / 判「这是我发的」都会撞上 id 恰好相同的那个志愿者
            boolean volunteerPost = Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType());
            SocialUserSetting setting = volunteerPost ? settings.get(p.getAuthorId()) : null;
            SocialVOs.Post vo = new SocialVOs.Post();
            vo.setId(p.getId());
            vo.setAuthor(official ? cardService.official(p.getOfficialDepartment())
                    : Integer.valueOf(SocialCodes.AUTHOR_ENTERPRISE).equals(p.getAuthorType())
                    ? cardService.enterprise(p.getAuthorSnapshotName(), p.getAuthorSnapshotAvatar())
                    : authors.get(p.getAuthorId()));
            vo.setOfficialLabel(p.getOfficialLabel());
            vo.setContent(p.getContent());
            vo.setMediaType(p.getMediaType());
            vo.setMediaUrls(mediaService.fromJson(p.getMediaUrls()));
            vo.setVisibility(p.getVisibility());
            vo.setVisibilityLabel(SocialCodes.visibilityLabel(p.getVisibility()));
            vo.setCommentable(commentable(p, setting));
            vo.setLikeable(likeable(p, setting));
            vo.setViewCount(p.getViewCount());
            vo.setLikeCount(p.getLikeCount());
            vo.setCommentCount(p.getCommentCount());
            vo.setShareCount(p.getShareCount());
            vo.setLikedByMe(liked.contains(p.getId()));
            vo.setMine(volunteerPost && Objects.equals(p.getAuthorId(), viewer));
            vo.setCreateTime(p.getCreateTime());
            vo.setEditTime(p.getEditTime());
            vo.setPinned(Integer.valueOf(1).equals(p.getPinned()));
            if (vo.isMine()) {
                vo.setReviewStatusLabel(com.hengde.social.constant.SocialGovCodes.reviewLabel(p.getReviewStatus()));
                vo.setHiddenByAdmin(Integer.valueOf(1).equals(p.getAdminHidden()));
                vo.setAwaitingKeywordReview(Integer.valueOf(1).equals(p.getKeywordHit())
                        && Integer.valueOf(SocialCodes.REVIEW_PENDING).equals(p.getReviewStatus()));
            }
            out.add(vo);
        }
        return out;
    }

    private PageResult<SocialVOs.Post> hotPage(Long viewer, List<Long> ranked, PageQuery query) {
        List<SocialPost> visible = postMapper.selectVisible(viewer, null, null, null, ranked, 0, ranked.size());
        Map<Long, SocialPost> byId = new HashMap<>();
        visible.forEach(p -> byId.put(p.getId(), p));
        List<SocialPost> ordered = ranked.stream().map(byId::get).filter(Objects::nonNull).toList();
        int from = Math.min((query.getPage() - 1) * query.getSize(), ordered.size());
        int to = Math.min(from + query.getSize(), ordered.size());
        return PageResult.of(toVOs(viewer, ordered.subList(from, to)), ordered.size(), query.getPage(), query.getSize());
    }

    private SocialPost fromDto(SocialDTOs.PostSave dto) {
        if (dto == null) {
            throw new BusinessException("请填写帖子内容");
        }
        return build(dto, mediaService.validate(dto.getContent(), dto.getImageUrls(), dto.getVideoUrl()));
    }

    /** 图片已由调用方按别的目录校验过（相册同步）。 */
    private SocialPost fromDtoTrusted(SocialDTOs.PostSave dto, java.util.List<String> imageUrls) {
        return build(dto, new SocialMediaService.Media(imageUrls.isEmpty() ? SocialCodes.MEDIA_NONE : SocialCodes.MEDIA_IMAGE, imageUrls));
    }

    private SocialPost build(SocialDTOs.PostSave dto, SocialMediaService.Media media) {
        int visibility = dto.getVisibility() == null ? SocialCodes.VISIBLE_ALL : dto.getVisibility();
        if (!SocialCodes.VISIBILITIES.contains(visibility)) {
            throw new BusinessException("不认识的可见性：" + dto.getVisibility());
        }
        SocialPost p = new SocialPost();
        p.setContent(dto.getContent() == null ? "" : dto.getContent().trim());
        p.setMediaType(media.type());
        p.setMediaUrls(mediaService.toJson(media));
        p.setVisibility(visibility);
        p.setAllowComment(Boolean.FALSE.equals(dto.getAllowComment()) ? 0 : 1);
        p.setAllowLike(Boolean.FALSE.equals(dto.getAllowLike()) ? 0 : 1);
        p.setReviewStatus(SocialCodes.REVIEW_PENDING);
        p.setReviewLevel(0);
        // 风控关键词（Row 23 F）：命中即先藏后审，审核队列里插队（D7 / Q1）
        java.util.List<String> hits = keywordService.hits(p.getContent());
        p.setKeywordHit(hits.isEmpty() ? 0 : 1);
        String joined = String.join("、", hits);
        p.setKeywordHits(hits.isEmpty() ? null : (joined.length() > 255 ? joined.substring(0, 255) : joined));
        p.setAdminHidden(0);
        p.setPinned(0);
        p.setViewCount(0);
        p.setLikeCount(0);
        p.setCommentCount(0);
        p.setShareCount(0);
        return p;
    }

    static String escapeLike(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String k = keyword.trim();
        if (k.length() > 50) {
            k = k.substring(0, 50);
        }
        return k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
