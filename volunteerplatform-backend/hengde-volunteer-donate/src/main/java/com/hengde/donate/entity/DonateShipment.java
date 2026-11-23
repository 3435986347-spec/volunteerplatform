package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 捐赠运单 / 包裹（V50）。
 *
 * <p>{@link #trackJson} 标了 {@code select = false}：它是 MEDIUMTEXT 的完整物流轨迹，
 * 列表与大多数读路径都用不到，默认 SELECT 带上它会把每一页的响应撑大一个数量级。
 * 要看完整轨迹时走 {@code DonateShipmentMapper.selectTrackJson}。</p>
 *
 * <p>生成列 {@code active_express_key} 不映射（数据库计算）。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_shipment")
public class DonateShipment extends BaseEntity {

    private Integer bizType;
    private Long bizId;
    private Long donorVolunteerId;
    private String donorName;
    private String donorOrg;
    private String expressCode;
    private String expressCompany;
    private String expressNo;
    /** 1已寄出/2已到货/3已核对/4已取消 */
    private Integer status;
    private LocalDateTime shipTime;
    private LocalDateTime arriveTime;
    private Long arriveBy;
    private LocalDateTime checkTime;
    private Long checkBy;
    /** 0无需退回/1待提交收件信息/2待寄回/3已寄回 */
    private Integer returnStatus;
    private String returnName;
    /** 密文（CryptoUtil） */
    private String returnPhone;
    private String returnAddress;
    private LocalDateTime returnSubmitTime;
    private String returnExpressCode;
    private String returnExpressCompany;
    private String returnExpressNo;
    private LocalDateTime returnTime;
    private Long returnBy;
    private Integer trackState;
    private String trackLastContext;
    private LocalDateTime trackLastTime;
    @TableField(select = false)
    private String trackJson;
    private LocalDateTime trackQueryTime;
    private Integer trackDone;
    /** 快递100 订阅状态，见 {@code DonateFlow.SUBSCRIBE_*}（V56） */
    private Integer subscribeStatus;
    /** 回调验签 salt，每单一个；<b>任何出参都不许带它</b> */
    private String subscribeSalt;
    private Integer subscribeAttempts;
    private LocalDateTime subscribeAttemptTime;
    private LocalDateTime subscribeTime;
    private String subscribeError;
    private LocalDateTime pushTime;
}
