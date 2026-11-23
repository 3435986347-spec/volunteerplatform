package com.hengde.system.constant;

/**
 * 系统治理域的权限点（V4 系统治理批）。
 *
 * <p>⚠️ 类名必须叫 {@code PermissionCode}——契约脚本按这个文件名收集常量（data 模块起过别的名字，7 个端点全被判成「只需登录」）。</p>
 *
 * @author hengde
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    /**
     * 操作日志查看（Row 62「所有记录均记录到后台数据，最高权限才可查看」）。
     *
     * <p><b>默认不授任何人</b>（超管通配），与 {@code social:chat-view}、{@code social:real-name} 同一形状：
     * 日志里有谁看过谁的手机号、谁导出过名单，本身就是敏感信息。</p>
     */
    public static final String SYSTEM_LOG = "system:log";

    /** 系统配置：界面水印（Row 78）、后台菜单排序（Row 77）、编号段（Row 75） */
    public static final String SYSTEM_CONFIG = "system:config";

    /** 文件网盘（Row 71）：文件夹 / 文件 / 授权 / 分享 / 公开到小程序 */
    public static final String SYSTEM_FILE = "system:file";
}
