package com.hengde.activity.dto;

import lombok.EqualsAndHashCode;

/**
 * 修改活动入参。可改字段与发布一致（全量提交，含时间段全量替换）。
 *
 * <p>已结束/已取消的活动不可改（service 校验）。V1 不区分活动开始前后的可改字段范围，
 * 统一全量更新；细粒度的「开始后仅可改人数/结束时间」等约束后续再补。</p>
 *
 * @author hengde
 */
@EqualsAndHashCode(callSuper = true)
public class ActivityUpdateDTO extends ActivityCreateDTO {
}
