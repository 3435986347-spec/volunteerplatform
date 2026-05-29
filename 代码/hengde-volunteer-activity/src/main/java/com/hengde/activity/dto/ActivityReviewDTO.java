package com.hengde.activity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 志愿者评价活动与负责人入参。活动结束后、本人有考勤记录方可提交，可覆盖。
 *
 * @author hengde
 */
@Data
public class ActivityReviewDTO {

    /** 对活动评分 1~5 */
    @NotNull(message = "活动评分不能为空")
    @Min(value = 1, message = "活动评分范围 1~5")
    @Max(value = 5, message = "活动评分范围 1~5")
    private Integer activityScore;

    /** 对负责人评分 1~5 */
    @NotNull(message = "负责人评分不能为空")
    @Min(value = 1, message = "负责人评分范围 1~5")
    @Max(value = 5, message = "负责人评分范围 1~5")
    private Integer leaderScore;

    /** 评价留言（可空） */
    @Size(max = 512, message = "评价留言不超过 512 字")
    private String comment;
}
