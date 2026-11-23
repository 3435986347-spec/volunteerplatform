package com.hengde.honor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 后台开一张奖惩单。
 *
 * @author hengde
 */
@Data
public class RewardPunishSaveDTO {

    /**
     * 志愿者 id。
     *
     * <p>传了 {@link #violationId} 时<b>以违规记录上的归属为准</b>，本字段被忽略——
     * 否则可以拿甲的违规去罚乙。</p>
     */
    private Long volunteerId;

    @NotNull(message = "请选择奖励或处罚")
    private Integer type;

    /** 类别：奖励类型 / 违规类型。开放集合（P113 类型列表结尾是「........」），由协会自己维护 */
    @NotBlank(message = "类别不能为空")
    @Size(max = 64, message = "类别不超过 64 字")
    private String category;

    /** 标题（P109「推荐评选雷州市优秀共青团员」） */
    @Size(max = 128, message = "标题不超过 128 字")
    private String title;

    /** 说明（P109「您在【xx活动中】因多次玩手机被处罚，请您下次注意」） */
    @Size(max = 1024, message = "说明不超过 1024 字")
    private String description;

    /** 积分变动：奖励为正、处罚为负；不填或 0 = 不涉及积分（P109「扣除积分：0」） */
    private Integer pointsDelta;

    private Long activityId;

    private Long slotId;

    /** 来源的现场违规 id；<b>必须是已通过组织部审核的那条</b> */
    private Long violationId;

    /** 处置能力域 1限制参加活动/2限制发布社区/3拒绝使用本程序；不填 = 仅警告不限制 */
    private Integer sanctionScope;

    /** 限制天数；填了 scope 而不填天数 = 不设期限 */
    private Integer sanctionDays;
}
