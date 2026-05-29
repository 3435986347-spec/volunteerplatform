package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 组长变更记录（管理端查询用）。志愿者姓名经 volunteerMapper 解析后回填，避免前端再次查询。
 *
 * @author hengde
 */
@Getter
@Setter
public class GroupLeaderHistoryVO {
    private Long id;
    private Long oldLeaderId;
    /** 前任组长姓名（首次任命为 null） */
    private String oldLeaderName;
    private Long newLeaderId;
    private String newLeaderName;
    private LocalDateTime changeTime;
    /** 1=组长主动转移 / 2=后台管理员转移 / 3=建组审批首次任命 */
    private Integer operatorType;
    private Long operatorId;
    private String reason;
}
