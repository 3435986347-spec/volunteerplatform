package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 奖惩单（V2 第 5 批，V32）。
 *
 * <p><b>需求原文</b>：xlsx Row 41 C「各类违规记录和奖励」、F「各类违规记录和奖励均需组织部同学
 * 审核才可显示，审核之后，志愿者会收到提示，并有 7 天申诉期」；
 * 原型 P109「奖惩记录」给出了完整卡片与详情形态。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_reward_punish")
public class HonorRewardPunish extends BaseEntity {

    /** 奖励 */
    public static final int TYPE_REWARD = 1;
    /** 处罚 */
    public static final int TYPE_PUNISH = 2;

    /** 待审核 */
    public static final int REVIEW_PENDING = 0;
    /** 审核通过——通过后才对志愿者显示，并开始 7 天申诉期 */
    public static final int REVIEW_APPROVED = 1;
    /** 已驳回 */
    public static final int REVIEW_REJECTED = 2;

    /**
     * 已初审、待理事会终审（协会 2026-09-02 答复问题二）。
     *
     * <p><b>这一档对志愿者不可见</b>——「理事会没审完，志愿者不会看到处罚」。
     * 可见性判定一律是 {@code == REVIEW_APPROVED}，故新增这一档天然不外泄，
     * 但<b>新增判定时不要写成 {@code != REVIEW_PENDING}</b>，那会把待终审的单当成已生效。</p>
     *
     * <p>处罚从 {@link #REVIEW_PENDING} 经组织部初审到这里；<b>奖励开单即落在这里</b>
     * （「各部门都可以提出奖励申请，理事会审核」——奖励不经组织部）。</p>
     */
    public static final int REVIEW_FIRST_PASSED = 3;

    /** 未申诉 */
    public static final int APPEAL_NONE = 0;
    /** 申诉中（待受理） */
    public static final int APPEAL_PENDING = 1;
    /** 申诉成立——撤销处置并冲正积分 */
    public static final int APPEAL_UPHELD = 2;
    /** 申诉驳回——维持原处罚 */
    public static final int APPEAL_REJECTED = 3;

    /** 奖惩编号，对外展示（P109「处罚编号：251363568415641」）；列名 {@code rp_no}，与 {@code cert_no} 同一命名习惯 */
    private String rpNo;

    private Long volunteerId;

    /** {@link #TYPE_REWARD} / {@link #TYPE_PUNISH} */
    private Integer type;

    /** 类别：奖励类型 / 违规类型。开放集合，见 V32 迁移注释 */
    private String category;

    /** 标题（P109「推荐评选雷州市优秀共青团员」） */
    private String title;

    /** 说明（P109「您在【xx活动中】因多次玩手机被处罚，请您下次注意」） */
    private String description;

    /** 积分变动：奖励为正、处罚为负、0=不涉及积分（P109「扣除积分：0」） */
    private Integer pointsDelta;

    private Long activityId;

    private Long slotId;

    /** 来源的现场违规 {@code activity_violation.id} */
    private Long violationId;

    /** 处置能力域；null = 仅警告不限制 */
    private Integer sanctionScope;

    /** 限制天数；null 且 scope 非空 = 不设期限 */
    private Integer sanctionDays;

    private Integer reviewStatus;

    private Long reviewedBy;

    /**
     * 初审人（组织部）。<b>{@link #reviewedBy} 此后专指终审人。</b>
     *
     * <p>为 NULL 有两种情形，靠 {@code type} 区分：奖励本就不经初审；
     * 处罚为 NULL 且已通过，说明走的是<b>理事会直接开单</b>那条快捷通道。
     * 「是否自动通过」因此不另存一列——多一列冗余就多一处可能与事实不一致。</p>
     */
    private Long firstReviewBy;

    /** 初审时间。 */
    private LocalDateTime firstReviewTime;

    private LocalDateTime reviewTime;

    private String rejectReason;

    /** 申诉截止 = 审核通过时刻 + 7 天，审核通过时写死 */
    private LocalDateTime appealDeadline;

    private Integer appealStatus;

    private String appealReason;

    private LocalDateTime appealTime;

    private Long appealHandledBy;

    private LocalDateTime appealHandleTime;

    private String appealResult;

    private Long createBy;

    /**
     * 只读生成列，由数据库维护：<b>未删除、未被驳回、且申诉未成立</b>时等于 {@code violationId}，
     * 否则为 NULL。
     *
     * <p>{@code uk_active_violation} 建在它上面：一条现场违规同时最多只有一张有效处罚单，
     * 而软删、<b>被驳回</b>（V35）或<b>申诉成立</b>（V38）之后应当能重新开单。
     * {@code RewardPunishService.insertWithNewNo} 的预查必须与本表达式同口径。</p>
     *
     * <p><b>需求出处</b>：协会 2026-08-11 答复第 8 条「处罚单被驳回或申诉成立后：用户申诉成立
     * 但觉得不惩罚不行，则可以给他开第二张轻一点的处罚单」。</p>
     *
     * <p><b>🔁 V35 时期的相反口径已被推翻</b>。当时刻意让申诉成立的单继续占位，理由是
     * 「驳回 = 这张单没有成立过（该允许改正重提）；申诉成立 = 成立过又被推翻（再罚一次等于二次处罚）」。
     * 那个理由本身讲得通，但它是<b>推论</b>，V35 抬头与《协会待确认清单》第 9-附 条都如实标注了。
     * 协会的口径是：申诉成立只说明<b>这次判罚过重</b>，事实仍在，可按更轻的档重开。
     * 推论让位于裁决——这正是当初把它标成待确认、而不是当成需求写死的原因。</p>
     *
     * <p>⚠️ <b>「第二张必须更轻」没有落成任何机器约束</b>（数据库与 Java 层都没有）：轻重跨类别
     * 不可比——换一个更贴切的违规类别重开，扣分未必更少，但并非加重。硬拦会挡住合理场景。
     * 已作为问题 B 发给协会，当前口径是由理事会人工把关、开单页展示原单档位供对照。
     * 协会若改口要硬拦，加在 {@code RewardPunishService}：那是业务规则，不是数据完整性。</p>
     */
    @TableField(value = "active_violation_id", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private Long activeViolationId;
}
