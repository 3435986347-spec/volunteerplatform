package com.hengde.user.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 我的注销状态（{@code GET /v/user/deactivation}）。
 *
 * @author hengde
 */
@Getter
@Setter
public class DeactivationVO {

    /** 是否已申请注销、正在冷静期内 */
    private boolean pending;

    /** 申请时间 */
    private LocalDateTime requestTime;

    /** 注销生效时间（冷静期结束的时刻；之前可以撤销） */
    private LocalDateTime effectiveTime;

    /** 冷静期天数（当前配置） */
    private int coolingDays;
}
