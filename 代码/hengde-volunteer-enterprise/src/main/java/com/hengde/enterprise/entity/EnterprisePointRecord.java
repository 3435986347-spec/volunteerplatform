package com.hengde.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 爱心企业积分流水（V79，D5 独立账本）。
 *
 * @author hengde
 */
@Data
@TableName("enterprise_point_record")
public class EnterprisePointRecord {

    /** 兑换入账 */
    public static final int SOURCE_EXCHANGE = 1;
    /** 后台调整 */
    public static final int SOURCE_ADJUST = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long enterpriseId;
    private Integer changeAmount;
    private Integer sourceType;
    private Long sourceId;
    private String requestId;
    private String remark;
    private Long operatorId;
    private LocalDateTime createTime;
}
