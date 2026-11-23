package com.hengde.social.service;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialChatCodes;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.dao.SocialBlockMapper;
import com.hengde.social.dao.SocialChatReportMapper;
import com.hengde.social.dao.SocialConversationMapper;
import com.hengde.social.dao.SocialMessageMapper;
import com.hengde.social.dao.SocialUserSettingMapper;
import com.hengde.social.dto.SocialChatDTOs;
import com.hengde.social.entity.SocialChatReport;
import com.hengde.social.entity.SocialConversation;
import com.hengde.social.entity.SocialMessage;
import com.hengde.social.entity.SocialUserSetting;
import com.hengde.social.vo.SocialChatVOs;
import com.hengde.social.vo.SocialVOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 私信（V4 私信批，Row 23「私信：完全参考抖音的私信界面」「私聊：用户双方可以通过主页搭建起聊天通道」）。
 *
 * <p><b>四道门</b>（与社区其余写入同形）：已实名才能发；对方账号要正常且已实名；对方设了「禁止私信」或把我「不让TA看」就发不出去；
 * 处置闸门 {@link SanctionScope#COMMUNITY_CHAT}（禁言只挡发、不挡收——收不到等于把对方的话也一起吞了）。</p>
 *
 * <p><b>陌生人限额</b>（V4规划 Q9）：对方一条都没回之前最多发 {@value SocialChatCodes#STRANGER_LIMIT} 条。
 * 判据是「对方在这条会话里发过几条」，不是关注关系——互关之后又互相取关不该把已经聊着的对话锁上。</p>
 *
 * <p><b>先落库、后推送</b>（V4规划 D8）：推送失败只是「晚看到」，落库失败才是丢消息。推送由
 * {@link SocialChatPushService} 在事务提交之后做，失败只记日志。</p>
 *
 * <p><b>清空聊天记录只对自己</b>：抬高自己那一侧的水位，消息行一条不删——Row 23 F 要求后台保存全部聊天记录，
 * 让志愿者真删就等于把追责依据交给被投诉的人处置。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class SocialChatService {

    private SocialConversationMapper conversationMapper;
    private SocialMessageMapper messageMapper;
    private SocialChatReportMapper reportMapper;
    private SocialUserSettingMapper settingMapper;
    private SocialBlockMapper blockMapper;
    private SocialGateService gate;
    private SocialCardService cardService;
    private SocialKeywordService keywordService;
    private SocialChatPushService pushService;
    private VolunteerQueryService volunteerQueryService;
    private FileStorageService fileStorageService;
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
    public void setSettingMapper(SocialUserSettingMapper settingMapper) {
        this.settingMapper = settingMapper;
    }

    @Autowired
    public void setBlockMapper(SocialBlockMapper blockMapper) {
        this.blockMapper = blockMapper;
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
    public void setKeywordService(SocialKeywordService keywordService) {
        this.keywordService = keywordService;
    }

    @Autowired
    public void setPushService(SocialChatPushService pushService) {
        this.pushService = pushService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================= 志愿者端 =================

    /** 发一条私信。返回消息 id。 */
    public Long send(Long me, Long peerId, SocialChatDTOs.Send dto) {
        gate.requireActor(me);
        if (peerId == null || Objects.equals(me, peerId)) {
            throw new BusinessException("不能给自己发私信");
        }
        requireReachable(me, peerId);
        String content = dto == null || dto.getContent() == null ? null : dto.getContent().trim();
        String image = dto == null || !StringUtils.hasText(dto.getImageUrl()) ? null : dto.getImageUrl().trim();
        if (!StringUtils.hasText(content) && image == null) {
            throw new BusinessException("说点什么，或者发一张图片");
        }
        if (content != null && content.length() > SocialChatCodes.MAX_CONTENT) {
            throw new BusinessException("私信不超过 " + SocialChatCodes.MAX_CONTENT + " 字");
        }
        if (image != null && !fileStorageService.isOwnUpload(image, SocialCodes.DIR_IMAGE)) {
            throw new BusinessException("图片请先通过小程序上传");
        }
        List<String> hits = keywordService.hits(content);
        SocialConversation conversation = openConversation(me, peerId);
        SocialMessage message = transactionTemplate.execute(s -> {
            // 事务第一条就锁住这条会话：陌生人限额是「先数再发」，不串行化的话同时发的几条都数到同一个旧值
            SocialConversation locked = conversationMapper.selectByIdForUpdate(conversation.getId());
            gate.assertNotRestricted(me, SanctionScope.COMMUNITY_CHAT, "发私信");
            requireStrangerQuota(locked, me);
            SocialMessage m = new SocialMessage();
            m.setConversationId(locked.getId());
            m.setSenderId(me);
            m.setReceiverId(peerId);
            m.setContent(content);
            m.setImageUrl(image);
            m.setKeywordHit(hits.isEmpty() ? 0 : 1);
            m.setKeywordHits(hits.isEmpty() ? null : joined(hits));
            m.setCreateTime(LocalDateTime.now().withNano(0));
            messageMapper.insert(m);
            conversationMapper.applySent(locked.getId(), m.getId(), summary(content, image), me,
                    m.getCreateTime(), locked.isSmall(me));
            if (!hits.isEmpty()) {
                // 关键词命中：消息照常送达，另生成一条插队的工单（「自动进入后台插队审核」说的是工单，不是拦截）
                openKeywordTicket(locked.getId(), me, m.getId(), joined(hits));
            }
            return m;
        });
        // 先落库、后推送：这一步失败只是对方晚一点看到（他一拉取就有），业务不受影响
        pushService.pushMessage(peerId, toMessageVO(message, peerId));
        return message.getId();
    }

    /** 我的会话列表（抖音那个私信列表）。 */
    public PageResult<SocialChatVOs.Conversation> conversations(Long me, PageQuery query) {
        gate.requireViewer(me);
        long total = conversationMapper.countMine(me);
        List<SocialConversation> rows = conversationMapper.selectMine(me,
                (long) (query.getPage() - 1) * query.getSize(), query.getSize());
        Set<Long> peers = new HashSet<>();
        rows.forEach(c -> peers.add(c.peerOf(me)));
        Map<Long, SocialVOs.Author> cards = cardService.volunteers(peers);
        List<SocialChatVOs.Conversation> out = new ArrayList<>(rows.size());
        for (SocialConversation c : rows) {
            SocialChatVOs.Conversation vo = new SocialChatVOs.Conversation();
            vo.setId(c.getId());
            vo.setPeerId(c.peerOf(me));
            vo.setPeer(cards.get(c.peerOf(me)));
            vo.setLastContent(c.getLastContent());
            vo.setLastFromMe(Objects.equals(c.getLastSenderId(), me));
            vo.setLastTime(c.getLastTime());
            vo.setUnread(c.unreadOf(me));
            out.add(vo);
        }
        return PageResult.of(out, total, query.getPage(), query.getSize());
    }

    /** 和某个人的消息（新的在前，游标 beforeId 往前翻）。 */
    public List<SocialChatVOs.Message> messages(Long me, Long peerId, Long beforeId, Integer size) {
        gate.requireViewer(me);
        SocialConversation c = findConversation(me, peerId);
        if (c == null) {
            return List.of();
        }
        int limit = size == null || size < 1 || size > 100 ? 20 : size;
        List<SocialMessage> rows = messageMapper.selectForVolunteer(c.getId(), c.clearedIdOf(me), beforeId, limit);
        List<SocialChatVOs.Message> out = new ArrayList<>(rows.size());
        rows.forEach(m -> out.add(toMessageVO(m, me)));
        return out;
    }

    /** 我读了这条会话。 */
    public void read(Long me, Long peerId) {
        SocialConversation c = findConversation(me, peerId);
        if (c != null) {
            conversationMapper.clearUnread(c.getId(), c.isSmall(me));
        }
    }

    /** 清空聊天记录（只对我自己；后台保存的记录一条不少）。 */
    public void clear(Long me, Long peerId) {
        SocialConversation c = findConversation(me, peerId);
        if (c != null) {
            conversationMapper.clearHistory(c.getId(), c.isSmall(me),
                    c.getLastMessageId() == null ? 0L : c.getLastMessageId());
        }
    }

    /** 未读总数（小程序角标）。 */
    public long unreadTotal(Long me) {
        return conversationMapper.totalUnread(me);
    }

    /** 投诉这段私聊（Row 23 F「用户投诉私聊信息时，审核员能看到聊天内容」）。 */
    public Long report(Long me, Long peerId, SocialChatDTOs.Report dto) {
        gate.requireActor(me);
        SocialConversation c = findConversation(me, peerId);
        if (c == null) {
            throw new BusinessException("你们还没有聊过");
        }
        SocialChatReport r = new SocialChatReport();
        r.setSource(SocialChatReport.SOURCE_USER);
        r.setReporterId(me);
        r.setTargetId(peerId);
        r.setConversationId(c.getId());
        r.setReason(dto.getReason().trim());
        r.setStatus(SocialChatReport.PENDING);
        r.setCreateTime(LocalDateTime.now().withNano(0));
        try {
            reportMapper.insert(r);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("你已经投诉过这段对话了，审核员正在处理");
        }
        return r.getId();
    }

    // ================= 给别的服务用 =================

    /** 会话（后台用，按 id）。 */
    public SocialConversation conversation(Long conversationId) {
        return conversationId == null ? null : conversationMapper.selectById(conversationId);
    }

    // ================= 内部 =================

    /** 找到（或建出）这两个人的会话。<b>建会话在事务之外</b>：它与「发得出去吗」无关，撞键就读回赢家那一条。 */
    private SocialConversation openConversation(Long a, Long b) {
        long small = Math.min(a, b);
        long large = Math.max(a, b);
        SocialConversation c = conversationMapper.selectByPair(small, large);
        if (c != null) {
            return c;
        }
        conversationMapper.insertIgnore(small, large);
        c = conversationMapper.selectByPair(small, large);
        if (c == null) {
            throw new BusinessException("会话建立失败，请重试");
        }
        return c;
    }

    private SocialConversation findConversation(Long a, Long b) {
        if (a == null || b == null) {
            return null;
        }
        return conversationMapper.selectByPair(Math.min(a, b), Math.max(a, b));
    }

    /**
     * 对方收不收得到我的私信。
     *
     * <p>「禁止私信」与「不让TA看」<b>报同一句话</b>：分开报等于告诉发信人「他把你拉黑了」。</p>
     */
    private void requireReachable(Long me, Long peerId) {
        if (!volunteerQueryService.filterActiveRegistered(List.of(peerId)).contains(peerId)) {
            throw new BusinessException("对方现在不能接收私信");
        }
        SocialUserSetting setting = settingMapper.selectById(peerId);
        if (setting != null && Integer.valueOf(1).equals(setting.getForbidChat())) {
            throw new BusinessException("对方设置了不接收私信");
        }
        if (blockMapper.exists(peerId, me) > 0) {
            throw new BusinessException("对方设置了不接收私信");
        }
    }

    /**
     * 陌生人限额：对方一条都没回之前，最多发 3 条（Q9）。
     *
     * <p>传进来的会话必须是<b>当前读锁住</b>的那一行（{@code selectByIdForUpdate}）——计数只有在锁里才作数。</p>
     */
    private void requireStrangerQuota(SocialConversation conversation, Long me) {
        Long peer = conversation.peerOf(me);
        if (conversation.sentOf(peer) > 0) {
            return;
        }
        long mine = messageMapper.countBySender(conversation.getId(), me);
        if (mine >= SocialChatCodes.STRANGER_LIMIT) {
            throw new BusinessException("对方还没有回复，先等一等（最多发 " + SocialChatCodes.STRANGER_LIMIT + " 条）");
        }
    }

    /** 关键词工单：同一条消息只开一张（撞唯一键即已有）。 */
    private void openKeywordTicket(Long conversationId, Long senderId, Long messageId, String words) {
        SocialChatReport r = new SocialChatReport();
        r.setSource(SocialChatReport.SOURCE_KEYWORD);
        r.setTargetId(senderId);
        r.setConversationId(conversationId);
        r.setMessageId(messageId);
        r.setReason("命中关键词：" + words);
        r.setStatus(SocialChatReport.PENDING);
        r.setCreateTime(LocalDateTime.now().withNano(0));
        try {
            reportMapper.insert(r);
        } catch (DuplicateKeyException ignored) {
            // 同一条消息的工单已经有了
        }
    }

    private SocialChatVOs.Message toMessageVO(SocialMessage m, Long viewer) {
        SocialChatVOs.Message vo = new SocialChatVOs.Message();
        vo.setId(m.getId());
        vo.setSenderId(m.getSenderId());
        vo.setMine(Objects.equals(m.getSenderId(), viewer));
        vo.setContent(m.getContent());
        vo.setImageUrl(m.getImageUrl());
        vo.setCreateTime(m.getCreateTime());
        return vo;
    }

    private static String summary(String content, String image) {
        if (!StringUtils.hasText(content)) {
            return image == null ? "" : "[图片]";
        }
        String text = content.trim();
        return text.length() <= SocialChatCodes.SUMMARY_CHARS ? text : text.substring(0, SocialChatCodes.SUMMARY_CHARS);
    }

    private static String joined(List<String> hits) {
        String joined = String.join("、", hits);
        return joined.length() > 255 ? joined.substring(0, 255) : joined;
    }
}
