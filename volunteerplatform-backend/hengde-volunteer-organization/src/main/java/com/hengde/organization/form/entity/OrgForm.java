package com.hengde.organization.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 问卷（V63）。状态与场景见 {@code FormFlow}。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("org_form")
public class OrgForm extends BaseEntity {

    private Integer scene;
    private String title;
    private String description;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 1 每人一次 / 0 不限次数；发布后不可改 */
    private Integer singleSubmit;
    /** 1 须已实名 / 0 游客也可填 */
    private Integer requireRegistered;
    private LocalDateTime publishTime;
    private Long publishBy;
    private LocalDateTime closeTime;
    private Long createBy;
}
