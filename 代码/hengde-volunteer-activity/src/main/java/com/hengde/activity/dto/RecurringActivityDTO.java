package com.hengde.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 固定日期周期发布入参（第 3 批·PR2）：以一份活动模板按多个日期批量发布多场活动。
 *
 * <p>{@link #template} 的 {@code startTime} 日期部分为<b>锚点日</b>，其所有日期型字段（活动起止/报名截止/
 * 取消截止/各角色报名开放/各时间段起止）的<b>时刻</b>为模板；每个目标日按相对锚点日的天数差整体平移。
 * 目标日 = {@link #dates}（显式日期）∪「{@link #recurStart}~{@link #recurEnd} 内落在 {@link #weekdays} 的日期」
 * （规则展开），并集去重。两者至少提供其一。</p>
 *
 * @author hengde
 */
@Data
public class RecurringActivityDTO {

    /** 活动模板（字段同发布活动） */
    @NotNull(message = "活动模板不能为空")
    @Valid
    private ActivityCreateDTO template;

    /** 显式日期列表（可空） */
    private List<LocalDate> dates;

    /** 周期规则：起始日期（含；与 recurEnd/weekdays 须同时提供） */
    private LocalDate recurStart;

    /** 周期规则：结束日期（含） */
    private LocalDate recurEnd;

    /** 周期规则：星期几（1周一…7周日） */
    private List<Integer> weekdays;
}
