package com.hengde.social.service;

import com.hengde.auth.vo.VolunteerSocialCardView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import org.redisson.api.RedissonClient;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.honor.service.MedalGrantService;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.dao.SocialBlockMapper;
import com.hengde.social.dao.SocialFollowMapper;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dao.SocialUserSettingMapper;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.entity.SocialUserSetting;
import com.hengde.social.vo.SocialVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 关注 / 主页 / 主页设置 / 不让TA看（Row 23「TA的主页」「自己主页」「首页可以设置」）。
 *
 * <p><b>主页上的数都按行现算、不存列</b>（发帖量 / 粉丝量 / 关注量）——存列就有两个口径。发帖量只数<b>看的人看得到的</b>，
 * 否则隐藏的帖子会从数字上露出来。</p>
 *
 * <p>「@TA（需要对方关注）」与「互动信息」依赖互动通知，属社区治理批；本批不做。</p>
 *
 * @author hengde
 */
@Service
public class SocialUserService {

    private SocialFollowMapper followMapper;
    private SocialBlockMapper blockMapper;
    private SocialPostMapper postMapper;
    private SocialUserSettingMapper settingMapper;
    private SocialGateService gate;
    private SocialCardService cardService;
    private MedalGrantService medalGrantService;
    private RedissonClient redissonClient;
    private SocialInteractionFeedService feedService;

