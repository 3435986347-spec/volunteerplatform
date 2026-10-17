package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 积分商城自提点（V94，协会答复五 丙-3：「兑换时可勾选自提，并选择可兑换的自提点」）。
 *
 * <p>兑换单只存它的快照（名称 / 地址 / 电话），改名、停用、删除都不改写历史单据。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_pickup_point")
public class MallPickupPoint extends BaseEntity {

    /** 名称 */
    private String name;
    /** 地址 */
    private String address;
    /** 联系电话 */
    private String phone;
    /** 开放时间说明 */
    private String openHours;
    /** 1 启用 / 0 停用：停用的不能再被选，已兑换的单不受影响 */
    private Integer enabled;
    /** 排序 */
    private Integer sort;
}
