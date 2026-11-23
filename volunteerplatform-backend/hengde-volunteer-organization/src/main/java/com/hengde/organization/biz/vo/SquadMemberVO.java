package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 同分队成员展示项。字段按 {@code volunteer_squad.visible_fields} 控制：
 * 仅在 visibleFields 列出的字段才填值，未列出的保持为 null（不下发给前端）。
 * volunteerId 始终返回作为标识。
 */
@Getter
@Setter
public class SquadMemberVO {
    private Long volunteerId;
    private String realName;
    private String school;
    private Integer grade;
    private Integer gender;
    private String phone;
}
