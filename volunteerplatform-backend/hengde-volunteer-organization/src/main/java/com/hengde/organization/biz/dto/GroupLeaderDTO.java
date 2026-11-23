package com.hengde.organization.biz.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GroupLeaderDTO {
    @NotNull
    private Long volunteerId;

    /** 变更原因（可选）—— 写入组长变更历史 reason 列 */
    private String reason;
}
