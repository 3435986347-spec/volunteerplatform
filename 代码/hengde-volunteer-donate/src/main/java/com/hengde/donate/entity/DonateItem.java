package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 捐赠物资（V50）。biz_type / biz_id / donor_volunteer_id 冗余自运单，见迁移注释。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_item")
public class DonateItem extends BaseEntity {

    private Long shipmentId;
    private Integer bizType;
    private Long bizId;
    private Long donorVolunteerId;
    private String name;
    /** 1课外书籍/2学习用品/3运动器材/9其他 */
    private Integer itemType;
    private Integer quantity;
    private String catalogBarcode;
    /** 物品专属码 HDI…；核对合格后才生成 */
    private String exclusiveCode;
    /** 见 {@code DonateFlow.ITEM_*} */
    private Integer status;
    private String checkRemark;
    private Long boxId;
    private Long recipientOrgId;
    private String recipientOrgName;
    private LocalDateTime deliverTime;
    private Integer borrowCount;
    private Long addedBy;
}
