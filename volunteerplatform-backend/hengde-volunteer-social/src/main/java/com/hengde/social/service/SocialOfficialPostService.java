package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.vo.SocialVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 官方帖（Row 23「官方：由各部分负责人在后台发布，前端显示报名或者分队名称，可以自由删除、回复、评论。宣传部可以删除其他部门发的帖子」）。
 *
 * <p><b>部门就是后台账号上的 {@code department} 那段文字</b>（与投诉建议批同一口径），发帖时快照在帖子上；
 * 删帖只能删本部门发的，持有 {@code social:official-all}（默认给宣传部）才能删别的部门的——由控制器按权限把部门传成 null。</p>
 *
 * @author hengde
 */
@Service
public class SocialOfficialPostService {

    private SocialPostMapper postMapper;
    private SocialMediaService mediaService;
    private AdminQueryService adminQueryService;

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Autowired
    public void setMediaService(SocialMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    public Long publish(Long adminId, SocialDTOs.OfficialPostSave dto) {
        String department = adminQueryService.departmentOf(adminId);
        if (!StringUtils.hasText(department)) {
            throw new BusinessException("后台账号没有填写部门，不能发官方帖");
        }
        if (dto == null) {
            throw new BusinessException("请填写帖子内容");
        }
        SocialMediaService.Media media = mediaService.validate(dto.getContent(), dto.getImageUrls(), dto.getVideoUrl());
        String label = StringUtils.hasText(dto.getLabel()) ? dto.getLabel().trim() : null;
        if (label != null && label.length() > SocialCodes.MAX_LABEL) {
            throw new BusinessException("名称不超过 " + SocialCodes.MAX_LABEL + " 字");
        }
        SocialPost p = new SocialPost();
        p.setAuthorType(SocialCodes.AUTHOR_OFFICIAL);
        p.setAuthorId(adminId);
        p.setOfficialDepartment(department.trim());
        p.setOfficialLabel(label);
        p.setContent(dto.getContent() == null ? "" : dto.getContent().trim());
        p.setMediaType(media.type());
        p.setMediaUrls(mediaService.toJson(media));
        p.setVisibility(SocialCodes.VISIBLE_ALL);
        p.setAllowComment(1);
        p.setAllowLike(1);
        // 官方帖由后台账号发，不进审核队列
        p.setReviewStatus(com.hengde.social.constant.SocialGovCodes.REVIEW_APPROVED);
        p.setReviewLevel(0);
        p.setKeywordHit(0);
        p.setAdminHidden(0);
        p.setPinned(0);
        p.setViewCount(0);
        p.setLikeCount(0);
        p.setCommentCount(0);
        p.setShareCount(0);
        postMapper.insert(p);
        return p.getId();
    }

    /** @param department 发帖人所在部门；持有全部门权限时传 null */
    public void delete(Long adminId, String department, Long postId) {
        if (department == null) {
            if (postMapper.softDeleteOfficial(postId, adminId, null) == 0) {
                throw new BusinessException("官方帖不存在");
            }
            return;
        }
        if (!StringUtils.hasText(department) || postMapper.softDeleteOfficial(postId, adminId, department.trim()) == 0) {
            throw new BusinessException("官方帖不存在，或不是本部门发的");
        }
    }

    public PageResult<SocialVOs.OfficialPost> list(String department, PageQuery query) {
        var wrapper = Wrappers.<SocialPost>lambdaQuery()
                .eq(SocialPost::getAuthorType, SocialCodes.AUTHOR_OFFICIAL)
                .eq(StringUtils.hasText(department), SocialPost::getOfficialDepartment, department)
                .orderByDesc(SocialPost::getCreateTime).orderByDesc(SocialPost::getId);
        Long total = postMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialPost> rows = postMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        Map<Long, String> names = adminQueryService.listNamesByIds(rows.stream().map(SocialPost::getAuthorId).toList());
        List<SocialVOs.OfficialPost> out = rows.stream().map(p -> {
            SocialVOs.OfficialPost vo = new SocialVOs.OfficialPost();
            vo.setId(p.getId());
            vo.setDepartment(p.getOfficialDepartment());
            vo.setLabel(p.getOfficialLabel());
            vo.setContent(p.getContent());
            vo.setMediaType(p.getMediaType());
            vo.setMediaUrls(mediaService.fromJson(p.getMediaUrls()));
            vo.setViewCount(p.getViewCount());
            vo.setLikeCount(p.getLikeCount());
            vo.setCommentCount(p.getCommentCount());
            vo.setShareCount(p.getShareCount());
            vo.setAuthorAdminId(p.getAuthorId());
            vo.setAuthorAdminName(names.get(p.getAuthorId()));
            vo.setCreateTime(p.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(out, total == null ? 0 : total, query.getPage(), query.getSize());
    }
}
