package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者端「报名详情/报名列表」单行。
 *
 * <p>隐私收敛：仅露报名人的<b>第1个姓</b>（姓名首字）+ 报名时间，不含全名/手机号
 * （需求「报名列表：显示报名时间和报名人的第1个姓」）。</p>
 *
 * @author hengde
 */
@Data
public class ActivityRegistrantVO {

    /** 报名人姓氏（姓名首字，公开列表不露全名） */
    private String name;

    /** 报名时间 */
    private LocalDateTime enrollTime;
}
