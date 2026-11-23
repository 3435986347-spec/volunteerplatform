package com.hengde.organization.exam.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 活动临时负责人资格（V75）。 */
@Getter
@Setter
@TableName("org_temp_leader_qualification")
public class OrgTempLeaderQualification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long volunteerId;
    private Integer sourceType;
    private Long attemptId;
    private LocalDateTime grantedTime;
    private LocalDateTime expireTime;
    private Long revokedBy;
    private LocalDateTime revokedTime;
    private String revokeReason;
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long activeKey;
}
