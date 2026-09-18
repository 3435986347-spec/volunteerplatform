package com.hengde.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 志愿者处置措施（V2 第 5 批，V32）。
 *
 * <p>需求出处见 {@link com.hengde.auth.constant.SanctionScope}。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("volunteer_sanction")
public class VolunteerSanction extends BaseEntity {

    /** 生效中 */
    public static final int STATUS_ACTIVE = 1;
    /** 已解除（申诉成立或管理员撤销） */
    public static final int STATUS_LIFTED = 2;

    /** 来源：奖惩单 */
    public static final int SOURCE_REWARD_PUNISH = 1;

    /** 来源：社区禁言（V4 社区治理批；source_id＝social_ban.id，即时生效、不走奖惩审核，Q2） */
    public static final int SOURCE_SOCIAL_BAN = 2;

    /**
     * 限制天数上限（10 年）。<b>单一出处</b>：写入端 {@code SanctionService.impose}、
     * 开单端 {@code RewardPunishService} 与 V33 的 {@code ck_rp_sanction_days} 三处同一个数。
     *
     * <p>它不是协会给的期限，而是一个工程上限：{@code now.plusDays(Integer.MAX_VALUE)} 会直接抛
     * {@code DateTimeException}（500 而不是人话报错），而稍小一点的值会算出一个
     * 「看着有期限、实际等同永久」的到期日——那种处罚应当走「不设期限」明说，
     * 而不是用 99999 天伪装成有期限。协会若给出真实口径，改这里与 V33 的 CHECK 即可。</p>
     */
    public static final int MAX_SANCTION_DAYS = 3650;

    private Long volunteerId;

    private Integer sourceType;

    private Long sourceId;

    /** 能力域，见 {@link com.hengde.auth.constant.SanctionScope} */
    private Integer scope;

    private LocalDateTime effectiveTime;

    /** 到期时间；{@code null} = 不设期限 */
    private LocalDateTime expireTime;

    private Integer status;

    private Long liftedBy;

    private LocalDateTime liftedTime;

    private String liftReason;
}
