package com.hengde.system.constant;

/**
 * 系统治理域的取值（单一来源）。
 *
 * @author hengde
 */
public final class SystemCodes {

    private SystemCodes() {
    }

    /** 日志类型：操作（写 / 敏感读，拦截器记） */
    public static final int LOG_OPERATION = 1;
    /** 日志类型：页面访问（前端上报——后端看不见纯前端的路由切换，D9） */
    public static final int LOG_PAGE_VIEW = 2;

    /** 操作人类型 */
    public static final int ACTOR_ANONYMOUS = 0;
    public static final int ACTOR_ADMIN = 1;
    public static final int ACTOR_VOLUNTEER = 2;
    public static final int ACTOR_ENTERPRISE = 3;

    /** 配置键：界面水印（Row 78） */
    public static final String CONFIG_WATERMARK = "watermark";
    /** 配置键：后台菜单排序（Row 77） */
    public static final String CONFIG_MENU_ORDER = "menu-order";

    /** 文件夹授权对象：后台账号 / 部门 */
    public static final int GRANTEE_ADMIN = 1;
    public static final int GRANTEE_DEPARTMENT = 2;

    /** 网盘文件的编号段（Row 75：十位数字，前三位是功能段） */
    public static final String SERIAL_SEGMENT_FILE = "101";

    /** 网盘文件传到对象存储的哪个目录 */
    public static final String DIR_VAULT = "vault";

    public static String actorLabel(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case ACTOR_ADMIN -> "后台账号";
            case ACTOR_VOLUNTEER -> "志愿者";
            case ACTOR_ENTERPRISE -> "爱心企业";
            default -> "未登录";
        };
    }
}
