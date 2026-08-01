package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水明细行。
 *
 * <p>志愿者端与管理端共用；管理端额外关心 {@code operatorType}/{@code operatorId}（谁调的分）。
 * 不回 {@code sourceId}——那是内部单据主键，对使用者无意义，且避免暴露内部结构。</p>
 *
 * @author hengde
 */
@Data
public class PointRecordVO {

    private Long id;

    /** 变动值：正=入账，负=出账 */
    private Integer changeAmount;

    /** 来源类型码，见 {@link com.hengde.activity.constant.PointSourceType} */
    private Integer sourceType;

    /** 来源类型中文名，前端直接展示 */
    private String sourceTypeName;

    /** 说明（如「参加XX活动」） */
    private String remark;

    /** 操作方 0系统/1管理员 */
    private Integer operatorType;

    /** 操作人 id */
    private Long operatorId;

    /** 发生时间 */
    private LocalDateTime createTime;
}
