-- 报名管理团队：志愿者在小程序提交申请（问卷/简历）→ 后台审核 → 通过即置 volunteer.manager_flag=1。
-- 复用现有 manager_flag 通道与 org:manager-flag 权限点，不新增权限点。
-- status 0待审核/1已通过/2已驳回。通过仅置 manager_flag，不自动授任何权限点（权限仍由超管在授权页给）。
CREATE TABLE manager_application (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id      BIGINT       NOT NULL COMMENT '申请志愿者id',
    reason            VARCHAR(500) NOT NULL COMMENT '申请理由/自我介绍',
    experience        VARCHAR(500) DEFAULT NULL COMMENT '相关经历（可空）',
    expect_department VARCHAR(50)  DEFAULT NULL COMMENT '期望部门（可空）',
    status            TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核/1已通过/2已驳回',
    reject_reason     VARCHAR(512) DEFAULT NULL COMMENT '驳回原因',
    apply_time        DATETIME     NOT NULL COMMENT '申请时间',
    audit_by          BIGINT       DEFAULT NULL COMMENT '审核人 admin_user.id',
    audit_time        DATETIME     DEFAULT NULL COMMENT '审核时间',
    create_time       DATETIME     DEFAULT NULL,
    update_time       DATETIME     DEFAULT NULL,
    is_deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_volunteer_status (volunteer_id, status),
    KEY idx_volunteer_apply_time (volunteer_id, apply_time, id),
    KEY idx_status_apply_time (status, apply_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '报名管理团队申请';
