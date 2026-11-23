package com.hengde.activity.constant;

/**
 * 活动发布状态码与「审核域」判定（V19）。
 *
 * <p>取值：0草稿 / 1已发布 / 2已结束 / 3已取消 / 4待审核发布 / 5发布被驳回。其中 4/5 是
 * <b>审核域</b>——小程序提交、等后台审核的活动，<b>只能经发布审核流程（publish-approve/reject）处置</b>，
 * 任何常规管理写动作（修改/删除/复制、指派负责人、活动补录等）都不得触达，统一用 {@link #isUnderReview}
 * 兜底，避免「有某管理权 + 知道 id」就绕开审核队列改动/上线待审内容。</p>
 *
 * @author hengde
 */
public final class ActivityStatus {

    private ActivityStatus() {
    }

    public static final int DRAFT = 0;
    public static final int PUBLISHED = 1;
    public static final int FINISHED = 2;
    public static final int CANCELLED = 3;
    public static final int PENDING_REVIEW = 4;
    public static final int REJECTED = 5;

    /** 是否处于「审核域」（待审核发布 4 或 发布被驳回 5）。 */
    public static boolean isUnderReview(Integer status) {
        return Integer.valueOf(PENDING_REVIEW).equals(status)
                || Integer.valueOf(REJECTED).equals(status);
    }
}
