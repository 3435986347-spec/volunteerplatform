package com.hengde.activity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 管理端手动新增报名入参：为指定志愿者补录某活动的若干时间段。
 *
 * <p>前端按手机号/姓名/身份证号搜索定位志愿者后传其 id。</p>
 *
 * @author hengde
 */
@Data
public class ManualEnrollDTO {

    /** 被代报名的志愿者 id */
    @NotNull(message = "请指定志愿者")
    private Long volunteerId;

    /** 报名的时间段 id 列表（activity_slot.id），至少一个，元素不可为 null */
    @NotEmpty(message = "请至少选择一个时间段")
    private List<@NotNull(message = "时间段 id 不能为空") Long> slotIds;
}
