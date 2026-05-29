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

    /** 负责人来源 1=报名志愿者/2=管理团队 */
    @NotNull(message = "负责人来源不能为空")
    private Integer leaderType;

    /** leaderType=1 时为 volunteer.id；=2 时为 admin_user.id */
    @NotNull(message = "负责人 id 不能为空")
    private Long refId;
}
