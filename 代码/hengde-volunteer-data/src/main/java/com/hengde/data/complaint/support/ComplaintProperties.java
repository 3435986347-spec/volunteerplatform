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

    /** 新工单默认进哪个部门（Row 43「投诉默认到监察部」）。须与后台账号的「部门」一字不差，否则没人看得到。 */
    private String defaultDepartment = "监察部";

    /** 同一个人 24 小时内最多提交几条（防刷；投诉建议对所有登录账号开放，游客也能提）。 */
    private int dailyLimit = 5;

    /** 短信里答复内容最多放多少字，其余在小程序里看（短信变量有长度限制，整段塞进去会被运营商拒收或截断）。 */
    private int smsReplyMaxChars = 30;
}
