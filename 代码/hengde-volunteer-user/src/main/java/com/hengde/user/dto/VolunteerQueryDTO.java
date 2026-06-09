package com.hengde.user.dto;

import com.hengde.common.page.PageQuery;
import lombok.Getter;
import lombok.Setter;

/**
 * 志愿者列表筛选入参（{@code GET /a/user/volunteers}）。继承分页参数 page/size。
 *
 * <p>性别/政治面貌/年级按 <b>code</b> 精确筛（与实体枚举的 code 对齐）；学校模糊；分队按 squadId；
 * keyword 命中姓名/学校（模糊）或手机号（HMAC 精确——手机号是密文无法模糊匹配）。</p>
 *
 * @author hengde
 */
@Getter
@Setter
public class VolunteerQueryDTO extends PageQuery {

    /** 关键词：姓名/学校模糊；纯数字则按手机号 HMAC 精确匹配 */
    private String keyword;

    /** 性别 code：0未知/1男/2女（见 {@link com.hengde.common.constant.Gender}）。非法值由 service 校验抛业务异常 */
    private Integer gender;

    /** 归属分队 id */
    private Long squad;

    /** 政治面貌 code 1~5（见 {@link com.hengde.common.constant.PoliticalStatus}）。非法值由 service 校验抛业务异常 */
    private Integer political;

    /** 学校（模糊匹配） */
    private String school;

    /** 年级 code 1~18（见 {@link com.hengde.common.constant.Grade}）。非法值由 service 校验抛业务异常 */
    private Integer grade;
}
