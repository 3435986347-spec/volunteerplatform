package com.hengde.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 指派活动负责人入参。
 *
 * @author hengde
 */
@Data
public class AssignLeaderDTO {

    /**
     * 负责人来源：
     * <ul>
     *   <li>1 = <b>志愿者负责人</b>：本活动报名志愿者，<b>或「管理团队」(manager_flag) 志愿者</b>；refId = volunteer.id，用小程序现场签到/统一签退。</li>
     *   <li>2 = <b>后台账号负责人</b>：admin_user 子账号；refId = admin_user.id，经后台管理，不在小程序露负责人身份。</li>
     * </ul>
     * 注意：从「管理团队」安排负责人请用 <b>leaderType=1 + volunteer.id</b>（不是 2）——2 专指后台 admin_user 账号，
     * 填错会落到 admin_user_id，该人在小程序拿不到负责人身份。
     */
    @NotNull(message = "负责人来源不能为空")
    private Integer leaderType;

    /** leaderType=1 时为 volunteer.id（报名志愿者或管理团队志愿者）；=2 时为 admin_user.id（后台账号） */
    @NotNull(message = "负责人 id 不能为空")
    private Long refId;
}
