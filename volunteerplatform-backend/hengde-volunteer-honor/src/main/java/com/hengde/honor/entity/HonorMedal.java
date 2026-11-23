package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 勋章定义（样式），V28。
 *
 * <p>全生命周期在后台：录入 → 提交 → 样式审核 → 已启用，只有已启用的才可用于发放。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_medal")
public class HonorMedal extends BaseEntity {

    /* ── 当前定义（管理端编辑的对象）与最后过审快照（志愿者端展示的对象）成对存在，V29。
       改一枚已启用勋章会原地覆盖当前定义并退回待审核，而「我的勋章」按 id 把该行补回来展示，
       没有快照的话已获得者会立刻看到未过审的样式、驳回稿也会一直挂着。见 V29 迁移注释。 */

    /** 勋章名称（当前定义，可能尚未过审） */
    private String name;

    /** 最后一次过审的名称；志愿者端只读这一列 */
    private String approvedName;

    /** 图标 URL（经 {@code /a/files/upload?dir=medal} 上传）（当前定义，可能尚未过审） */
    private String iconUrl;

    /** 最后一次过审的图标 URL */
    private String approvedIconUrl;

    /** 说明，志愿者端展示（当前定义，可能尚未过审） */
    private String description;

    /** 最后一次过审的说明 */
    private String approvedDescription;

    /** 获取条件，见 {@code MedalConditionType}；本批只存不判（当前定义，可能尚未过审） */
    private Integer conditionType;

    /** 最后一次过审的获取条件 */
    private Integer approvedConditionType;

    /** 条件阈值；手动授予时为 null（当前定义，可能尚未过审） */
    private Long conditionThreshold;

    /** 最后一次过审的条件阈值 */
    private Long approvedConditionThreshold;

    /** 附带积分奖励，0=不发（当前定义，可能尚未过审） */
    private Integer rewardPoints;

    /** 最后一次过审的附带积分 */
    private Integer approvedRewardPoints;

    /** 展示排序，小的在前 */
    private Integer sort;

    /** 状态，见 {@code MedalStatus} */
    private Integer status;

    /** 样式审核驳回原因 */
    private String rejectReason;

    /** 样式审核人 admin_user.id */
    private Long reviewBy;

    /** 样式审核时间 */
    private java.time.LocalDateTime reviewTime;
}
