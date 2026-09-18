package com.hengde.data.constant;

/**
 * data 模块的权限点（与 {@code permission} 种子一致）。类名叫 {@code PermissionCode} 是约定：契约脚本按这个文件名收集权限常量。
 *
 * @author hengde
 */
public final class PermissionCode {

    /** 投诉建议处理：只看得到、只能动<b>当前在本部门</b>的工单（V65） */
    /**
     * 数据看板与汇总（V2 已 seed）：此前只作前端菜单可见性，**自 V4 数据汇总批起，Row 79 的汇总端点真的挂它**。
     *
     * <p>`GET /a/data/dashboard`（志愿者端首页也在用的那组数字）保持「仅需登录」不变——
     * 汇总那一份含举报、封号、金额，与头部统计不是一个东西。</p>
     */
    public static final String DATA_DASHBOARD = "data:dashboard";

    public static final String COMPLAINT = "data:complaint";
    /** 投诉建议全部门：看全部工单并可代任何部门处理（V65；Row 43「监察部可根据工作需要选择流转」，默认给监察部） */
    public static final String COMPLAINT_ALL = "data:complaint-all";

    private PermissionCode() {
    }
}
