package com.hengde.activity.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 志愿者端「推荐活动」列表项：在 {@link ActivityListVO} 基础上多带 报名人数 与 有名额标记。
 *
 * <p>独立于管理端列表 VO——这两项需按活动算名额，仅推荐列表（{@code selectRecommendPage}）填充；
 * 管理端列表走普通拷贝、不含这两项，避免共享 VO 造成「/v 有值、/a 为空」的语义混淆。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RecommendActivityVO extends ActivityListVO {

    /** 报名人数（活跃报名去重志愿者数：待审核+已通过） */
    private Long enrolledCount;

    /** 是否有名额 1有/0满（任一时间段未满或不限即为有名额；用于推荐排序与「名额已满」标记） */
    private Integer hasQuota;

    /** 志愿者报名开放时间（派生展示状态用，DB 列 enroll_open_volunteer） */
    private LocalDateTime enrollOpenVolunteer;

    /** 现场运行状态 0未开始/1进行中/2已结束（派生展示状态用，DB 列 run_status） */
    private Integer runStatus;

    /**
     * 志愿者端展示状态（实时派生，见 {@link com.hengde.activity.constant.ActivityDisplayStatus}）：
     * 0未开放/1报名中/2报名截止/3活动中/4已结束。前端徽标按此显示，而非持久化的 {@code status}。
     */
    private Integer displayStatus;
}
