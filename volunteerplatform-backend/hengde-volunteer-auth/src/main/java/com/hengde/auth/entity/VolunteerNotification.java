package com.hengde.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 志愿者站内提示（V37）。
 *
 * <p><b>需求出处</b>：xlsx Row 41 F「各类违规记录和奖励均需组织部同学审核才可显示，
 * <b>审核之后，志愿者会收到提示</b>，并有 7 天申诉期」。</p>
 *
 * <p><b>为什么落在 auth 而不是 honor</b>：与 {@link VolunteerSanction} 同一条理由——
 * 写入方会越来越多（本批是 honor 的奖惩审核，将来报名审核在 activity、小组审批在 organization），
 * 放进任一业务域都会让其余域反向依赖它；auth 是各业务模块都已依赖的那一层。</p>
 *
 * <p>⚠️ <b>只是站内提示，没有推送</b>：短信通知类模板要协会另行报备、微信订阅消息要模板 id，
 * 两者都是外部前置。志愿者要打开小程序才看得到。见 V37 迁移抬头。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("volunteer_notification")
public class VolunteerNotification extends BaseEntity {

    // ---------- type ----------

    /** 奖惩单审核通过（Row 41 F 的直接出处） */
    public static final int TYPE_REWARD_PUNISH_APPROVED = 1;

    /**
     * 申诉受理结果。
     *
     * <p>⚠️ <b>这一类是推论、不是需求原文</b>：Row 41 F 只写了「审核之后会收到提示」，
     * 没写申诉有结果之后要不要再提示一次。取这个口径的理由是——申诉是志愿者<b>自己发起</b>的，
     * 让他去反复刷新奖惩记录页面才知道结果，比不做提示更差。协会若另有口径，删这一类即可。</p>
     */
    public static final int TYPE_APPEAL_HANDLED = 2;

    /** 微心愿认领被后台撤销（V3 微心愿批）：认领人要知道心愿回到了心愿池、为什么。V37 列注释只列到 2，以本类为准 */
    public static final int TYPE_WISH_CLAIM_REVOKED = 3;

    /** 认领的微心愿已实现（V3 微心愿批，Row 12 G「给捐赠人反馈物资发放图片」）：发放照片在微心愿中心可看 */
    public static final int TYPE_WISH_REALIZED = 4;

    /** 投诉建议已答复（V4 投诉建议批，Row 43「含处理进度」的最后一步）：短信只放得下答复的开头，全文在这里与工单详情 */
    public static final int TYPE_COMPLAINT_REPLIED = 5;

    /** 社区互动汇总（V4 社区治理批，Row 23 D「点赞、评论等通知每隔20分钟……汇总提示一次」；订阅消息模板报备之前落站内提示，Q13） */
    public static final int TYPE_SOCIAL_INTERACTIONS = 6;

    /** 社区帖子未通过审核（V4 社区治理批）：驳回即对他人隐藏，作者要知道为什么 */
    public static final int TYPE_SOCIAL_POST_REJECTED = 7;

    // ---------- biz_type ----------

    /** 关联奖惩单 {@code honor_reward_punish.id} */
    public static final int BIZ_REWARD_PUNISH = 1;

    /** 关联微心愿认领 {@code donate_wish_claim.id}（V3 微心愿批） */
    public static final int BIZ_WISH_CLAIM = 2;

    /** 关联投诉建议工单 {@code data_complaint.id}（V4 投诉建议批） */
    public static final int BIZ_COMPLAINT = 3;

    /** 关联社区帖子 {@code social_post.id}（V4 社区治理批） */
    public static final int BIZ_SOCIAL_POST = 4;

    // ---------- is_read ----------

    public static final int UNREAD = 0;
    public static final int READ = 1;

    /** 接收人 volunteer.id */
    private Long volunteerId;

    /** 提示类型，见 {@link #TYPE_REWARD_PUNISH_APPROVED} */
    private Integer type;

    /** 标题，列表页直接展示 */
    private String title;

    /** 正文 */
    private String content;

    /** 关联业务类型，见 {@link #BIZ_REWARD_PUNISH}；无关联时为 null */
    private Integer bizType;

    /** 关联业务 id；前端据此跳转详情 */
    private Long bizId;

    /** 0未读/1已读 */
    private Integer isRead;

    /** 读取时间 */
    private java.time.LocalDateTime readTime;
}
