-- V10 activity 域：签到/签退/服务时长/积分发放闭环 + 活动现场负责人（V1.1 第 1 批·主干闭环）
-- 对应需求：扫码/GPS 自助签到、活动现场负责人(后台预设, 志愿者或管理团队)、到位状态、违规记录、
--   统一签退算时长、秘书部确认、服务记录大板块、积分发放(负责人×1.4/管理团队×1.2/普通×1.0, 违规减半/不发)、
--   活动开始/结束、确认到家、活动总结。
-- 周边字段(确认到家/双向评价/总结/到家定位)一并在本迁移建好，避免第 2 批再 ALTER；第 1 批代码只用主干列。
-- 活动留言/相册/补录 属第 2、3 批，单独迁移（V11+）。
-- 公共列(id/create_time/update_time/is_deleted)见 com.hengde.common.entity.BaseEntity。

-- ============================================================
-- 1) activity 主表补「现场运行 + GPS 签到 + 总结 + 已参加时长门槛」字段
--   lat/lng/check_in_radius_m：GPS 签到校验（志愿者上报坐标距活动坐标 ≤ 半径，默认 500m）。
--   run_status：现场运行态，与发布态 status 正交——status 管「草稿/已发布/结束/取消」，run_status 管负责人「未开始/进行中/已结束」。
--   require_min_join_minutes：报名「已参加时长门槛」（默认 0=不限，第 2 批 eligibility 用）。
-- ============================================================
ALTER TABLE activity
    ADD COLUMN lat                      DECIMAL(10, 7) DEFAULT NULL COMMENT '活动地点纬度（GPS 签到用）',
    ADD COLUMN lng                      DECIMAL(10, 7) DEFAULT NULL COMMENT '活动地点经度（GPS 签到用）',
    ADD COLUMN check_in_radius_m        INT      NOT NULL DEFAULT 500 COMMENT '签到半径（米，默认 500）',
    ADD COLUMN run_status               TINYINT  NOT NULL DEFAULT 0 COMMENT '现场运行状态 0未开始/1进行中/2已结束（与 status 发布态正交）',
    ADD COLUMN actual_start_time        DATETIME DEFAULT NULL COMMENT '负责人点「活动开始」时间',
    ADD COLUMN actual_end_time          DATETIME DEFAULT NULL COMMENT '负责人点「活动结束」时间',
    ADD COLUMN summary_text             TEXT     DEFAULT NULL COMMENT '活动总结文字（负责人上传）',
    ADD COLUMN summary_images           VARCHAR(2048) DEFAULT NULL COMMENT '活动总结图片URL（逗号分隔）',
    ADD COLUMN summary_by               BIGINT   DEFAULT NULL COMMENT '总结上传人（volunteer.id 或 admin_user.id）',
    ADD COLUMN summary_time             DATETIME DEFAULT NULL COMMENT '总结上传时间',
    ADD COLUMN require_min_join_minutes INT      NOT NULL DEFAULT 0 COMMENT '已参加活动时长门槛（分钟，默认0=不限）';

