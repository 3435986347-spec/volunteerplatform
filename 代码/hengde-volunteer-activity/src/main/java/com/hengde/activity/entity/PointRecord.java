package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 积分流水（V24）。积分的**唯一事实来源**。
 *
 * <p>此前积分只存于 {@code activity_attendance.points_award}，V1 够用但撑不起「总积分/已用积分/明细」
 * 与多来源积分。本表建立后，{@code points_award} 退化为「该次考勤发了多少分」的业务快照，
 * 汇总一律以本表为准。</p>
 *
 * <p><b>追加型账本，业务上永不删除</b>（{@code isDeleted} 仅为满足全局逻辑删除配置而继承）。
 * 幂等由 {@code uk_source(source_type, source_id)} 保证，各来源的 source_id 指向见
 * {@link com.hengde.activity.constant.PointSourceType}。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("point_record")
public class PointRecord extends BaseEntity {

    /** 志愿者 id */
    private Long volunteerId;

    /** 变动值：正=入账，负=出账。余额不冗余存储，一律由 {@code SUM(change_amount)} 求得 */
    private Integer changeAmount;

    /** 来源类型，见 {@link com.hengde.activity.constant.PointSourceType} */
    private Integer sourceType;

    /** 来源单据 id；手工调整为 null */
    private Long sourceId;

    /**
     * 幂等键。手工调整必填（{@code source_id} 为 null 时 {@code uk_source} 挡不住重复），
     * 其余来源为 null——它们各有单据 id 走 {@code uk_source}。
     */
    private String requestId;

    /** 说明（对志愿者展示） */
    private String remark;

    /** 操作方 0系统/1管理员 */
    private Integer operatorType;

    /** 操作人 id（管理员时为 admin_user.id） */
    private Long operatorId;
}
