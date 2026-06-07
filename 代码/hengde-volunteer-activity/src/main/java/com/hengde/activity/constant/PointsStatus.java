package com.hengde.activity.constant;

/**
 * 积分发放状态码：0未发放 / 1已发放。
 *
 * @author hengde
 */
public final class PointsStatus {

    private PointsStatus() {
    }

    /** 未发放 */
    public static final int NOT_GRANTED = 0;
    /** 已发放 */
    public static final int GRANTED = 1;
}
