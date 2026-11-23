package com.hengde.activity.constant;

/**
 * 秘书部确认时长状态码：0待确认 / 1已确认。
 *
 * @author hengde
 */
public final class SecretaryStatus {

    private SecretaryStatus() {
    }

    /** 待确认 */
    public static final int PENDING = 0;
    /** 已确认 */
    public static final int CONFIRMED = 1;
}
