package com.hengde.activity.constant;

/**
 * 活动现场运行状态码（与发布态 status 正交）：0未开始 / 1进行中 / 2已结束。
 *
 * @author hengde
 */
public final class RunStatus {

    private RunStatus() {
    }

    /** 未开始 */
    public static final int NOT_STARTED = 0;
    /** 进行中 */
    public static final int RUNNING = 1;
    /** 已结束 */
    public static final int ENDED = 2;
}
