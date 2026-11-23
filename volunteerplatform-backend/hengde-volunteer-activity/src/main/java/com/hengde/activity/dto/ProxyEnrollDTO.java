package com.hengde.activity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 代报名入参：同小组成员之间互相帮报名某活动的若干时间段。
 *
 * <p>前置：调用方必须已加入某 ACTIVE 小组；{@code volunteerIds} 全部必须是同小组 ACTIVE 成员。
 * 校验由 {@code GroupQueryService.requireSameActiveGroup} 兜底——任一不满足则整批回滚。</p>
 *
 * @author hengde
 */
@Getter
@Setter
public class ProxyEnrollDTO {

    /** 被代报名的同组志愿者 id 列表（可含自己，但实际场景一般不含），最多 20 人 */
    @NotEmpty(message = "请至少选择一名被代报名的同组成员")
    @Size(max = 20, message = "一次最多代报名 20 名同组成员")
    private List<@NotNull(message = "志愿者 id 不能为空") Long> volunteerIds;

    /** 时间段 id 列表，与自助报名同一份语义；为每个 target 各报这些时间段 */
    @NotEmpty(message = "请选择至少一个时间段")
    private List<@NotNull(message = "时间段 id 不能为空") Long> slotIds;
}
