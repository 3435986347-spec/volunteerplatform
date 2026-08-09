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
     * 只读生成列，由数据库维护：<b>未删除且未被驳回</b>时等于 {@code violationId}，否则为 NULL。
     *
     * <p>{@code uk_active_violation} 建在它上面：一条现场违规同时最多只有一张有效处罚单，
     * 而软删或<b>被驳回</b>之后应当能重新开单——V35 把「驳回」也加进了释放条件，
     * 此前驳回会永久占位，而系统没有修改/重提入口，{@code reject} 强制填写的原因也就无处落实。
     * {@code RewardPunishService.insertWithNewNo} 的预查必须与本表达式同口径。</p>
     *
     * <p><b>申诉成立的单<u>不</u>释放占位</b>（{@code review_status} 仍是「已通过」，
     * 变的只是 {@code appeal_status}）：那条违规此后开不出第二张单。⚠️ 这与上面对「驳回」的判断
     * 方向相反，是<b>刻意的推论</b>——驳回是这张单<b>没有成立过</b>（开单环节的失误，该允许改正重提），
     * 申诉成立是这张单<b>成立过又被推翻</b>（就同一件事再罚一次等于二次处罚）。
     * 需求原文没有涉及，已记入《协会待确认清单》第 9-附 条。</p>
     */
    @TableField(value = "active_violation_id", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private Long activeViolationId;
}
