package com.hengde.enterprise.constant;

/**
 * 爱心企业域权限点（V77）。⚠️ 类名必须叫 {@code PermissionCode}：契约脚本按这个文件名收集常量。
 *
 * @author hengde
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    /** 爱心企业管理：查看 / 搜索 / 后台注册 / 暂停恢复 / 删除（Row 15 F） */
    public static final String ENTERPRISE_MANAGE = "enterprise:manage";
    /** 爱心企业入驻审核 */
    public static final String ENTERPRISE_AUDIT = "enterprise:audit";
    /** 爱心企业批量导出——导出里有负责人手机号，与查看分开授权（同 user:list / user:export 的先例） */
    public static final String ENTERPRISE_EXPORT = "enterprise:export";
    /** 爱心企业积分：查看账本 / 调整（兑换企业权益时扣减）/ 补记兑换入账（V79，商品段） */
    public static final String ENTERPRISE_POINTS = "enterprise:points";
    /** 赞助商评价管理：查看 / 屏蔽 / 恢复 / 删除（V81，Row 74「删除、屏蔽功能」） */
    public static final String ENTERPRISE_REVIEW = "enterprise:review";
}
