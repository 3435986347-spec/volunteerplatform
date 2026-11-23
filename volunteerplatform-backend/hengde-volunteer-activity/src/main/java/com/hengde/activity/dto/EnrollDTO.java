package com.hengde.activity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 志愿者报名入参：选定该活动下的一个或多个时间段/子项目。
 *
 * <p>项目数量须满足活动配置的 {@code minProjects ~ maxProjects}（业务层校验）。</p>
 *
 * @author hengde
 */
@Data
public class EnrollDTO {

    /** 报名的时间段 id 列表（activity_slot.id），至少一个，元素不可为 null */
    @NotEmpty(message = "请至少选择一个时间段")
    private List<@NotNull(message = "时间段 id 不能为空") Long> slotIds;
}
