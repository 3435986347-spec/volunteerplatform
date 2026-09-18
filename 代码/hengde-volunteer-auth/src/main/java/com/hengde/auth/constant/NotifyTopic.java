package com.hengde.auth.constant;

import com.hengde.common.sms.SmsNotifyTemplate;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 订阅通知的话题（Row 48「订阅通知：短信和通知提醒，用户可自主选择关闭或打开某个内容的提醒」，V4 个人中心补全批）。
 *
 * <p><b>一条短信模板属于且只属于一个话题</b>（{@link #of}），新增模板时必须在这里归类——没归类的一律当「不可关闭」处理，
 * 宁可多打扰一条，不能让一条要紧的提醒因为漏归类被静默吞掉。</p>
 *
 * <p><b>不可关闭</b>的是「不知道就会吃亏」的提醒：奖惩与违规（申诉期从通过那一刻起算）、活动取消（不知道就白跑一趟）。
 * 用户存了关闭也不生效（V4规划 承重条款 6：偏好在 {@code SmsNotifyService} 统一过滤，不让业务调用方各自判断）。
 * 验证码不走通知通道，本来就不受偏好影响。</p>
 *
 * @author hengde
 */
public enum NotifyTopic {

    ENROLLMENT("报名审核结果与代报名", true,
            SmsNotifyTemplate.ENROLLMENT_APPROVED, SmsNotifyTemplate.ENROLLMENT_REJECTED,
            SmsNotifyTemplate.ENROLLMENT_POSITION),
    ACTIVITY_REMINDER("活动开始与评价提醒", true,
            SmsNotifyTemplate.ACTIVITY_START_REMINDER, SmsNotifyTemplate.ACTIVITY_COMMENT_REMINDER),
    ACTIVITY_CANCELLED("活动取消", false,
            SmsNotifyTemplate.ACTIVITY_CANCELLED),
    POINTS("服务时长积分与兑换审核", true,
            SmsNotifyTemplate.SERVICE_RECORD_CREDITED, SmsNotifyTemplate.POINTS_ORDER_REVIEW),
    REWARD_PUNISH("奖惩与违规记录", false,
            SmsNotifyTemplate.REWARD_PUNISH, SmsNotifyTemplate.ACTIVITY_VIOLATION),
    ORGANIZATION("分队 / 小组 / 管理团队申请结果", true,
            SmsNotifyTemplate.ORG_JOIN_RESULT, SmsNotifyTemplate.GROUP_JOIN_RESULT),
    COMPLAINT("投诉建议答复", true,
            SmsNotifyTemplate.COMPLAINT_REPLIED),
    OTHER("其他通知", false,
            SmsNotifyTemplate.VOLUNTEER_APPLY_SUBMITTED, SmsNotifyTemplate.VOLUNTEER_APPLY_APPROVED,
            SmsNotifyTemplate.VOLUNTEER_APPLY_REJECTED, SmsNotifyTemplate.RECRUIT_REVIEW_RESULT,
            SmsNotifyTemplate.RECRUIT_ENROLLED, SmsNotifyTemplate.LEAVE_APPROVED, SmsNotifyTemplate.LEAVE_REJECTED);

    private final String label;
    private final boolean optional;
    private final Set<SmsNotifyTemplate> templates;

    NotifyTopic(String label, boolean optional, SmsNotifyTemplate... templates) {
        this.label = label;
        this.optional = optional;
        this.templates = templates.length == 0 ? EnumSet.noneOf(SmsNotifyTemplate.class)
                : EnumSet.copyOf(Arrays.asList(templates));
    }

    public String getLabel() {
        return label;
    }

    /** 用户能不能关。 */
    public boolean isOptional() {
        return optional;
    }

    public Set<SmsNotifyTemplate> getTemplates() {
        return templates;
    }

    /** 模板属于哪个话题；没归类的当作 {@link #OTHER}（不可关闭）。 */
    public static NotifyTopic of(SmsNotifyTemplate template) {
        for (NotifyTopic t : values()) {
            if (t.templates.contains(template)) {
                return t;
            }
        }
        return OTHER;
    }

    /** 按名字取；不认识返回 null。 */
    public static NotifyTopic fromName(String name) {
        for (NotifyTopic t : values()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }
}
