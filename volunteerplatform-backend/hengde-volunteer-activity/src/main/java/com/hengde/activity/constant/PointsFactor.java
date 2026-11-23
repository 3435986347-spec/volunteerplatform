package com.hengde.activity.constant;

/**
 * 积分违规调整系数码：0正常(×1) / 1减半(×0.5) / 2不发(×0)。
 *
 * @author hengde
 */
public final class PointsFactor {

    private PointsFactor() {
    }

    /** 正常（×1） */
    public static final int NORMAL = 0;
    /** 减半（×0.5） */
    public static final int HALF = 1;
    /** 不发（×0） */
    public static final int NONE = 2;
}