    @Autowired
    public void setFeedService(SocialInteractionFeedService feedService) {
        this.feedService = feedService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setFollowMapper(SocialFollowMapper followMapper) {
        this.followMapper = followMapper;
    }

    @Autowired
    public void setBlockMapper(SocialBlockMapper blockMapper) {
        this.blockMapper = blockMapper;
    }

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
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
    public void setMedalGrantService(MedalGrantService medalGrantService) {
        this.medalGrantService = medalGrantService;
    }

    // ================= 关注 =================

    /** @return true＝这次关注上了；false＝之前已经关注（不报错） */
    public boolean follow(Long viewer, Long targetId) {
        gate.requireActor(viewer);
        if (Objects.equals(viewer, targetId)) {
            throw new BusinessException("不能关注自己");
        }
        cardService.requireCard(targetId);
        SocialUserSetting setting = settingMapper.selectById(targetId);
        if (setting != null && Integer.valueOf(1).equals(setting.getForbidFollow())) {
            throw new BusinessException("TA 设置了禁止关注");
        }
        // 同一个人连点「关注 / 取关」与连点赞同形：按人上锁，理由见 SocialInteractionService
        return DistributedLockSupport.runLocked(redissonClient, SocialInteractionService.LOCK_PREFIX + viewer, () -> {
            try {
                if (followMapper.insert(viewer, targetId) != 1) {
                    return false;
                }
            } catch (DuplicateKeyException e) {
                return false;
            }
            feedService.followed(viewer, targetId);
            return true;
        });
    }

    public boolean unfollow(Long viewer, Long targetId) {
        return DistributedLockSupport.runLocked(redissonClient, SocialInteractionService.LOCK_PREFIX + viewer,
                () -> followMapper.delete(viewer, targetId) == 1);
    }

    public PageResult<SocialVOs.UserCard> followers(Long viewer, Long userId, PageQuery query) {
        gate.requireViewer(viewer);
        cardService.requireCard(userId);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        return PageResult.of(cards(viewer, followMapper.selectFollowerIds(userId, offset, query.getSize())),
                followMapper.countFollowers(userId), query.getPage(), query.getSize());
    }

    public PageResult<SocialVOs.UserCard> following(Long viewer, Long userId, PageQuery query) {
        gate.requireViewer(viewer);
        cardService.requireCard(userId);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        return PageResult.of(cards(viewer, followMapper.selectFollowingIds(userId, offset, query.getSize())),
                followMapper.countFollowing(userId), query.getPage(), query.getSize());
    }

    // ================= 主页 =================

    public SocialVOs.Profile profile(Long viewer, Long userId) {
        gate.requireViewer(viewer);
        VolunteerSocialCardView card = cardService.requireCard(userId);
        SocialVOs.Author author = SocialCardService.toAuthor(userId, card);
        SocialUserSetting setting = settingMapper.selectById(userId);
        SocialVOs.Profile vo = new SocialVOs.Profile();
        vo.setVolunteerId(userId);
        vo.setName(author.getName());
        vo.setAvatarUrl(author.getAvatarUrl());
        vo.setRegisterTime(card.registerTime());
        vo.setBio(setting == null ? null : setting.getBio());
        medalGrantService.myMedals(userId).stream()
                .filter(m -> Boolean.TRUE.equals(m.getOwned()))
                .forEach(m -> {
                    SocialVOs.Medal medal = new SocialVOs.Medal();
                    medal.setMedalId(m.getMedalId());
                    medal.setName(m.getName());
                    medal.setIconUrl(m.getIconUrl());
                    vo.getMedals().add(medal);
                });
        vo.setPostCount(postMapper.countVisible(viewer, null, userId, null, null));
        vo.setFollowerCount(followMapper.countFollowers(userId));
        vo.setFollowingCount(followMapper.countFollowing(userId));
        vo.setLikeCount(postMapper.sumLikesOfAuthor(userId));
        vo.setSelf(Objects.equals(viewer, userId));
        if (!vo.isSelf()) {
            vo.setFollowedByMe(followMapper.exists(viewer, userId) > 0);
            vo.setFollowsMe(followMapper.exists(userId, viewer) > 0);
            vo.setBlockedByMe(blockMapper.exists(viewer, userId) > 0);
        }
        vo.setFollowForbidden(setting != null && Integer.valueOf(1).equals(setting.getForbidFollow()));
        return vo;
    }

    // ================= 设置 =================

    public SocialVOs.Setting mySetting(Long viewer) {
        SocialUserSetting s = settingMapper.selectById(viewer);
        SocialVOs.Setting vo = new SocialVOs.Setting();
        if (s != null) {
            vo.setForbidFollow(Integer.valueOf(1).equals(s.getForbidFollow()));
            vo.setForbidComment(Integer.valueOf(1).equals(s.getForbidComment()));
            vo.setForbidLike(Integer.valueOf(1).equals(s.getForbidLike()));
            vo.setForbidChat(Integer.valueOf(1).equals(s.getForbidChat()));
            vo.setBio(s.getBio());
        }
        return vo;
    }

    /** 整份提交：没传的开关按「关」处理，备注传空即清空。 */
    public void saveSetting(Long viewer, SocialDTOs.SettingSave dto) {
        gate.requireActor(viewer);
        String bio = dto == null || !StringUtils.hasText(dto.getBio()) ? null : dto.getBio().trim();
        if (bio != null && bio.length() > SocialCodes.MAX_BIO) {
            throw new BusinessException("备注不超过 " + SocialCodes.MAX_BIO + " 字");
        }
        SocialUserSetting s = new SocialUserSetting();
        s.setVolunteerId(viewer);
        s.setForbidFollow(dto != null && Boolean.TRUE.equals(dto.getForbidFollow()) ? 1 : 0);
        s.setForbidComment(dto != null && Boolean.TRUE.equals(dto.getForbidComment()) ? 1 : 0);
        s.setForbidLike(dto != null && Boolean.TRUE.equals(dto.getForbidLike()) ? 1 : 0);
        s.setForbidChat(dto != null && Boolean.TRUE.equals(dto.getForbidChat()) ? 1 : 0);
        s.setBio(bio);
        settingMapper.upsert(s);
    }

    // ================= 不让TA看 =================

    public void block(Long viewer, Long targetId) {
        gate.requireActor(viewer);
        if (Objects.equals(viewer, targetId)) {
            throw new BusinessException("不能对自己设置");
        }
        cardService.requireCard(targetId);
        DistributedLockSupport.runLocked(redissonClient, SocialInteractionService.LOCK_PREFIX + viewer, () -> {
            try {
                blockMapper.insert(viewer, targetId);
            } catch (DuplicateKeyException ignored) {
                // 已经设置过
            }
            return null;
        });
    }

    public void unblock(Long viewer, Long targetId) {
        DistributedLockSupport.runLocked(redissonClient, SocialInteractionService.LOCK_PREFIX + viewer,
                () -> blockMapper.delete(viewer, targetId));
    }

    public List<SocialVOs.UserCard> blocks(Long viewer) {
        return cards(viewer, blockMapper.selectTargets(viewer));
    }

    private List<SocialVOs.UserCard> cards(Long viewer, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, SocialVOs.Author> authors = cardService.volunteers(ids);
        Set<Long> followed = new HashSet<>(followMapper.selectFollowedAmong(viewer, ids));
        return ids.stream().map(id -> {
            SocialVOs.Author a = authors.get(id);
            SocialVOs.UserCard c = new SocialVOs.UserCard();
            c.setVolunteerId(id);
            c.setName(a == null ? null : a.getName());
            c.setAvatarUrl(a == null ? null : a.getAvatarUrl());
            c.setFollowedByMe(followed.contains(id));
            return c;
        }).toList();
    }
}
