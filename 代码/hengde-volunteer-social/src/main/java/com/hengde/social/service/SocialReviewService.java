package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.auth.service.NotificationService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.constant.SocialGovCodes;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dao.SocialReviewMapper;
import com.hengde.social.dao.SocialReviewerMapper;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.entity.SocialReviewer;
import com.hengde.social.vo.SocialGovVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子审核（Row 23 F「用户发布帖子后正常显示，但后台显示为待审核阶段，可由后台设置该帖子审核几次，设置审核员」，D7 / Q3）。
 *
 * <p><b>串行 N 级</b>（N 在 {@code social_review_setting}，1~3）：第 k 级只有被设为第 k 级的审核员（或超管）能审；
 * <b>同一个人不能审同一条帖子的两级</b>——多级审核的意义就是多双眼睛，一个人点两下等于一级。
 * 通过写进一条 CAS（条件带「还停在上一级」），两个同级审核员同时点只成一个。</p>
 *
 * <p><b>驳回</b>：任一级都可驳回，驳回即对他人隐藏（本人看得到「未通过」），站内提示作者。</p>
 *
 * <p><b>队列</b>：命中关键词的插队在前，其余先发先审；只列我这一级能审的。</p>
 *
 * @author hengde
 */
@Service
public class SocialReviewService {

