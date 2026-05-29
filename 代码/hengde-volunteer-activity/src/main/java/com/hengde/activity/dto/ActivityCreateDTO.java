package com.hengde.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发布活动入参（含子时间段）。报名限制条件 null/0 表示不限。
 *
 * @author hengde
 */
@Data
public class ActivityCreateDTO {

    /** 活动名称 */
    @NotBlank(message = "活动名称不能为空")
    private String title;

    /** 活动封面图 */
    private String coverImageUrl;

    /** 活动地点 */
    private String location;

    /** 活动内容/介绍 */
    private String content;

    /** 活动要求 */
    private String requirement;

    /** 活动整体开始时间 */
    @NotNull(message = "活动开始时间不能为空")
    private LocalDateTime startTime;

    /** 活动整体结束时间 */
    @NotNull(message = "活动结束时间不能为空")
    private LocalDateTime endTime;

    /** 报名截止时间（不填默认活动开始前24h） */
    private LocalDateTime enrollDeadline;

    /** 取消报名截止（null=随时可取消） */
    private LocalDateTime cancelDeadline;

    /** 积分基数 */
    @Min(value = 0, message = "积分基数不能为负")
    private Integer pointsBase;

    /** 负责人积分倍率（不填默认1.4） */
    @DecimalMin(value = "0.1", message = "负责人积分倍率必须大于0")
    private BigDecimal leaderMultiplier;

    /** 管理团队积分倍率（不填默认1.2） */
    @DecimalMin(value = "0.1", message = "管理团队积分倍率必须大于0")
    private BigDecimal managerMultiplier;

    /** 报名是否需审核 0否/1是（不填默认0） */
    @Min(value = 0, message = "needAudit 取值 0/1")
    @Max(value = 1, message = "needAudit 取值 0/1")
    private Integer needAudit;

    /** 报名范围 0全平台/1指定分队（不填默认0） */
    @Min(value = 0, message = "enrollScope 取值 0/1")
    @Max(value = 1, message = "enrollScope 取值 0/1")
    private Integer enrollScope;

    /** 指定分队id列表（逗号分隔，enrollScope=1时用；V1 暂不校验） */
    private String targetSquadIds;

    /** 最小年龄要求 */
    @Min(value = 0, message = "年龄要求不能为负")
    private Integer requireMinAge;

    /** 最大年龄要求 */
    @Min(value = 0, message = "年龄要求不能为负")
    private Integer requireMaxAge;

    /** 最低年级要求（年级编码 1~18） */
    @Min(value = 1, message = "年级编码范围 1~18")
    @Max(value = 18, message = "年级编码范围 1~18")
    private Integer requireMinGrade;

    /** 最高年级要求（年级编码 1~18） */
    @Min(value = 1, message = "年级编码范围 1~18")
    @Max(value = 18, message = "年级编码范围 1~18")
    private Integer requireMaxGrade;

    /** 性别要求 null不限/1男/2女 */
    @Min(value = 1, message = "性别要求取值 1男/2女")
    @Max(value = 2, message = "性别要求取值 1男/2女")
    private Integer requireGender;

    /** 已参加活动次数门槛 */
    @Min(value = 0, message = "次数门槛不能为负")
    private Integer requireMinJoinCount;

    /** 最少需报名项目数 */
    @Min(value = 0, message = "最少报名项目数不能为负")
    private Integer minProjects;

    /** 最多可报名项目数（null=不限） */
    @Min(value = 1, message = "最多报名项目数至少为1")
    private Integer maxProjects;

    /** 报名须知（弹窗内容） */
    private String enrollNotice;

    /** 须知倒计时秒数 */
    @Min(value = 0, message = "倒计时秒数不能为负")
    private Integer noticeCountdownSec;

    /** 报名成功提示文字 */
    private String successTipText;

    /** 报名成功提示图片 */
    private String successTipImageUrl;

    /** 联系人姓名 */
    private String contactName;

    /** 联系人电话 */
    private String contactPhone;

    /** 发布团队/部门名称 */
    private String publisherDeptName;

    /** 管理团队报名开放时间（null=即时可报） */
    private LocalDateTime enrollOpenManager;

    /** 临时负责人报名开放时间（V1 字段预留） */
    private LocalDateTime enrollOpenLeader;

    /** 志愿者报名开放时间（影响志愿者端 enroll/代报名） */
    private LocalDateTime enrollOpenVolunteer;

    /** 时间段/子项目（至少一个） */
    @NotEmpty(message = "至少需要一个时间段")
    @Valid
    private List<ActivitySlotDTO> slots;
}
