package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 受赠单位主数据（V50，《协会待确认清单-v3》⑤ 默认）。
 *
 * <p>表上的生成列 {@code active_name_key} 刻意不映射——由数据库计算，映射进来 INSERT 会试图写它而报错。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_recipient_org")
public class DonateRecipientOrg extends BaseEntity {

    private String name;
    /** 1学校/2乡镇/3其他 */
    private Integer orgType;
    private String address;
    private String contactName;
    private String contactPhone;
    /** 1启用/0停用 */
    private Integer status;
    private Integer sort;
    private Long createBy;
}