    private SocialPostMapper postMapper;
    private SocialReviewMapper reviewMapper;
    private SocialReviewerMapper reviewerMapper;
    private AdminQueryService adminQueryService;
    private NotificationService notificationService;
    private SocialAdminService adminService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Autowired
    public void setReviewMapper(SocialReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Autowired
    public void setReviewerMapper(SocialReviewerMapper reviewerMapper) {
        this.reviewerMapper = reviewerMapper;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Autowired
    public void setAdminService(SocialAdminService adminService) {
        this.adminService = adminService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int levels() {
        Integer n = reviewMapper.selectLevels();
        return n == null ? 1 : n;
    }

    /** 调审核级数；调低之后，已经过够新级数的待审帖直接算通过。 */
    public void setLevels(Long adminId, Integer levels) {
        if (levels == null || levels < 1 || levels > SocialGovCodes.MAX_REVIEW_LEVELS) {
            throw new BusinessException("审核级数只能是 1~" + SocialGovCodes.MAX_REVIEW_LEVELS);
        }
        transactionTemplate.executeWithoutResult(s -> {
            reviewMapper.updateLevels(levels, adminId);
            reviewMapper.completeReachedLevels(levels);
        });
    }

    // ================= 审核员 =================

    public List<SocialGovVOs.Reviewer> reviewers() {
        List<SocialReviewer> rows = reviewerMapper.selectList(Wrappers.<SocialReviewer>lambdaQuery()
                .orderByAsc(SocialReviewer::getLevel).orderByAsc(SocialReviewer::getId));
        Map<Long, String> names = adminQueryService.listNamesByIds(rows.stream().map(SocialReviewer::getAdminUserId).toList());
        return rows.stream().map(r -> {
            SocialGovVOs.Reviewer vo = new SocialGovVOs.Reviewer();
            vo.setId(r.getId());
            vo.setAdminUserId(r.getAdminUserId());
            vo.setAdminName(names.get(r.getAdminUserId()));
            vo.setLevel(r.getLevel());
            vo.setCreateTime(r.getCreateTime());
            return vo;
        }).toList();
    }

    public Long addReviewer(Long operatorId, Long adminUserId, Integer level) {
        if (level == null || level < 1 || level > SocialGovCodes.MAX_REVIEW_LEVELS) {
            throw new BusinessException("审核级别只能是 1~" + SocialGovCodes.MAX_REVIEW_LEVELS);
        }
        if (adminUserId == null || !adminQueryService.listNamesByIds(List.of(adminUserId)).containsKey(adminUserId)) {
            throw new BusinessException("后台账号不存在");
        }
        SocialReviewer r = new SocialReviewer();
        r.setAdminUserId(adminUserId);
        r.setLevel(level);
        r.setCreatedBy(operatorId);
        r.setCreateTime(LocalDateTime.now());
        try {
            reviewerMapper.insert(r);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("这个账号已经是第 " + level + " 级审核员了");
        }
        return r.getId();
    }

    public void removeReviewer(Long id) {
        if (reviewerMapper.deleteById(id) == 0) {
            throw new BusinessException("审核员不存在");
        }
    }

    // ================= 审核 =================

    /**
     * 我这一级能审的待审帖（命中关键词的在前，其余先发先审）。
     *
     * @param superAdmin 超管不必被设为审核员，任何一级都能审
     */
    public PageResult<SocialGovVOs.AdminPost> queue(Long adminId, boolean superAdmin, boolean withRealName, PageQuery query) {
        int n = levels();
        Set<Integer> passedLevels = superAdmin
                ? java.util.stream.IntStream.range(0, n).boxed().collect(Collectors.toSet())
                : myLevels(adminId).stream().filter(l -> l <= n).map(l -> l - 1).collect(Collectors.toSet());
        if (passedLevels.isEmpty()) {
            return PageResult.of(List.of(), 0, query.getPage(), query.getSize());
        }
        var wrapper = Wrappers.<SocialPost>lambdaQuery()
                // 企业帖与志愿者帖走同一条审核线（V4 爱心企业批·社区段）——漏掉 3 的话企业发的帖子没人审得了，
                // 而「先发后审」意味着它们会一直停在待审核、对外永远不出现
                .in(SocialPost::getAuthorType, SocialCodes.AUTHOR_VOLUNTEER, SocialCodes.AUTHOR_ENTERPRISE)
                .eq(SocialPost::getReviewStatus, SocialCodes.REVIEW_PENDING)
                .in(SocialPost::getReviewLevel, passedLevels)
                .orderByDesc(SocialPost::getKeywordHit)
                .orderByAsc(SocialPost::getCreateTime)
                .orderByAsc(SocialPost::getId);
        Long total = postMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialPost> rows = postMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        return PageResult.of(adminService.toAdminPosts(rows, withRealName), total == null ? 0 : total, query.getPage(), query.getSize());
    }

    public void approve(Long adminId, boolean superAdmin, Long postId) {
        transactionTemplate.executeWithoutResult(s -> {
            SocialPost p = requirePending(postId);
            int n = levels();
            int level = p.getReviewLevel() + 1;
            requireReviewerAt(adminId, superAdmin, level);
            if (reviewMapper.countApprovalsBy(postId, adminId) > 0) {
                throw new BusinessException("你已经审过这条帖子的上一级了，这一级要换一个人审");
            }
            int updated = postMapper.update(null, Wrappers.<SocialPost>lambdaUpdate()
                    .eq(SocialPost::getId, postId)
                    .eq(SocialPost::getReviewStatus, SocialCodes.REVIEW_PENDING)
                    .eq(SocialPost::getReviewLevel, level - 1)
                    .set(SocialPost::getReviewLevel, level)
                    .set(SocialPost::getReviewStatus, level >= n ? SocialGovCodes.REVIEW_APPROVED : SocialCodes.REVIEW_PENDING)
                    .set(SocialPost::getUpdateTime, LocalDateTime.now()));
            if (updated == 0) {
                throw new BusinessException("这条帖子刚被别人审过了，请刷新");
            }
            reviewMapper.insertLog(postId, level, adminId, SocialGovCodes.LOG_APPROVE, null);
        });
    }

    public void reject(Long adminId, boolean superAdmin, Long postId, String reason) {
        String r = reason == null ? "" : reason.trim();
        if (!StringUtils.hasText(r) || r.length() > 255) {
            throw new BusinessException("请填写驳回原因（不超过 255 字）");
        }
        transactionTemplate.executeWithoutResult(s -> {
            SocialPost p = requirePending(postId);
            int level = p.getReviewLevel() + 1;
            requireReviewerAt(adminId, superAdmin, level);
            int updated = postMapper.update(null, Wrappers.<SocialPost>lambdaUpdate()
                    .eq(SocialPost::getId, postId)
                    .eq(SocialPost::getReviewStatus, SocialCodes.REVIEW_PENDING)
                    .eq(SocialPost::getReviewLevel, level - 1)
                    .set(SocialPost::getReviewStatus, SocialGovCodes.REVIEW_REJECTED)
                    .set(SocialPost::getUpdateTime, LocalDateTime.now()));
            if (updated == 0) {
                throw new BusinessException("这条帖子刚被别人审过了，请刷新");
            }
            reviewMapper.insertLog(postId, level, adminId, SocialGovCodes.LOG_REJECT, r);
            // 企业帖没有站内提示的收件箱（作者是企业账号不是志愿者）——不加这一层会把提示写给「id 恰好相同的那个志愿者」
            if (Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType())) {
                notificationService.notify(p.getAuthorId(), VolunteerNotification.TYPE_SOCIAL_POST_REJECTED, "帖子未通过审核",
                        "你发布的帖子未通过审核，已对他人隐藏。原因：" + r, VolunteerNotification.BIZ_SOCIAL_POST, postId);
            }
        });
    }

    private SocialPost requirePending(Long postId) {
        SocialPost p = postId == null ? null : postMapper.selectById(postId);
        // 官方帖不进审核；志愿者帖与企业帖都进
        if (p == null || Integer.valueOf(SocialCodes.AUTHOR_OFFICIAL).equals(p.getAuthorType())) {
            throw new BusinessException("帖子不存在");
        }
        if (!Integer.valueOf(SocialCodes.REVIEW_PENDING).equals(p.getReviewStatus())) {
            throw new BusinessException("这条帖子已经审核完了（" + SocialGovCodes.reviewLabel(p.getReviewStatus()) + "）");
        }
        return p;
    }

    private void requireReviewerAt(Long adminId, boolean superAdmin, int level) {
        if (superAdmin) {
            return;
        }
        if (!myLevels(adminId).contains(level)) {
            throw new BusinessException("这条帖子正在等第 " + level + " 级审核，你不是这一级的审核员");
        }
    }

    private Set<Integer> myLevels(Long adminId) {
        return reviewerMapper.selectList(Wrappers.<SocialReviewer>lambdaQuery().eq(SocialReviewer::getAdminUserId, adminId))
                .stream().map(SocialReviewer::getLevel).collect(Collectors.toSet());
    }
}
