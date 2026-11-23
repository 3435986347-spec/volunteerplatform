package com.hengde.enterprise.constant;

/**
 * 爱心企业账号状态（V77）。
 *
 * @author hengde
 */
public final class EnterpriseStatus {

    private EnterpriseStatus() {
    }

    /** 待审核：能登录、只能看改自己的资料 */
    public static final int PENDING = 0;
    /** 正常 */
    public static final int NORMAL = 1;
    /** 驳回：能登录、改资料后重新提交 */
    public static final int REJECTED = 2;
    /** 暂停：登录不了，已登录的下一次请求即被踢出 */
    public static final int PAUSED = 3;

    /** 来源：自助注册 */
    public static final int SOURCE_SELF = 1;
    /** 来源：后台代建 */
    public static final int SOURCE_ADMIN = 2;

    public static String label(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case PENDING -> "待审核";
            case NORMAL -> "正常";
            case REJECTED -> "已驳回";
            case PAUSED -> "已暂停";
            default -> "未知";
        };
    }
}
