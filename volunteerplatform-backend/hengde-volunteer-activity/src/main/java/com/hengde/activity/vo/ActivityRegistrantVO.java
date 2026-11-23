package com.hengde.activity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者端「报名详情/报名列表」单行。
 *
 * <p>露报名人<b>完整姓名</b> + 报名时间（2026-06-27 用户要求由「仅姓氏」改为完整姓名）；
 * 仍不含手机号等其它 PII。</p>
 *
 * @author hengde
 */
@Data
public class ActivityRegistrantVO {

    /** 报名人完整姓名 */
    private String name;

    /** 报名时间 */
    private LocalDateTime enrollTime;
}
