-- 活动临时负责人考试（V4 临时负责人考试批）：试卷 / 试题 / 答卷 / 资格 + 3 个权限点。
--
-- 【需求出处】xlsx Row 14 / Row 45「跟考试一样，考试分数达到多少，即可获得这个资格，如果志愿者对他评价过低，由组织部的同学审核过后，
--   可以给他取消活动临时负责人资格，也可以直接取消。有历史考试板块」；Row 14 D「单项选择题，多项选择题、判断题、填空题和简答题」；
--   Row 45 F「填空题等主观题需要人工审核」；Row 14 F「管理活动临时负责人，管理考试试题，信息批量导出」；Row 24「所属职务」；
--   V4规划 D3（与问卷共用题型与答案校验、不共用表）、D4（资格是一张表、按时间现算）、Q6（及格线与有效期可配、评价过低由组织部手动撤销）。
--
-- 【同一时刻至多一份开放中的试卷】生成列唯一键。【一个人至多一份待阅卷的答卷】生成列唯一键（阅完才能再考）。
-- 【资格】一个人至多一条没撤销的资格行（生成列唯一键）；到期的在授予新资格时先以「资格到期」收尾。「当前是不是临时负责人」按时间现算，不靠定时任务。
-- 【本文件形态】全部新建表 + 权限点 INSERT，新库上没有失败的可能。

CREATE TABLE org_exam_paper (
    id                   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    title                VARCHAR(100)  NOT NULL COMMENT '试卷名称',
    description          VARCHAR(1000) DEFAULT NULL COMMENT '说明',
    pass_score           INT           NOT NULL COMMENT '及格线（总分达到即获得资格）',
    total_score          INT           NOT NULL COMMENT '满分（各题分值之和）',
    qualification_months INT           DEFAULT NULL COMMENT '资格有效期（月）；为空＝长期有效',
    status               TINYINT       NOT NULL DEFAULT 0 COMMENT '0 草稿 / 1 开放中 / 2 已停止',
    created_by           BIGINT        DEFAULT NULL COMMENT '创建人 admin_user.id',
    create_time          DATETIME      NOT NULL COMMENT '创建时间',
    update_time          DATETIME      DEFAULT NULL COMMENT '更新时间',
    is_deleted           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_open_key      TINYINT GENERATED ALWAYS AS (CASE WHEN status = 1 AND is_deleted = 0 THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_open (active_open_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '临时负责人考试试卷';

CREATE TABLE org_exam_question (
    id                   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id             BIGINT        NOT NULL COMMENT '试卷',
    sort                 INT           NOT NULL COMMENT '题号',
    question_type        TINYINT       NOT NULL COMMENT '1 单选 / 2 多选 / 3 判断 / 4 填空 / 5 简答（与问卷共用题型）',
    title                VARCHAR(500)  NOT NULL COMMENT '题目',
    description          VARCHAR(1000) DEFAULT NULL COMMENT '补充说明',
    options_json         TEXT          DEFAULT NULL COMMENT '选项（单选 / 多选）',
    config_json          TEXT          DEFAULT NULL COMMENT '题目配置（与问卷同一格式）',
    answer_json          TEXT          DEFAULT NULL COMMENT '标准答案：单选 / 多选 / 判断必填，自动判分；填空 / 简答为参考答案，给阅卷人看',
    score                INT           NOT NULL COMMENT '分值',
    create_time          DATETIME      NOT NULL COMMENT '创建时间',
    update_time          DATETIME      DEFAULT NULL COMMENT '更新时间',
    is_deleted           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_paper (paper_id, is_deleted, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '临时负责人考试试题';

CREATE TABLE org_exam_attempt (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id           BIGINT       NOT NULL COMMENT '试卷',
    volunteer_id       BIGINT       NOT NULL COMMENT '考生',
    answers_json       TEXT         NOT NULL COMMENT '作答（规范化后，与问卷答卷同一格式）',
    objective_score    INT          NOT NULL COMMENT '客观题得分（交卷时自动判）',
    subjective_json    TEXT         DEFAULT NULL COMMENT '主观题逐题得分（阅卷后）',
    subjective_score   INT          DEFAULT NULL COMMENT '主观题得分（阅卷后）',
    total_score        INT          DEFAULT NULL COMMENT '总分（出分后）',
    passed             TINYINT      DEFAULT NULL COMMENT '1 及格 / 0 不及格（出分后）',
    status             TINYINT      NOT NULL COMMENT '1 待阅卷 / 2 已出分',
    grade_note         VARCHAR(255) DEFAULT NULL COMMENT '阅卷备注',
    graded_by          BIGINT       DEFAULT NULL COMMENT '阅卷人 admin_user.id',
    graded_time        DATETIME     DEFAULT NULL COMMENT '出分时间',
    submit_time        DATETIME     NOT NULL COMMENT '交卷时间',
    active_pending_key BIGINT GENERATED ALWAYS AS (CASE WHEN status = 1 THEN volunteer_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_pending (active_pending_key),
    KEY idx_volunteer (volunteer_id, id),
    KEY idx_paper_status (paper_id, status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '临时负责人考试答卷（历史考试板块）';

CREATE TABLE org_temp_leader_qualification (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id  BIGINT       NOT NULL COMMENT '志愿者',
    source_type   TINYINT      NOT NULL DEFAULT 1 COMMENT '来源 1 考试',
    attempt_id    BIGINT       DEFAULT NULL COMMENT '来源答卷',
    granted_time  DATETIME     NOT NULL COMMENT '获得时间',
    expire_time   DATETIME     DEFAULT NULL COMMENT '到期时间；为空＝长期有效',
    revoked_by    BIGINT       DEFAULT NULL COMMENT '撤销人 admin_user.id（到期收尾时为空）',
    revoked_time  DATETIME     DEFAULT NULL COMMENT '撤销 / 收尾时间',
    revoke_reason VARCHAR(255) DEFAULT NULL COMMENT '撤销原因',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    active_key    BIGINT GENERATED ALWAYS AS (CASE WHEN revoked_time IS NULL THEN volunteer_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_qualification (active_key),
    KEY idx_volunteer (volunteer_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动临时负责人资格（D4：一张资格表，按时间现算）';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('org:exam', '临时负责人考试试题管理（试卷 / 题目 / 开放停止）', 'org', 2, 93, NOW(), 0),
('org:exam-grade', '临时负责人考试阅卷（主观题人工判分）', 'org', 2, 94, NOW(), 0),
('org:temp-leader', '活动临时负责人管理（名单 / 撤销资格 / 批量导出）', 'org', 2, 95, NOW(), 0);