-- ============================================================
-- 2) 活动现场负责人（后台预设；可多名；不占活动报名人数）
--   leader_type=1：从报名志愿者中选，volunteer_id 有值；积分发放时该志愿者按 ×1.4(leader_multiplier)。
--   leader_type=2：从管理团队安排，admin_user_id 有值；管理团队同学也有活动管理权限，不产生志愿者积分。
--   唯一键用复合列（含 NULL 那一侧 MySQL 不约束），保证同一活动不重复指派同一人。
-- ============================================================
CREATE TABLE activity_leader (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id   BIGINT   NOT NULL COMMENT '活动 activity.id',
    leader_type   TINYINT  NOT NULL COMMENT '负责人来源 1=报名志愿者/2=管理团队',
    volunteer_id  BIGINT   DEFAULT NULL COMMENT 'leader_type=1 时的 volunteer.id',
    admin_user_id BIGINT   DEFAULT NULL COMMENT 'leader_type=2 时的 admin_user.id',
    assigned_by   BIGINT   DEFAULT NULL COMMENT '指派人（组织部）admin_user.id',
    assigned_time DATETIME DEFAULT NULL COMMENT '指派时间',
    create_time   DATETIME DEFAULT NULL COMMENT '创建时间',
    update_time   DATETIME DEFAULT NULL COMMENT '更新时间',
    is_deleted    TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    KEY idx_activity (activity_id),
    KEY idx_volunteer (volunteer_id),
    UNIQUE KEY uk_act_vol (activity_id, volunteer_id),
    UNIQUE KEY uk_act_admin (activity_id, admin_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动现场负责人';

-- ============================================================
-- 3) 活动考勤 / 服务记录（每活动每志愿者一条）——闭环核心表
--   粒度：活动级（不是时间段级）。统一签退、GPS 一次签到都是活动级；岗位时间从其报名行带出。
--   service_minutes：签退算出 = 签退−签到（分钟）；缺席/请假置 0。
--   secretary_status：秘书部确认后才汇入「服务记录大板块」并据以发积分。
--   points_factor：违规时由发放人定 0正常/1减半/2不发；points_award=基数×倍率×系数（请假/缺席=0）。
--   评价/确认到家列在本迁移先建好（第 2 批填充）。
-- ============================================================
CREATE TABLE activity_attendance (
    id                BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id       BIGINT   NOT NULL COMMENT '活动 activity.id',
    volunteer_id      BIGINT   NOT NULL COMMENT '志愿者 volunteer.id',
    check_in_time     DATETIME DEFAULT NULL COMMENT '签到时间',
    check_in_method   TINYINT  DEFAULT NULL COMMENT '签到方式 1扫码/2到点自动定位/3负责人确认',
    check_in_by       BIGINT   DEFAULT NULL COMMENT '签到登记人（自助=本人 volunteer.id；负责人代标=负责人）',
    check_in_lat      DECIMAL(10, 7) DEFAULT NULL COMMENT '签到上报纬度',
    check_in_lng      DECIMAL(10, 7) DEFAULT NULL COMMENT '签到上报经度',
    check_out_time    DATETIME DEFAULT NULL COMMENT '签退时间（负责人统一点）',
    check_out_by      BIGINT   DEFAULT NULL COMMENT '签退登记人',
    attend_status     TINYINT  DEFAULT NULL COMMENT '到位状态 1正常到位/2请假/3迟到/4缺席（null未标）',
    service_minutes   INT      DEFAULT NULL COMMENT '服务时长（分钟，签退算出/可后台改）',
    confirm_home_time DATETIME DEFAULT NULL COMMENT '志愿者确认到家时间（活动结束1h内）',
    confirm_home_lat  DECIMAL(10, 7) DEFAULT NULL COMMENT '确认到家上报纬度',
    confirm_home_lng  DECIMAL(10, 7) DEFAULT NULL COMMENT '确认到家上报经度',
    leader_evaluation VARCHAR(512) DEFAULT NULL COMMENT '负责人对该志愿者评价（第2批）',
    vol_activity_score TINYINT DEFAULT NULL COMMENT '志愿者对活动评分（第2批）',
    vol_leader_score  TINYINT  DEFAULT NULL COMMENT '志愿者对负责人评分（第2批）',
    vol_comment       VARCHAR(512) DEFAULT NULL COMMENT '志愿者评价留言（第2批）',
    secretary_status  TINYINT  NOT NULL DEFAULT 0 COMMENT '秘书部确认 0待确认/1已确认',
    secretary_by      BIGINT   DEFAULT NULL COMMENT '秘书部确认人 admin_user.id',
    secretary_time    DATETIME DEFAULT NULL COMMENT '秘书部确认时间',
    points_award      INT      DEFAULT NULL COMMENT '实发积分（确认后据 基数×倍率×违规系数 落值）',
    points_status     TINYINT  NOT NULL DEFAULT 0 COMMENT '积分发放 0未发/1已发',
    points_factor     TINYINT  NOT NULL DEFAULT 0 COMMENT '积分调整 0正常/1减半/2不发（违规时由发放人定）',
    create_time       DATETIME DEFAULT NULL COMMENT '创建时间',
    update_time       DATETIME DEFAULT NULL COMMENT '更新时间',
    is_deleted        TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_volunteer (activity_id, volunteer_id),
    KEY idx_volunteer (volunteer_id),
    KEY idx_secretary (secretary_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动考勤与服务记录';

-- ============================================================
-- 4) 违规记录（每活动每志愿者可多条）
--   缺席自动写一条 type=5；负责人手动记 1玩手机/2服装不合格/3早退/4长时间交头接耳。
--   积分发放据是否有违规决定减半/不发（points_factor）。
-- ============================================================
CREATE TABLE activity_violation (
    id             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id    BIGINT   NOT NULL COMMENT '活动 activity.id',
    volunteer_id   BIGINT   NOT NULL COMMENT '志愿者 volunteer.id',
    violation_type TINYINT  NOT NULL DEFAULT 0 COMMENT '类型 1玩手机/2服装不合格/3早退/4长时间交头接耳/5缺席/0其他',
    description    VARCHAR(512) DEFAULT NULL COMMENT '违规说明',
    recorded_by    BIGINT   DEFAULT NULL COMMENT '记录人（负责人 volunteer.id 或 admin_user.id）',
    recorded_time  DATETIME DEFAULT NULL COMMENT '记录时间',
    create_time    DATETIME DEFAULT NULL COMMENT '创建时间',
    update_time    DATETIME DEFAULT NULL COMMENT '更新时间',
    is_deleted     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    KEY idx_activity_vol (activity_id, volunteer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动违规记录';

-- ============================================================
-- 5) activity 域新增权限点（接 V4 的 sort=28 之后，29~34）
--   type：1菜单级 / 2功能级 / 3审核流程级（与 V2 一致）。
--   超管走 * 通配不受影响；其余角色需后台重新分配后方可操作。
--   attendance-edit/audit 属第 2 批（组织部改+部长二次审核），一并先种子，避免再开迁移。
-- ============================================================
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('activity:leader-assign',   '指派活动负责人',   'activity', 2, 29, NOW(), 0),
('activity:manage',          '活动现场管理',     'activity', 2, 30, NOW(), 0),
('activity:service-confirm', '秘书部确认时长',   'activity', 3, 31, NOW(), 0),
('activity:points-grant',    '活动积分发放',     'activity', 2, 32, NOW(), 0),
('activity:attendance-edit', '改签到签退积分',   'activity', 2, 33, NOW(), 0),
('activity:attendance-audit','考勤变更二次审核', 'activity', 3, 34, NOW(), 0);
