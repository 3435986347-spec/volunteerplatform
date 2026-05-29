package com.hengde.organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 设置志愿者「管理团队」标记入参。
 *
 * @author hengde
 */
@Data
public class ManagerFlagDTO {

    /** 目标标记 0取消/1设为管理团队 */
    @NotNull(message = "标记值不能为空")
    @Min(value = 0, message = "标记值只能为 0 或 1")
    @Max(value = 1, message = "标记值只能为 0 或 1")
    private Integer flag;
}
