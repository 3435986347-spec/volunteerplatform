package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 物资流转轨迹（V50，Row 17「以上操作轨迹捐赠人均可在他们的前端看得到记录」）。只追加。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_item_trace")
public class DonateItemTrace extends BaseEntity {

    private Long shipmentId;
    /** 运单级动作为 null */
    private Long itemId;
    private Long boxId;
    /** 见 {@code DonateFlow.ACT_*} */
    private Integer action;
    private String content;
    /** 0系统/1管理员/2捐赠人 */
    private Integer operatorType;
    private Long operatorId;
}
