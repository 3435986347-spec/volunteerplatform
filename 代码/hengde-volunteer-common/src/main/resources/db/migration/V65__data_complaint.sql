-- 投诉建议（V4 投诉建议批）。
--
-- 【需求出处】xlsx Row 43「投诉建议 / 类似于问卷收集 / 投诉默认到监察部，后续监察部可根据工作需要选择流转到其他部门，含处理进度」。
--
-- 【一张工单表 + 一张进度表】工单记「现在在哪个部门、到了哪一步、最后怎么答复的」；进度表只追加，每一次提交 / 受理 / 流转 /
--   答复 / 内部备注各一行——「含处理进度」给志愿者看的正是其中 visible = 1 的那几行（内部备注与流转理由只给后台看）。
--   不在工单上叠一串「上一个部门、上上个部门」列：流转次数没有上限。
--
-- 【部门是字符串】admin_user.department 本就是自由文本（V1），没有部门主数据表；流转目标取「当前有启用账号的部门」（V4规划 Q7 默认），
--   这样转过去的工单一定有人看得到。默认部门由配置给（hengde.data.complaint.default-department，默认「监察部」）。
--
-- 【类似于问卷收集】固定两项（类型 + 内容，外加可选图片）照旧必填；协会在后台为「投诉建议」场景发布了问卷时，答卷随工单一起提交，
--   form_submission_id 记下（与报名管理团队 V64 同一形状）。
--
-- 【本文件形态】两条 CREATE TABLE（新表）+ 两个权限点。

CREATE TABLE data_complaint (
    id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    complaint_no       VARCHAR(32)   NOT NULL COMMENT '对外编号（短信里的「编号」）',
    volunteer_id       BIGINT        NOT NULL COMMENT '提交人 volunteer.id',
    complaint_type     TINYINT       NOT NULL COMMENT '1投诉/2建议',
    content            VARCHAR(2000) NOT NULL COMMENT '内容',
    images             VARCHAR(3200)          DEFAULT NULL COMMENT '图片 URL，逗号分隔，最多 6 张（只收本系统上传的）',
    form_submission_id BIGINT                 DEFAULT NULL COMMENT '随工单提交的问卷答卷 org_form_submission.id（该场景没有问卷时为空）',
    status             TINYINT       NOT NULL DEFAULT 0 COMMENT '0待受理/1处理中/2已办结',
    current_department VARCHAR(32)   NOT NULL COMMENT '当前处理部门（admin_user.department 的取值）',
    transfer_count     INT           NOT NULL DEFAULT 0 COMMENT '流转次数',
    accept_by          BIGINT                 DEFAULT NULL COMMENT '当前部门的受理人 admin_user.id（流转后清空）',
    accept_time        DATETIME               DEFAULT NULL COMMENT '受理时间',
    reply_content      VARCHAR(2000)          DEFAULT NULL COMMENT '答复（办结时填写，志愿者可见）',
    reply_by           BIGINT                 DEFAULT NULL COMMENT '答复人 admin_user.id',
    reply_department   VARCHAR(32)            DEFAULT NULL COMMENT '【快照】答复时所在部门',
    reply_time         DATETIME               DEFAULT NULL COMMENT '办结时间',
    create_time        DATETIME      NOT NULL COMMENT '提交时间',
    update_time        DATETIME               DEFAULT NULL COMMENT '更新时间',
    is_deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_complaint_no (complaint_no),
    KEY idx_volunteer (volunteer_id, id),
    KEY idx_dept_status (current_department, status, id),
    KEY idx_status (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投诉建议工单（Row 43，V4 投诉建议批）';

CREATE TABLE data_complaint_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    complaint_id    BIGINT        NOT NULL COMMENT '工单',
    action          TINYINT       NOT NULL COMMENT '1提交/2受理/3流转/4答复办结/5内部备注',
    from_department VARCHAR(32)            DEFAULT NULL COMMENT '流转前部门',
    to_department   VARCHAR(32)            DEFAULT NULL COMMENT '流转后部门（提交时为默认部门）',
    content         VARCHAR(2000)          DEFAULT NULL COMMENT '说明：流转理由 / 答复 / 备注',
    visible         TINYINT       NOT NULL DEFAULT 1 COMMENT '1志愿者可见（处理进度）/0仅后台',
    operator_type   TINYINT       NOT NULL COMMENT '1志愿者/2管理员',
    operator_id     BIGINT        NOT NULL COMMENT '操作人 id（按 operator_type 分 volunteer / admin_user）',
    create_time     DATETIME      NOT NULL COMMENT '发生时间',
    update_time     DATETIME               DEFAULT NULL COMMENT '更新时间',
    is_deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除；本表不提供删除入口',
    PRIMARY KEY (id),
    KEY idx_complaint (complaint_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投诉建议处理进度（只追加）';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('data:complaint', '投诉建议处理（本部门工单：受理 / 流转 / 答复 / 备注）', 'data', 2, 75, NOW(), 0);
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('data:complaint-all', '投诉建议全部门（看全部工单并可代任何部门处理，默认给监察部）', 'data', 2, 76, NOW(), 0);
