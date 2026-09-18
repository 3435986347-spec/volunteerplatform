-- 问卷引擎（V4 问卷引擎批）。
--
-- 【需求出处】xlsx Row 43 投诉建议「类似于问卷收集」、Row 44 报名管理团队「类似于简历提交 / 批量下载」、
--   Row 46 报名管理团队 / Row 47 评优评先「类似于一个问卷调查。功能含有：单选题、多选题、判断题、简答题、文件上传、日期选择」、
--   Row 48 意见反馈「类似于问卷收集、后台设置」。一个引擎服务五处（V4规划 D2），落 organization、表名前缀 org_form_。
--
-- 【题型】1 单选 / 2 多选 / 3 判断 / 4 填空 / 5 简答 / 6 文件 / 7 日期。考试（临时负责人考试批）共用题型、不共用这几张表（D3）。
--
-- 【发布后题目冻结】已经有人按这套题作答，再改题会让旧答卷的含义跟着变（选项 B 从「是」改成「否」，历史答卷就说反了）。
--   所以只有草稿能改题；收集中 / 已停止的问卷要改，复制出一份新草稿。答卷因此只存题目 id + 值，不必快照题面。
--
-- 【一个场景同时至多一份收集中的问卷】报名管理团队、评优评先这类「挂在某个流程上」的问卷，志愿者端只认一份当前问卷；
--   两份同时收集会让志愿者看到哪份取决于排序。通用问卷（scene = 1）不设这条。用生成列唯一键兜底，不靠先查再发布。
--
-- 【每人一次】由答卷上快照的 single_submit 决定生成列取不取值——发布之后改不了「每人一次」这个设置
--   （题目冻结的同一个理由），快照只是让唯一键不必去连表。
--
-- 【本文件形态】三条 CREATE TABLE（新表，不会与存量冲突）+ 两个权限点。

CREATE TABLE org_form (
    id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    scene              TINYINT       NOT NULL DEFAULT 1 COMMENT '1通用问卷/2报名管理团队/3评优评先/4意见反馈/5投诉建议',
    title              VARCHAR(128)  NOT NULL COMMENT '问卷标题',
    description        VARCHAR(2000)          DEFAULT NULL COMMENT '问卷说明',
    status             TINYINT       NOT NULL DEFAULT 0 COMMENT '0草稿/1收集中/2已停止',
    start_time         DATETIME               DEFAULT NULL COMMENT '开始收集时间（空＝发布即开始）',
    end_time           DATETIME               DEFAULT NULL COMMENT '截止时间（空＝直到手动停止）',
    single_submit      TINYINT       NOT NULL DEFAULT 1 COMMENT '1每人一次/0不限次数',
    require_registered TINYINT       NOT NULL DEFAULT 1 COMMENT '1须已实名才能填写/0游客也可填写',
    publish_time       DATETIME               DEFAULT NULL COMMENT '发布时间',
    publish_by         BIGINT                 DEFAULT NULL COMMENT '发布人 admin_user.id',
    close_time         DATETIME               DEFAULT NULL COMMENT '停止收集时间',
    create_by          BIGINT        NOT NULL COMMENT '创建人 admin_user.id',
    create_time        DATETIME      NOT NULL COMMENT '创建时间',
    update_time        DATETIME               DEFAULT NULL COMMENT '更新时间',
    is_deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（仅草稿可删）',
    active_scene_key   TINYINT GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND status = 1 AND scene <> 1 THEN scene ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_scene (active_scene_key),
    KEY idx_scene_status (scene, status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问卷（V4 问卷引擎批）';

CREATE TABLE org_form_question (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    form_id       BIGINT        NOT NULL COMMENT '所属问卷',
    sort          INT           NOT NULL DEFAULT 0 COMMENT '题号（从 1 起，保存时按提交顺序重排）',
    question_type TINYINT       NOT NULL COMMENT '1单选/2多选/3判断/4填空/5简答/6文件/7日期',
    title         VARCHAR(512)  NOT NULL COMMENT '题目',
    description   VARCHAR(512)           DEFAULT NULL COMMENT '题目补充说明',
    required      TINYINT       NOT NULL DEFAULT 1 COMMENT '是否必答',
    options_json  TEXT                   DEFAULT NULL COMMENT '选项（单选 / 多选）：[{"key":"A","label":"…"}]，key 由服务端按顺序分配',
    config_json   VARCHAR(1024)          DEFAULT NULL COMMENT '题型配置：字数上限 / 最少最多选几项 / 最多几个文件 / 日期范围',
    create_time   DATETIME      NOT NULL COMMENT '创建时间',
    update_time   DATETIME               DEFAULT NULL COMMENT '更新时间',
    is_deleted    TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（草稿改题时整批替换）',
    PRIMARY KEY (id),
    KEY idx_form (form_id, sort, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问卷题目（V4 问卷引擎批）';

CREATE TABLE org_form_submission (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    form_id           BIGINT       NOT NULL COMMENT '问卷',
    scene             TINYINT      NOT NULL COMMENT '【快照】问卷场景',
    volunteer_id      BIGINT       NOT NULL COMMENT '填写人 volunteer.id',
    single_submit     TINYINT      NOT NULL COMMENT '【快照】问卷是否每人一次',
    answers_json      MEDIUMTEXT   NOT NULL COMMENT '答卷：[{"questionId":1,"value":…}]，值已按题型规范化',
    submit_time       DATETIME     NOT NULL COMMENT '提交时间',
    create_time       DATETIME     NOT NULL COMMENT '创建时间',
    update_time       DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_single_key VARCHAR(48) GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND single_submit = 1 THEN CONCAT(form_id, ':', volunteer_id) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_single (active_single_key),
    KEY idx_form_time (form_id, submit_time, id),
    KEY idx_volunteer (volunteer_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问卷答卷（V4 问卷引擎批）';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('org:form', '问卷管理（建 / 改 / 发布 / 停止）', 'org', 2, 73, NOW(), 0);
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('org:form-data', '问卷答卷查看与导出', 'org', 2, 74, NOW(), 0);
