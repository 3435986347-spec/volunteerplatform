package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 勋章发放记录，V28。
 *
 * <p>发起后落「待审核」，须过发放审核才对志愿者生效；附带积分在生效那一刻入账。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_medal_grant")
public class HonorMedalGrant extends BaseEntity {

    /** 勋章 id */
    private Long medalId;

    /** 志愿者 id */
    private Long volunteerId;

    /** 发放方式 1手动/2自动（自动引擎未做，当前恒为 1） */
    private Integer grantType;

    /**
     * 发起时<b>快照</b>的积分奖励值，审核通过按此发分。
     *
     * <p>不在审核时现读勋章定义：申请与审核之间若管理员改了 {@code rewardPoints}，
     * 审核人批准的就不再是他看到的那个数。</p>
     */
    private Integer rewardPoints;

    /** 授予理由 */
    private String reason;

    /** 状态，见 {@code MedalGrantStatus} */
    private Integer status;

    /** 发放审核驳回原因 */
    private String rejectReason;

    /** 发起人 admin_user.id */
    private Long applyBy;

    /** 发起时间 */
    private java.time.LocalDateTime applyTime;

    /** 发放审核人 admin_user.id */
    private Long reviewBy;

    /** 发放审核时间 */
    private java.time.LocalDateTime reviewTime;

    /**
     * 防重复授予的唯一约束载体，<b>由数据库生成列计算</b>，应用不可写。
     *
     * <p>{@code @TableField(exist = false)} 不行——那样查询也读不到；用
     * {@code insertStrategy/updateStrategy = NEVER} 让 MP 永不把它写进 SQL，
     * 否则 INSERT 会因为写生成列而报错。</p>
     */
    @TableField(value = "active_grant_lock",
            insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String activeGrantLock;
}
