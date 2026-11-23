package com.hengde.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 暂停/恢复志愿者账号入参（{@code PATCH /a/user/volunteers/{id}/status}）。
 *
 * <p>仅允许 0正常 / 1禁用（见 {@link com.hengde.common.constant.UserStatus}）；
 * 「注销(2)」是用户主动注销的业务态，不经此后台开关设置，service 侧硬校验拦截。</p>
 *
 * @author hengde
 */
@Getter
@Setter
public class VolunteerStatusDTO {

    @NotNull(message = "状态不能为空")
    private Integer status;
}
