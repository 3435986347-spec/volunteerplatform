package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结对登记（V52）。生成列 {@code active_pair_key} 不映射（数据库计算，保证一人一项目至多一条有效登记）。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_pair_record")
public class DonatePairRecord extends BaseEntity {

    private Long projectId;
    private Long volunteerId;
    private BigDecimal amount;
    /** 已付金额（V61，结对捐款成功累加、退款减回） */
    private BigDecimal paidAmount;
    /** 见 {@code PairFlow.AMOUNT_*} */
    private Integer amountType;
    /** 见 {@code PairFlow.PAIR_*} */
    private Integer status;
    private LocalDateTime registerTime;
    /** 结对成立时间——证书的触发点 */
    private LocalDateTime establishedTime;
    private LocalDateTime cancelTime;
    private Long cancelBy;
    private String cancelReason;
    private String remark;
}
