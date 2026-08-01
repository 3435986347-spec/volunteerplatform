package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动违规记录明细行（负责人板块「违规记录」页：名字 / 记录人 / 记录明细 / 记录时间）。
 *
 * <p>违规者 id 必是志愿者，姓名按志愿者域解析。<b>记录人 {@link #recordedBy} 跨 volunteer/admin 两套 ID
 * 空间且会重叠</b>，故 {@link #recordedByName} 仅当其属<b>本活动志愿者负责人</b>（{@code leaderType=1}）时才解析，
 * 否则置 null（管理端账号录入 / 已撤销负责人 / 跨域同号）——避免把 admin_user.id 错认成同号志愿者。</p>
 *
 * @author hengde
 */
@Data
public class ViolationRecordVO {

    private Long id;

    /**
     * 场次 activity_slot.id（V30）。
     *
     * <p>违规自 V30 起记在场次上（xlsx Row 32 C：负责人页「显示活动场次…活动时间段…是否违规」）。
     * 不带场次时，一个多场次活动的违规明细页里「张三 玩手机」会出现两条一模一样的行，
     * 分不清是上午场还是下午场，也就无从核对。</p>
     */
    private Long slotId;

    /** 场次（岗位）名称 */
    private String slotProjectName;

    /** 场次开始时间 */
    private LocalDateTime slotStartTime;

    /** 场次结束时间 */
    private LocalDateTime slotEndTime;

    /** 违规者 volunteer.id */
    private Long volunteerId;

    /** 违规者姓名 */
    private String volunteerName;

    /** 类型 1玩手机/2服装不合格/3早退/4长时间交头接耳/5缺席/0其他（自由文本为主，类型保留） */
    private Integer violationType;

    /** 记录明细（自由文本） */
    private String description;

    /** 记录人 id（负责人 volunteer.id 或 admin_user.id） */
    private Long recordedBy;

    /** 记录人姓名（志愿者域 best-effort 解析；管理端录入则可能 null） */
    private String recordedByName;

    /** 记录时间 */
    private LocalDateTime recordedTime;
}
