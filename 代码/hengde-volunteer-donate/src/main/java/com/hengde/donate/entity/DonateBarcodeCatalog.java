package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品条码数据库（V50，Row 17 D「这个条码是对应哪个东西，捐赠人输入那本书即可跳出那本书的信息」）。
 *
 * <p>生成列 {@code active_barcode_key} 不映射，理由同 {@link DonateRecipientOrg}。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_barcode_catalog")
public class DonateBarcodeCatalog extends BaseEntity {

    private String barcode;
    private String name;
    /** 1课外书籍/2学习用品/3运动器材/9其他 */
    private Integer itemType;
    private String spec;
    private String remark;
}
