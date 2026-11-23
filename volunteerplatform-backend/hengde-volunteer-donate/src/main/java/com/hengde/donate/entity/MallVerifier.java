package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商城核销员（V46 {@code mall_verifier}）。
 *
 * <p>核销员是<b>志愿者</b>（Row 8 F「设置某一个志愿者为企业核销员」）。
 * 表上的生成列 {@code active_volunteer_key} <b>刻意不映射</b>——它由数据库计算，
 * 映射进来 MyBatis-Plus 会在 INSERT 时试图写它而报错。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_verifier")
public class MallVerifier extends BaseEntity {

    private Long volunteerId;

    /** 作用域：null = 全部商品（V3 恒为 null）；V4 企业自助指派时为企业 id */
    private Long enterpriseId;

    private String remark;

    /** 指派人 admin_user.id */
    private Long createBy;
}
