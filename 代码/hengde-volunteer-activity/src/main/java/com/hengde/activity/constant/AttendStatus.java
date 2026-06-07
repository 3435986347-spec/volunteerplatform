package com.hengde.activity.constant;

/**
 * 到位状态码：1正常 / 2请假 / 3迟到 / 4缺席。
 *
 * <p>考勤、服务记录积分计算、考勤变更、活动补录多处共用的单一来源。</p>
 *
 * @author hengde
 */
public final class AttendStatus {

    private AttendStatus() {
    }

    /** 正常 */
    public static final int NORMAL = 1;
    /** 请假 */
    public static final int LEAVE = 2;
    /** 迟到 */
    public static final int LATE = 3;
    /** 缺席 */
    public static final int ABSENT = 4;
}
