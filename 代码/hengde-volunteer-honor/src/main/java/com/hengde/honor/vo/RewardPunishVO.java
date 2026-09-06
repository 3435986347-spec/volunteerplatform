package com.hengde.honor.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 奖惩记录行（原型 P109 卡片与详情）。
 *
 * @author hengde
 */
@Data
public class RewardPunishVO {

    private Long id;

    /** 奖惩编号（P109「处罚编号：251363568415641」） */
    private String rpNo;

    private Long volunteerId;

    private String volunteerName;

    /** 1奖励/2处罚 */
    private Integer type;

    /** 类别（P109「奖励类型：积极参加活动」「违规类型：违规玩手机」） */
    private String category;

    /** 标题（P109「推荐评选雷州市优秀共青团员」） */
    private String title;

    /** 说明（P109「违规说明」） */
    private String description;

    /** 积分变动（P109「奖励积分：200」「扣除积分：0」） */
    private Integer pointsDelta;

    private Long activityId;

    private Long slotId;

    private Long violationId;

    /** 处置能力域 */
    private Integer sanctionScope;

    /** 处置能力域中文名（P109「限制参加活动」）——服务端给出，前端不必再维护一份映射 */
    private String sanctionScopeLabel;

    /** 限制天数（P109「限制参加活动7天」的那个 7） */
    private Integer sanctionDays;

    private Integer reviewStatus;

    private LocalDateTime reviewTime;

    private String rejectReason;

    /** 0未申诉/1申诉中/2申诉成立/3申诉驳回 */
    private Integer appealStatus;

    /** 申诉截止时刻 */
    private LocalDateTime appealDeadline;

    private String appealReason;

    /** 申诉凭证图片，按提交顺序；没有则为空数组（不是 null，免得客户端还要判空） */
    private List<String> appealImageUrls;

    private String appealResult;

    /**
     * 现在还能不能申诉——由服务端算。
     *
     * <p>条件是「是处罚 + 已审核通过 + 未申诉过 + 未过截止」四条同时成立。
     * 散到前端去拼迟早两端算出不同结果：按钮显示着、点下去报错，或者反过来把还能申诉的人挡住。</p>
     */
    private Boolean appealable;

    private LocalDateTime createTime;
}
