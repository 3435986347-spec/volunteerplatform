package com.hengde.user.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 志愿者管理-列表行（{@code GET /a/user/volunteers}）。
 *
 * <p>性别/年级/政治面貌同时给出 <b>label</b>（直接展示）与 <b>code</b>（编辑表单回填/筛选）。
 * 手机号已解密（管理端、登录可见）。服务时长/积分/参与活动为跨域 best-effort 聚合，无记录则为 0。</p>
 *
 * @author hengde
 */
@Getter
@Setter
public class AdminVolunteerListVO {

    private Long id;

    /** 姓名 */
    private String name;

    /** 性别 label（男/女/未知） */
    private String gender;

    /** 性别 code 1男/2女 */
    private Integer genderCode;

    /** 手机号（解密明文） */
    private String phone;

    /** 管理团队标记 0否/1是 */
    private Integer managerFlag;

    private String school;

    /** 年级 label */
    private String grade;

    /** 年级 code */
    private Integer gradeCode;

    /** 政治面貌 label */
    private String political;

    /** 政治面貌 code */
    private Integer politicalCode;

    /** 归属分队 id（可空） */
    private Long squadId;

    /** 归属分队名（可空=未归属） */
    private String squad;

    /** 所在小组名（可空=无小组） */
    private String group;

    /** 服务时长（小时，由已确认分钟换算，保留 1 位小数） */
    private Double hours;

    /** 累计积分（已发放） */
    private Integer points;

    /** 参与活动次数 */
    private Integer activities;

    /** 账号状态 0正常/1禁用/2注销 */
    private Integer status;

    /** 实名注册时间 */
    private LocalDateTime registerTime;
}
