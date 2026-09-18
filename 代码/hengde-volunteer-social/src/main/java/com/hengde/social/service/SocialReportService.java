package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.constant.SocialGovCodes;
import com.hengde.social.dao.SocialCommentMapper;
import com.hengde.social.dao.SocialPostMapper;
import com.hengde.social.dao.SocialReportMapper;
import com.hengde.social.dto.SocialGovDTOs;
import com.hengde.social.entity.SocialComment;
import com.hengde.social.entity.SocialPost;
import com.hengde.social.entity.SocialReport;
import com.hengde.social.vo.SocialGovVOs;
import com.hengde.social.vo.SocialVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 举报（Row 23「长按可以删除、举报」「发布人可以长按删除、举报他人评论」，Row 59「举报待审核」）。
 *
 * <p><b>同一个人对同一个对象只能有一条待处理的举报</b>（生成列唯一键，处理完释放）；<b>处理按对象结案</b>——
 * 同一条帖子被十个人举报，成立 / 不成立处理一次，十条一起结。成立可顺带「隐藏」（仅帖子）或「删除」，处置与结案同一事务。</p>
 *
 * @author hengde
 */
@Service
public class SocialReportService {

    private SocialReportMapper reportMapper;
    private SocialPostMapper postMapper;
    private SocialCommentMapper commentMapper;
    private SocialPostService postService;
    private SocialGateService gate;
    private SocialCardService cardService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setReportMapper(SocialReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    @Autowired
    public void setPostMapper(SocialPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Autowired
    public void setCommentMapper(SocialCommentMapper commentMapper) {
        this.commentMapper = commentMapper;
    }

    @Autowired
    public void setPostService(SocialPostService postService) {
        this.postService = postService;
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
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Long report(Long viewer, SocialGovDTOs.ReportSave dto) {
        gate.requireActor(viewer);
        String reason = dto == null || dto.getReason() == null ? "" : dto.getReason().trim();
        if (reason.isEmpty() || reason.length() > 200) {
            throw new BusinessException("请填写举报理由（不超过 200 字）");
        }
        SocialReport r = new SocialReport();
        r.setReporterId(viewer);
        r.setReason(reason);
        r.setStatus(SocialGovCodes.REPORT_PENDING);
        r.setCreateTime(LocalDateTime.now());
        if (Integer.valueOf(SocialGovCodes.REPORT_TARGET_POST).equals(dto.getTargetType())) {
            SocialPost p = postService.requireVisible(viewer, dto.getTargetId());
            if (Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(p.getAuthorType()) && Objects.equals(p.getAuthorId(), viewer)) {
                throw new BusinessException("不能举报自己的帖子");
            }
            r.setPostId(p.getId());
        } else if (Integer.valueOf(SocialGovCodes.REPORT_TARGET_COMMENT).equals(dto.getTargetType())) {
            SocialComment c = dto.getTargetId() == null ? null : commentMapper.selectById(dto.getTargetId());
            if (c == null) {
                throw new BusinessException("评论不存在");
            }
            postService.requireVisible(viewer, c.getPostId());
            if (Integer.valueOf(SocialCodes.AUTHOR_VOLUNTEER).equals(c.getAuthorType()) && Objects.equals(c.getAuthorId(), viewer)) {
                throw new BusinessException("不能举报自己的评论");
            }
            r.setPostId(c.getPostId());
        } else {
            throw new BusinessException("只能举报帖子或评论");
        }
        r.setTargetType(dto.getTargetType());
        r.setTargetId(dto.getTargetId());
        try {
            reportMapper.insert(r);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("你已经举报过了，正在处理中");
        }
        return r.getId();
    }

    public PageResult<SocialGovVOs.Report> list(Integer status, PageQuery query) {
        var wrapper = Wrappers.<SocialReport>lambdaQuery()
                .eq(status != null, SocialReport::getStatus, status)
                .orderByAsc(status != null && status == SocialGovCodes.REPORT_PENDING, SocialReport::getId)
                .orderByDesc(status == null || status != SocialGovCodes.REPORT_PENDING, SocialReport::getId);
        Long total = reportMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialReport> rows = reportMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        Map<Long, SocialVOs.Author> cards = cardService.volunteers(rows.stream().map(SocialReport::getReporterId).toList());
        List<SocialGovVOs.Report> out = rows.stream().map(r -> {
            SocialGovVOs.Report vo = new SocialGovVOs.Report();
            vo.setId(r.getId());
            vo.setReporter(cards.get(r.getReporterId()));
            vo.setTargetType(r.getTargetType());
            vo.setTargetId(r.getTargetId());
            vo.setPostId(r.getPostId());
            vo.setTargetSnippet(snippet(r));
            vo.setReason(r.getReason());
            vo.setStatus(r.getStatus());
            vo.setHandleAction(r.getHandleAction());
            vo.setHandleNote(r.getHandleNote());
            vo.setHandledBy(r.getHandledBy());
            vo.setHandledTime(r.getHandledTime());
            vo.setCreateTime(r.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(out, total == null ? 0 : total, query.getPage(), query.getSize());
    }

    /** 举报成立：这个对象上全部待处理的举报一起结案，按 {@code action} 处置。 */
    public void uphold(Long adminId, Long reportId, SocialGovDTOs.ReportHandle dto) {
        int action = dto == null || dto.getAction() == null ? SocialGovCodes.ACTION_NONE : dto.getAction();
        if (action < SocialGovCodes.ACTION_NONE || action > SocialGovCodes.ACTION_DELETE) {
            throw new BusinessException("不认识的处置方式");
        }
        transactionTemplate.executeWithoutResult(s -> {
            SocialReport r = requirePending(reportId);
            if (action == SocialGovCodes.ACTION_HIDE && r.getTargetType() != SocialGovCodes.REPORT_TARGET_POST) {
                throw new BusinessException("评论不能隐藏，只能删除");
            }
            if (reportMapper.resolveTarget(r.getTargetType(), r.getTargetId(), SocialGovCodes.REPORT_UPHELD, action,
                    note(dto), adminId) == 0) {
                throw new BusinessException("这条举报刚被别人处理了，请刷新");
            }
            if (action == SocialGovCodes.ACTION_HIDE) {
                postMapper.update(null, Wrappers.<SocialPost>lambdaUpdate()
                        .eq(SocialPost::getId, r.getTargetId())
                        .set(SocialPost::getAdminHidden, 1)
                        .set(SocialPost::getUpdateTime, LocalDateTime.now()));
            } else if (action == SocialGovCodes.ACTION_DELETE) {
                if (r.getTargetType() == SocialGovCodes.REPORT_TARGET_POST) {
                    postMapper.softDeleteByAdmin(r.getTargetId(), adminId);
                } else if (commentMapper.softDelete(r.getTargetId(), SocialCodes.AUTHOR_OFFICIAL, adminId) == 1) {
                    postMapper.decComment(r.getPostId());
                }
            }
        });
    }

    /** 举报不成立：这个对象上全部待处理的举报一起结案。 */
    public void dismiss(Long adminId, Long reportId, SocialGovDTOs.ReportHandle dto) {
        transactionTemplate.executeWithoutResult(s -> {
            SocialReport r = requirePending(reportId);
            if (reportMapper.resolveTarget(r.getTargetType(), r.getTargetId(), SocialGovCodes.REPORT_DISMISSED, null,
                    note(dto), adminId) == 0) {
                throw new BusinessException("这条举报刚被别人处理了，请刷新");
            }
        });
    }

    private SocialReport requirePending(Long reportId) {
        SocialReport r = reportId == null ? null : reportMapper.selectById(reportId);
        if (r == null) {
            throw new BusinessException("举报不存在");
        }
        if (!Integer.valueOf(SocialGovCodes.REPORT_PENDING).equals(r.getStatus())) {
            throw new BusinessException("这条举报已经处理过了");
        }
        return r;
    }

    private String snippet(SocialReport r) {
        String text = null;
        if (r.getTargetType() == SocialGovCodes.REPORT_TARGET_POST) {
            SocialPost p = postMapper.selectById(r.getTargetId());
            text = p == null ? null : p.getContent();
        } else {
            SocialComment c = commentMapper.selectById(r.getTargetId());
            text = c == null ? null : c.getContent();
        }
        return text == null ? null : (text.length() > 60 ? text.substring(0, 60) + "…" : text);
    }

    private static String note(SocialGovDTOs.ReportHandle dto) {
        if (dto == null || dto.getNote() == null || dto.getNote().isBlank()) {
            return null;
        }
        String n = dto.getNote().trim();
        if (n.length() > 255) {
            throw new BusinessException("说明不超过 255 字");
        }
        return n;
    }
}
