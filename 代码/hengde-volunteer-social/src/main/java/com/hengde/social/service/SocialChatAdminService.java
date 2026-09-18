package com.hengde.social.service;

import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerSocialCardView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialChatCodes;
import com.hengde.social.dao.SocialChatReportMapper;
import com.hengde.social.dao.SocialConversationMapper;
import com.hengde.social.dao.SocialMessageMapper;
import com.hengde.social.dto.SocialChatDTOs;
import com.hengde.social.entity.SocialChatReport;
import com.hengde.social.entity.SocialConversation;
import com.hengde.social.entity.SocialMessage;
import com.hengde.social.vo.SocialChatVOs;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台的聊天记录与私聊工单（V4 私信批，Row 23 F）。
 *
 * <p><b>后台看到的是全部</b>：双方各自「清空聊天记录」的水位对这里不生效，被删除的消息也照样列出来（标成已删除）——
 * 这张表存在的理由就是追责，按志愿者那一侧的可见性去过滤，等于让被投诉的人决定审核员能看到什么。</p>
 *
 * <p>入口全挂 {@code social:chat-view}，默认不授任何人（超管通配）。</p>
 *
 * @author hengde
 */
@Service
public class SocialChatAdminService {

    private SocialConversationMapper conversationMapper;
    private SocialMessageMapper messageMapper;
    private SocialChatReportMapper reportMapper;
    private VolunteerQueryService volunteerQueryService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setConversationMapper(SocialConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    @Autowired
    public void setMessageMapper(SocialMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Autowired
    public void setReportMapper(SocialChatReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 会话列表（可按某个志愿者筛）。 */
    public PageResult<SocialChatVOs.AdminConversation> conversations(Long volunteerId, PageQuery query) {
        long total = conversationMapper.countForAdmin(volunteerId);
        List<SocialConversation> rows = conversationMapper.selectForAdmin(volunteerId,
                (long) (query.getPage() - 1) * query.getSize(), query.getSize());
        Set<Long> ids = new HashSet<>();
        rows.forEach(c -> {
            ids.add(c.getSmallId());
            ids.add(c.getLargeId());
        });
        Map<Long, VolunteerSocialCardView> cards = ids.isEmpty() ? Map.of() : volunteerQueryService.listSocialCardsByIds(ids);
        List<SocialChatVOs.AdminConversation> out = new ArrayList<>(rows.size());
        for (SocialConversation c : rows) {
            SocialChatVOs.AdminConversation vo = new SocialChatVOs.AdminConversation();
            vo.setId(c.getId());
            vo.setSmallId(c.getSmallId());
            vo.setLargeId(c.getLargeId());
            vo.setSmallName(nameOf(cards, c.getSmallId()));
            vo.setLargeName(nameOf(cards, c.getLargeId()));
            vo.setLastContent(c.getLastContent());
            vo.setLastTime(c.getLastTime());
            vo.setMessageCount(messageMapper.countForAdmin(c.getId()));
            out.add(vo);
        }
        return PageResult.of(out, total, query.getPage(), query.getSize());
    }

    /** 一条会话的全部消息（新的在前）。 */
    public PageResult<SocialChatVOs.AdminMessage> messages(Long conversationId, PageQuery query) {
        SocialConversation c = conversationMapper.selectById(conversationId);
        if (c == null) {
            throw new BusinessException("会话不存在");
        }
        long total = messageMapper.countForAdmin(conversationId);
        List<SocialMessage> rows = messageMapper.selectForAdmin(conversationId,
                (long) (query.getPage() - 1) * query.getSize(), query.getSize());
        Map<Long, VolunteerSocialCardView> cards = volunteerQueryService.listSocialCardsByIds(
                List.of(c.getSmallId(), c.getLargeId()));
        List<SocialChatVOs.AdminMessage> out = new ArrayList<>(rows.size());
        for (SocialMessage m : rows) {
            SocialChatVOs.AdminMessage vo = new SocialChatVOs.AdminMessage();
            vo.setId(m.getId());
            vo.setSenderId(m.getSenderId());
            vo.setSenderName(nameOf(cards, m.getSenderId()));
            vo.setContent(m.getContent());
            vo.setImageUrl(m.getImageUrl());
            vo.setKeywordHit(Integer.valueOf(1).equals(m.getKeywordHit()));
            vo.setKeywordHits(m.getKeywordHits());
            vo.setDeleted(Integer.valueOf(1).equals(m.getIsDeleted()));
            vo.setCreateTime(m.getCreateTime());
            out.add(vo);
        }
        return PageResult.of(out, total, query.getPage(), query.getSize());
    }

    /** 删一条违规消息（双方都看不到；记录仍在，后台照样列得出来）。 */
    public void deleteMessage(Long messageId, Long adminId) {
        requireOperator(adminId);
        if (messageId == null || messageMapper.softDelete(messageId) != 1) {
            throw new BusinessException("消息不存在或已删除");
        }
    }

    /** 工单队列（关键词命中的插队在前）。 */
    public PageResult<SocialChatVOs.ChatReport> reports(Integer status, PageQuery query) {
        long total = reportMapper.countQueue(status);
        List<SocialChatReport> rows = reportMapper.selectQueue(status,
                (long) (query.getPage() - 1) * query.getSize(), query.getSize());
        Set<Long> ids = new HashSet<>();
        rows.forEach(r -> {
            ids.add(r.getTargetId());
            if (r.getReporterId() != null) {
                ids.add(r.getReporterId());
            }
        });
        Map<Long, VolunteerSocialCardView> cards = ids.isEmpty() ? Map.of() : volunteerQueryService.listSocialCardsByIds(ids);
        List<SocialChatVOs.ChatReport> out = new ArrayList<>(rows.size());
        for (SocialChatReport r : rows) {
            SocialChatVOs.ChatReport vo = new SocialChatVOs.ChatReport();
            vo.setId(r.getId());
            vo.setSource(r.getSource());
            vo.setSourceLabel(SocialChatCodes.reportSourceLabel(r.getSource()));
            vo.setConversationId(r.getConversationId());
            vo.setReporterId(r.getReporterId());
            vo.setReporterName(r.getReporterId() == null ? null : nameOf(cards, r.getReporterId()));
            vo.setTargetId(r.getTargetId());
            vo.setTargetName(nameOf(cards, r.getTargetId()));
            vo.setMessageId(r.getMessageId());
            vo.setReason(r.getReason());
            vo.setStatus(r.getStatus());
            vo.setStatusLabel(SocialChatCodes.reportStatusLabel(r.getStatus()));
            vo.setHandleNote(r.getHandleNote());
            vo.setHandledTime(r.getHandledTime());
            vo.setCreateTime(r.getCreateTime());
            out.add(vo);
        }
        return PageResult.of(out, total, query.getPage(), query.getSize());
    }

    /**
     * 处理工单：成立 / 不成立，成立时可顺带删掉被投诉的那条消息。
     *
     * <p><b>是一条 CAS</b>（{@code WHERE status = 待处理}）：两个审核员同时点，只成一个，另一个被告知刚被处理过。
     * <b>禁言不在这里做</b>——处置是奖惩域的动作，走 {@code POST /a/social/bans}，
     * 否则「处理投诉」这一个按钮会同时产生审核结论与一条处罚，出了错分不清是谁的判断。</p>
     */
    public void handle(Long reportId, SocialChatDTOs.Handle dto, Long adminId) {
        requireOperator(adminId);
        String note = dto == null || !StringUtils.hasText(dto.getNote()) ? null : dto.getNote().trim();
        boolean valid = dto != null && Boolean.TRUE.equals(dto.getValid());
        transactionTemplate.executeWithoutResult(s -> {
            SocialChatReport r = reportMapper.selectById(reportId);
            if (r == null) {
                throw new BusinessException("工单不存在");
            }
            int rows = reportMapper.update(null, Wrappers.<SocialChatReport>lambdaUpdate()
                    .eq(SocialChatReport::getId, reportId)
                    .eq(SocialChatReport::getStatus, SocialChatReport.PENDING)
                    .set(SocialChatReport::getStatus, valid ? SocialChatReport.VALID : SocialChatReport.INVALID)
                    .set(SocialChatReport::getHandleNote, note)
                    .set(SocialChatReport::getHandledBy, adminId)
                    .set(SocialChatReport::getHandledTime, LocalDateTime.now().withNano(0)));
            if (rows != 1) {
                throw new BusinessException("这条工单刚被别人处理过了，请刷新");
            }
            if (valid && Boolean.TRUE.equals(dto.getDeleteMessage()) && r.getMessageId() != null) {
                messageMapper.softDelete(r.getMessageId());
            }
        });
    }

    private static String nameOf(Map<Long, VolunteerSocialCardView> cards, Long id) {
        VolunteerSocialCardView v = id == null ? null : cards.get(id);
        return v == null || !StringUtils.hasText(v.nickName()) ? ("志愿者" + (id == null ? "" : id)) : v.nickName();
    }

    private static void requireOperator(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }
}
