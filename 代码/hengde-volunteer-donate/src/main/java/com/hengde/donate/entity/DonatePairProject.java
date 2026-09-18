package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 助学助困结对项目（V52，Row 10）。
 *
 * <p>{@link #pledgedAmount} 是<b>认捐额</b>而不是已到账金额——本批不碰支付，见 V52 文件头。
 * 「参加人数」不存列，按结对记录现算。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_pair_project")
public class DonatePairProject extends BaseEntity {

    private String title;
    /** 见 {@code PairFlow.TYPE_*} */
    private Integer projectType;
    private String coverUrl;
    private String detail;
    private BigDecimal targetAmount;
    private BigDecimal pledgedAmount;
    /** 已到账金额（V60，捐款成功累加、退款减回） */
    private BigDecimal raisedAmount;
    /** 见 {@code PairFlow.PROJECT_*} */
    private Integer status;
    private Long createBy;
}
