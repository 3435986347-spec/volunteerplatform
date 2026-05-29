package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SquadVO {
    private Long id;
    private String name;
    private String type;
    private Long leaderId;
    private String leaderName;
    private String leaderPhone;
    private Integer memberLimit;
    private String visibleFields;
    private Integer status;
    private Long memberCount;

    /** 当前志愿者是否已归属本分队 */
    private Boolean belonged;

    /** 同分队成员名单：仅当 belonged=true 时返回，字段按 visibleFields 控制 */
    private List<SquadMemberVO> members;
}
