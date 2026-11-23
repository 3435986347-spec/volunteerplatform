package com.hengde.organization.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 组长变更历史（V7 起）。每次 transferLeader 或建组审批通过的初次任命都追加一条。
 * 仅追加、不更新——查询「该小组历任组长」按 changeTime 倒序即可。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("volunteer_group_leader_history")
public class VolunteerGroupLeaderHistory extends BaseEntity {

    private Long groupId;
    /** 前任组长（建组首次任命为 null） */
    private Long oldLeaderId;
    private Long newLeaderId;
    private LocalDateTime changeTime;
    /** 1=组长主动转移 / 2=后台管理员转移 / 3=建组审批首次任命 */
    private Integer operatorType;
    /** 操作人 id：志愿者 id（type=1）或 admin_user.id（type=2,3） */
    private Long operatorId;
    private String reason;
}
