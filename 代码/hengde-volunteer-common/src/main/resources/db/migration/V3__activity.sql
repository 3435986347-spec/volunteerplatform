-- V3 activity 域：志愿活动发布与报名（对应 V1 R65 活动发布 / R22 活动报名）
-- V1 粗粒度：不做签到/时长/公示闭环。代报名(proxy)、活动补录 本版搁置（字段预留，逻辑后置）。
-- 三张表：activity 主表 + activity_slot 时间段/子项目 + activity_enrollment 报名记录。
-- 公共列（id/create_time/update_time/is_deleted）见 com.hengde.common.entity.BaseEntity。

-- ============================================================
-- 活动主表
-- serial_no：对外展示的「唯一递增数字编号」(R65)。草稿可空，发布动作(status=1)时取自增 id 回写
--   （唯一递增、零竞态，不引分布式锁）；唯一索引允许多个 NULL，故草稿期不冲突。
-- 报名限制条件(age/grade/gender/min_join_count/min|max_projects)：null 或 0 表示「不限」。
-- target_squad_ids 为【临时字段】：V1 不做指定分队校验/筛选；待分队模块(organization,排期在 activity 之后)
--   落地时改为关联表 activity_target_squad(activity_id, squad_id)，届时本列废弃。
-- ============================================================
CREATE TABLE activity (
    id                     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    serial_no              BIGINT        DEFAULT NULL COMMENT '对外展示编号（唯一递增，发布时分配；草稿为空）',
    title                  VARCHAR(128)  NOT NULL COMMENT '活动名称',
    cover_image_url        VARCHAR(512)  DEFAULT NULL COMMENT '活动封面图',
    location               VARCHAR(255)  DEFAULT NULL COMMENT '活动地点',
    content                TEXT          DEFAULT NULL COMMENT '活动内容/介绍',
    requirement            TEXT          DEFAULT NULL COMMENT '活动要求',
    start_time             DATETIME      NOT NULL COMMENT '活动整体开始时间',
    end_time               DATETIME      NOT NULL COMMENT '活动整体结束时间',
    enroll_deadline        DATETIME      DEFAULT NULL COMMENT '报名截止时间（默认活动开始前24h，公示后则为公示时）',
    cancel_deadline        DATETIME      DEFAULT NULL COMMENT '取消报名截止（此后不可取消；null=随时可取消）',
    points_base            INT           NOT NULL DEFAULT 0 COMMENT '积分基数',
    leader_multiplier      DECIMAL(3, 1) NOT NULL DEFAULT 1.4 COMMENT '负责人积分倍率',
    manager_multiplier     DECIMAL(3, 1) NOT NULL DEFAULT 1.2 COMMENT '管理团队积分倍率',
    need_audit             TINYINT       NOT NULL DEFAULT 0 COMMENT '报名是否需审核 0否(默认报名即通过)/1是',
    enroll_scope           TINYINT       NOT NULL DEFAULT 0 COMMENT '报名范围 0全平台/1指定分队',
    target_squad_ids       VARCHAR(255)  DEFAULT NULL COMMENT '【临时】指定分队id列表（逗号分隔，enroll_scope=1时用；分队模块就绪后改关联表）',
    require_min_age        INT           DEFAULT NULL COMMENT '最小年龄要求（null不限）',
    require_max_age        INT           DEFAULT NULL COMMENT '最大年龄要求（null不限）',
    require_min_grade      INT           DEFAULT NULL COMMENT '最低年级要求（年级编码，null不限）',
    require_max_grade      INT           DEFAULT NULL COMMENT '最高年级要求（年级编码，null不限）',
    require_gender         TINYINT       DEFAULT NULL COMMENT '性别要求 null不限/1男/2女',
    require_min_join_count INT           NOT NULL DEFAULT 0 COMMENT '已参加活动次数门槛（默认0=不限）',
    min_projects           INT           NOT NULL DEFAULT 0 COMMENT '最少需报名项目数（默认0）',
    max_projects           INT           DEFAULT NULL COMMENT '最多可报名项目数（null=不限）',
    enroll_notice          TEXT          DEFAULT NULL COMMENT '报名须知（弹窗内容）',
    notice_countdown_sec   INT           NOT NULL DEFAULT 0 COMMENT '须知倒计时秒数（>0则倒计时结束才可确认）',
    success_tip_text       VARCHAR(512)  DEFAULT NULL COMMENT '报名成功提示文字（加群引导）',
    success_tip_image_url  VARCHAR(512)  DEFAULT NULL COMMENT '报名成功提示图片（群二维码）',
    status                 TINYINT       NOT NULL DEFAULT 0 COMMENT '状态 0草稿/1已发布/2已结束/3已取消',
    create_by              BIGINT        DEFAULT NULL COMMENT '发布人 admin_user.id',
    create_time            DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_time            DATETIME      DEFAULT NULL COMMENT '更新时间',
    is_deleted             TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_serial_no (serial_no),
    KEY idx_status_start_time (status, start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '志愿活动';

-- ============================================================
-- 活动时间段/子项目（一个活动多段；公示名 = 活动名 + 项目名）
-- 时间须落在所属活动的 start_time~end_time 内（应用层校验）。
-- idx_time 便于报名时的「同时间段重叠」冲突校验。
-- ============================================================
CREATE TABLE activity_slot (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id  BIGINT       NOT NULL COMMENT '所属活动 activity.id',
    project_name VARCHAR(128) NOT NULL COMMENT '项目名称',
    start_time   DATETIME     NOT NULL COMMENT '该时间段开始（精确到分钟）',
    end_time     DATETIME     NOT NULL COMMENT '该时间段结束',
    need_count   INT          NOT NULL DEFAULT 0 COMMENT '需求人数（0=不限）',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    KEY idx_activity (activity_id),
    KEY idx_time (start_time, end_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动时间段/子项目';

-- ============================================================
-- 报名记录（志愿者报名某活动的某时间段）
-- 防重复：不设 DB 唯一键，由 Redisson 锁 + 应用层校验「无活跃报名(status 0/1)」实现，
--   兼容「取消后再报名」并保留历史；全平台同时间段重叠防重(R22 CD)同样走锁+应用层。
-- 取消报名：update status=3（保留历史，谁报了又取消可追溯），不删行。
-- is_deleted（沿用 BaseEntity 逻辑删除）：仅供后台「删除报名记录」管理动作用，与志愿者取消无关。
-- proxy_by_volunteer_id：代报名人，本版搁置，字段预留。
-- ============================================================
CREATE TABLE activity_enrollment (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id           BIGINT       NOT NULL COMMENT '活动 activity.id',
    slot_id               BIGINT       NOT NULL COMMENT '时间段 activity_slot.id',
    volunteer_id          BIGINT       NOT NULL COMMENT '志愿者 volunteer.id',
    status                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0待审核/1已通过/2已拒绝/3已取消（应用层按 need_audit 设初值）',
    enroll_time           DATETIME     DEFAULT NULL COMMENT '报名时间',
    reject_reason         VARCHAR(255) DEFAULT NULL COMMENT '拒绝原因',
    proxy_by_volunteer_id BIGINT       DEFAULT NULL COMMENT '代报名人 volunteer.id（本版搁置，预留）',
    audit_by              BIGINT       DEFAULT NULL COMMENT '审核管理员 admin_user.id',
    audit_time            DATETIME     DEFAULT NULL COMMENT '审核时间',
    create_time           DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_time           DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删（后台删记录用）',
    PRIMARY KEY (id),
    KEY idx_volunteer_status (volunteer_id, status),
    KEY idx_activity_status (activity_id, status),
    KEY idx_slot (slot_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动报名记录';
