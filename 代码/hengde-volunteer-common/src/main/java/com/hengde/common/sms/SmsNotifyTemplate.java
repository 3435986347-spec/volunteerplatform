package com.hengde.common.sms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知类短信模板（协会 2026-08-11 报备，火山控制台已上线）。
 *
 * <p><b>本枚举是「模板键 ↔ 变量名 ↔ 变量顺序」的唯一来源。</b>此前这三样东西只以注释形式
 * 写在 {@code application-local.yaml} 里，调用方组参数时要照着抄——而火山模板的占位名
 * <b>大小写敏感、逐字匹配，传错一个键不会报错，只会把那一段替换成空</b>：短信照发、内容缺一块、
 * 没有任何征兆。把它搬进代码并配 {@link #params(String...)} 之后，调用方按<b>声明顺序</b>传值即可，
 * 拼错键这一整类问题消失。</p>
 *
 * <p><b>枚举值覆盖全部 20 条已报备模板，但只有一部分真的有调用方</b>——见每一条的「落点」。
 * 没有落点的那 9 条对应的功能尚未开发（或流程上不存在），<b>先留着不接</b>：
 * 配着不用是无害的，而等做那个功能时再回头找模板容易漏配或配错。</p>
 *
 * <p>验证码类模板不在这里，它们按场景值取键，见 {@link SmsScene}。</p>
 *
 * @author hengde
 */
public enum SmsNotifyTemplate {

    // ==================== 活动报名 ====================

    /**
     * 「您已成功报名${activityName}，活动时间：${startTime}，地点：${location}。请准时参加。」
     *
     * <p>落点两处：管理端报名审核通过（{@code EnrollmentAdminService#approve}）、
     * 与<b>后台补录报名</b>（{@code EnrollmentService#manualEnroll}——补录直接落「已通过」，
     * 用这条模板而不是代报名那条）。</p>
     *
     * <p><b>自助报名刻意不发</b>：自己刚点完的事不必再花一条短信告诉他。上面两处都是
     * <b>别人替他做的决定</b>，他事先并不知情。</p>
     */
    ENROLLMENT_APPROVED("enrollment-approved", "activityName", "startTime", "location"),

    /**
     * 「您报名的${activityName}审核未通过，原因：${reason}。」
     *
     * <p>落点：管理端报名审核拒绝（{@code EnrollmentAdminService#reject}）。</p>
     */
    ENROLLMENT_REJECTED("enrollment-rejected", "activityName", "reason"),

    /**
     * 「尊敬的志愿者您好，您报名 “${Activity}” 活动的 “${Position}” 岗位：${message}。」
     *
     * <p><b>⚠️ 这一条的变量名首字母大写</b>（{@code Activity} / {@code Position}），与其余模板不一致，
     * 是控制台里就那样报备的，勿套用到别处——也正是本枚举存在的理由。</p>
     *
     * <p>落点：<b>同小组代报名</b>（{@code EnrollmentService#proxyEnroll}）。
     * 只接代报名、不接本人自助报名：自己刚点完的事不必再发一条短信告诉他，
     * 而<b>被别人报上的名，他事先并不知情</b>，不通知就可能到了活动当天才发现。</p>
     */
    ENROLLMENT_POSITION("enrollment-position", "Activity", "Position", "message"),

    // ==================== 活动进程 ====================

    /**
     * 「您报名的${activityName}将于${startTime}开始，地点：${location}，请携带证件准时签到。」
     *
     * <p>落点：定时任务 {@code ActivityStartReminderJob}（按<b>场次</b>提醒，
     * 场次是参与的最小单元；已提醒过的场次由 {@code activity_slot.reminder_sent_time} 记住，不重复发）。</p>
     */
    ACTIVITY_START_REMINDER("activity-start-reminder", "activityName", "startTime", "location"),

    /**
     * 「非常抱歉，您报名的${activityName}已取消，原因：${reason}。」
     *
     * <p>落点：后台取消活动（{@code ActivityService#cancel}），发给该活动全部<b>有效报名</b>的志愿者。</p>
     */
    ACTIVITY_CANCELLED("activity-cancelled", "activityName", "reason"),

    /**
     * 「您参与的${activityName}已结束，欢迎前往活动详情页面留下您的评价。」
     *
     * <p>落点：现场负责人点「活动结束」（{@code AttendanceService#finishActivity}），
     * 发给<b>实际签到过</b>的人——没到场的人收到「欢迎评价」只会莫名其妙。</p>
     */
    ACTIVITY_COMMENT_REMINDER("activity-comment-reminder", "activityName"),

    /**
     * 「恭喜完成${activityName}，获得服务时长${hours}小时，奖励积分${points}分。」
     *
     * <p>落点：积分发放（{@code ServiceRecordService#grantPoints}）。</p>
     */
    SERVICE_RECORD_CREDITED("service-record-credited", "activityName", "hours", "points"),

    // ==================== 奖惩与违规 ====================

    /**
     * 「您有一条${type}记录：${title}，涉及积分${points}分。详情请登录小程序查看。」
     *
     * <p>落点：奖惩单审核通过（{@code RewardPunishService#approve}），对应 xlsx Row 41 F
     * 「审核之后，志愿者会收到提示」。</p>
     *
     * <p><b>⚠️ 这条模板没有放申诉截止日期的位置</b>，而 Row 41 F 的原话是「并有 7 天申诉期」。
     * 只说「你有一条处罚记录」不说「几号之前可以申诉」，等于把有期限的权利说成没期限的。
     * 站内提示里写了准确的截止时刻，短信里<b>暂时写不了</b>——已作为问题发给协会
     * （见《待协会答复》问题一），协会补报一条带 {@code ${deadline}} 的模板后换掉即可。</p>
     */
    REWARD_PUNISH("reward-punish", "type", "title", "points"),

    /**
     * 「您在${activityName}中存在违规行为（${violationType}），扣除积分${pointsDeducted}分。如有异议可申诉。」
     *
     * <p>落点：现场违规记录<b>审核通过</b>（{@code ViolationReviewService#approve}）——
     * Row 41 F「各类违规记录…均需组织部同学审核才可显示，审核之后，志愿者会收到提示」，
     * 审核通过正是这条记录对志愿者可见的那一刻。</p>
     *
     * <p><b>⚠️ 扣分一律传 0</b>：现场违规记录本身不扣积分，扣分是后续<b>处罚单</b>干的事
     * （那一步发 {@link #REWARD_PUNISH}）。文案里那句「扣除积分0分」读着别扭但属实；
     * 已一并写进给协会的问题清单。</p>
     */
    ACTIVITY_VIOLATION("activity-violation", "activityName", "violationType", "pointsDeducted"),

    // ==================== 组织与小组 ====================

    /**
     * 「您申请加入${orgName}，审核结果：${status}。${remark}」
     *
     * <p>落点两处：<b>分队</b>加入申请审批（{@code SquadService#approveApplication/rejectApplication}）
     * 与<b>报名管理团队</b>审批（{@code ManagerApplicationService#approve/reject}）——
     * 后者的 {@code orgName} 传「管理团队」。两者都是「申请加入某个组织、由人来批」，共用这一条。</p>
     */
    ORG_JOIN_RESULT("org-join-result", "orgName", "status", "remark"),

    /**
     * 「尊敬的志愿者您好，您加入“${teamName}”小组的申请${result}。」（控制台名称：国内通知短信）
     *
     * <p>落点：志愿小组的加入审批（{@code GroupService#approveMember/rejectMember}）
     * 与<b>建组</b>审批（{@code GroupService#approveCreate/rejectCreate}——发起人等的就是这个结果）。</p>
     */
    GROUP_JOIN_RESULT("group-join-result", "teamName", "result"),

    // ==================== 以下暂无落点：对应功能不存在或未开发 ====================

    /**
     * 「尊敬的${name}，您的志愿者申请已提交成功，请耐心等待审核。」
     *
     * <p><b>不接</b>：注册走身份证二要素<b>自动</b>核验，当场出结果，<b>没有「等待审核」这个状态</b>。</p>
     */
    VOLUNTEER_APPLY_SUBMITTED("volunteer-apply-submitted", "name"),

    /**
     * 「恭喜${name}！您的志愿者申请已通过审核，志愿者编号：${volunteerId}，欢迎加入。」
     *
     * <p><b>不接</b>：同上，没有人工审核环节，「已通过审核」与实际流程不符；
     * 且注册流程刚发过一条验证码短信，紧接着再发一条欢迎短信价值有限、成本翻倍。
     * 协会若确实想要注册欢迎短信，改文案后接在注册成功处即可。</p>
     */
    VOLUNTEER_APPLY_APPROVED("volunteer-apply-approved", "name", "volunteerId"),

    /**
     * 「尊敬的${name}，您的志愿者申请未通过审核，原因：${reason}。请完善信息后重新提交。」
     *
     * <p><b>不接</b>：实名核验失败是当场返回的错误，用户正盯着屏幕，再补一条短信没有意义。</p>
     */
    VOLUNTEER_APPLY_REJECTED("volunteer-apply-rejected", "name", "reason"),

    /** 「您报名的${activityName}审核未通过，原因：${reason}。」<b>不接</b>：招募功能未开发。 */
    RECRUIT_REVIEW_RESULT("recruit-review-result", "activityName", "reason"),

    /** 「您已成功报名${title}招募，请留意后续通知。」<b>不接</b>：招募功能未开发。 */
    RECRUIT_ENROLLED("recruit-enrolled", "title"),

    /** 「您的${activityName}请假申请已通过。」<b>不接</b>：请假审批功能未开发（现有请假只是考勤状态）。 */
    LEAVE_APPROVED("leave-approved", "activityName"),

    /** 「您的${activityName}请假申请未通过，原因：${reason}。」<b>不接</b>：同上。 */
    LEAVE_REJECTED("leave-rejected", "activityName", "reason"),

    /** 「您兑换的${goodsName}订单审核${status}。${remark}」<b>不接</b>：积分商城属 donate，模块未建。 */
    POINTS_ORDER_REVIEW("points-order-review", "goodsName", "status", "remark"),

    /** 「您提交的投诉建议（编号：${submissionNumber}）已处理，回复：${replyContent}。」<b>不接</b>：投诉建议（Row 43）未开发。 */
    COMPLAINT_REPLIED("complaint-replied", "submissionNumber", "replyContent");

    private final String key;
    private final List<String> variables;

    SmsNotifyTemplate(String key, String... variables) {
        this.key = key;
        this.variables = List.of(variables);
    }

    /** 配置键：{@code hengde.sms.templates} 下的那一行，值为火山模板 ID。 */
    public String getKey() {
        return key;
    }

    /** 模板变量名，<b>顺序即 {@link #params(String...)} 的传参顺序</b>。 */
    public List<String> getVariables() {
        return variables;
    }

    /**
     * 按声明顺序组模板参数。
     *
     * <p>调用方只管按上面 javadoc 里正文出现的顺序把值传进来，键名由本枚举填，
     * 因此<b>拼错变量名是不可能的</b>——那正是这套模板最容易出、又最难发现的错。</p>
     *
     * <p>{@code null} 一律转成空串：火山对缺失的占位不会报错、只会留个空洞，
     * 与其让「原因」那一格变成字面量 {@code null}，不如空着。</p>
     *
     * @param values 与 {@link #getVariables()} 等长、同序的值
     * @throws IllegalArgumentException 个数对不上（属编码错误，应当在测试期就炸出来）
     */
    public Map<String, String> params(String... values) {
        if (values == null || values.length != variables.size()) {
            throw new IllegalArgumentException("模板 " + key + " 需要 " + variables.size()
                    + " 个参数 " + variables + "，实际传入 " + (values == null ? 0 : values.length) + " 个");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            map.put(variables.get(i), values[i] == null ? "" : values[i]);
        }
        return map;
    }
}
