-- V5 organization business: volunteer groups, squads and organization structure.

CREATE TABLE volunteer_group (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_no      VARCHAR(32)  NOT NULL COMMENT '小组编号',
    name          VARCHAR(64)  NOT NULL COMMENT '小组名称',
    description   VARCHAR(512) DEFAULT NULL COMMENT '小组简介',
    leader_id     BIGINT       NOT NULL COMMENT '组长 volunteer.id',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核/1正常/2已拒绝/3已解散',
    reject_reason VARCHAR(255) DEFAULT NULL COMMENT '拒绝或解散原因',
    create_time   DATETIME     DEFAULT NULL,
    update_time   DATETIME     DEFAULT NULL,
    is_deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_no (group_no),
    KEY idx_name (name),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '志愿小组';

CREATE TABLE volunteer_group_member (
    id           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id     BIGINT   NOT NULL COMMENT '小组id',
    volunteer_id BIGINT   NOT NULL COMMENT '志愿者id',
    role         TINYINT  NOT NULL DEFAULT 0 COMMENT '0成员/1组长/2管理员',
    status       TINYINT  NOT NULL DEFAULT 0 COMMENT '0待审核/1已加入/2已拒绝/3已退出/4已移除',
    apply_time   DATETIME DEFAULT NULL COMMENT '申请时间',
    audit_time   DATETIME DEFAULT NULL COMMENT '审核时间',
    create_time  DATETIME DEFAULT NULL,
    update_time  DATETIME DEFAULT NULL,
    is_deleted   TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_group_status (group_id, status),
    KEY idx_volunteer_status (volunteer_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '志愿小组成员';

CREATE TABLE volunteer_squad (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name            VARCHAR(64)  NOT NULL COMMENT '分队名称',
    type            VARCHAR(32)  NOT NULL COMMENT '分队类型，如学校/乡镇',
    leader_id       BIGINT       DEFAULT NULL COMMENT '负责人 volunteer.id',
    leader_name     VARCHAR(32)  DEFAULT NULL COMMENT '负责人姓名冗余',
    leader_phone    VARCHAR(32)  DEFAULT NULL COMMENT '负责人联系电话',
    member_limit    INT          NOT NULL DEFAULT 0 COMMENT '人数上限，0不限',
    visible_fields  VARCHAR(255) DEFAULT NULL COMMENT '同分队可见字段，逗号分隔',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '0停用/1启用',
    create_time     DATETIME     DEFAULT NULL,
    update_time     DATETIME     DEFAULT NULL,
    is_deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_type_status (type, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '归属分队';

CREATE TABLE squad_application (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    squad_id     BIGINT       NOT NULL COMMENT '分队id',
    volunteer_id BIGINT       NOT NULL COMMENT '志愿者id',
    reason       VARCHAR(255) DEFAULT NULL COMMENT '申请说明',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核/1已通过/2已拒绝',
    reject_reason VARCHAR(255) DEFAULT NULL COMMENT '拒绝原因',
    apply_time   DATETIME     DEFAULT NULL COMMENT '申请时间',
    audit_time   DATETIME     DEFAULT NULL COMMENT '审核时间',
    create_time  DATETIME     DEFAULT NULL,
    update_time  DATETIME     DEFAULT NULL,
    is_deleted   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_squad_status (squad_id, status),
    KEY idx_volunteer_status (volunteer_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分队加入申请';

CREATE TABLE organization_structure_node (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    parent_id   BIGINT       DEFAULT NULL COMMENT '父节点',
    name        VARCHAR(64)  NOT NULL COMMENT '名称',
    title       VARCHAR(64)  DEFAULT NULL COMMENT '职务/说明',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    create_time DATETIME     DEFAULT NULL,
    update_time DATETIME     DEFAULT NULL,
    is_deleted  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_parent_sort (parent_id, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '组织架构节点';

INSERT INTO organization_structure_node (parent_id, name, title, sort, create_time, is_deleted) VALUES
(NULL, '雷州市恒德爱心公益协会', '协会', 1, NOW(), 0),
(1, '组织部', '志愿者组织与分队管理', 10, NOW(), 0),
(1, '秘书部', '资料与活动协同', 20, NOW(), 0),
(1, '宣传部', '公告与公示', 30, NOW(), 0);
