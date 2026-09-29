package com.hengde.data.complaint.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 投诉建议配置（{@code hengde.data.complaint}）。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.data.complaint")
public class ComplaintProperties {

    /**
     * 各类别的工单进哪个部门（协会 2026-09-02）。须与后台账号的「部门」一字不差，否则只有持
     * {@code data:complaint-all} 的账号看得到。
     *
     * <p>需求表 Row 43 原写「投诉默认到监察部」——监察部与分队管理部一年前已撤销（协会 2026-09-16），
     * 不要再把监察部配回来：那样工单会进一个没有人的部门。</p>
     */
    private Routes routes = new Routes();

    /** 同一个人 24 小时内最多提交几条（防刷；投诉建议对所有登录账号开放，游客也能提）。 */
    private int dailyLimit = 5;

    /** 短信里答复内容最多放多少字，其余在小程序里看（短信变量有长度限制，整段塞进去会被运营商拒收或截断）。 */
    private int smsReplyMaxChars = 30;

    /** 类别 → 受理部门；没配或配成空串即「这一类暂不受理」。 */
    public String departmentFor(int category) {
        String d = switch (category) {
            case ComplaintFlow.CATEGORY_ACTIVITY -> routes.getActivity();
            case ComplaintFlow.CATEGORY_INTERNAL -> routes.getInternal();
            case ComplaintFlow.CATEGORY_COMMUNITY -> routes.getCommunity();
            default -> null;
        };
        return d == null || d.isBlank() ? null : d.trim();
    }

    @Data
    public static class Routes {

        /** 活动类（活动现场、报名、考勤、负责人……）。 */
        private String activity = "组织部";

        /** 协会内部类（协会管理、部门与人员……）。 */
        private String internal = "秘书部";

        /** 社区类（帖子、评论、私信……）。 */
        private String community = "宣传部";
    }
}
