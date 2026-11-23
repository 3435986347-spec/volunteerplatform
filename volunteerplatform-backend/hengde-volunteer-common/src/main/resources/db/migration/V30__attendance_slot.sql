-- V30：考勤补上「场次」维度
--
-- 需求来源（不是新需求，是补齐 V1 未做到的既有要求）：
--   · 原型 `志愿平台设想【第十版】.pdf` P15「报名详情」逐行画着
--       姓名 / 报名时间 / 岗位时间 14:00-17:00 / 签到时间 / 签退时间
--     同一位志愿者出现多行、每行各带岗位时间与签到签退，且列表可「筛选：全部时间段」。
--     ——只有「一场次一条考勤」才画得出这个界面。
--   · 原型 P92「我的活动」详情同样是「岗位名称 + 考勤信息」成对出现。
--   · 协会 2026-07-30 答复「志愿者证书是根据他的场次来决定的，一场活动一个证书」与上述一致。
--
-- 现状：activity_attendance 只有 activity_id，且 uk_activity_volunteer(activity_id, volunteer_id)
--       限死「一人一活动一条考勤」；报名侧 activity_enrollment 已是场次粒度（slot_id）。
--       本迁移把考勤对齐到报名的粒度。

-- ---------------------------------------------------------------------------
-- 前置断言：不允许存在「活动一个场次都没有，却已经有考勤/违规」的数据。
--
-- 为什么是中止而不是删除：这种数据本就不该被任何有效业务流程产生，出现即说明数据不变量已经破坏。
-- 而考勤行不是孤立的——积分账本(point_record)、考勤变更审核(activity_attendance_change)、
-- 补录单(activity_backfill) 都挂着它。删掉考勤主体、留下这些关联事实，是把一处已知损坏
-- 换成多处静默的悬空引用，比迁移失败难查得多。归档表只作【推断审计】，不承担备份职责。
--
-- 触发中止后如何处置：
--   SELECT * FROM v30_precheck_orphan;            -- 列出全部问题行
--   然后二选一：给对应活动补上正确场次，或人工确认后删除这些考勤/违规行；
--   再 flyway repair 并重跑本迁移。
DROP TABLE IF EXISTS v30_precheck_orphan;
CREATE TABLE v30_precheck_orphan (
    table_name   VARCHAR(64) NOT NULL,
    row_id       BIGINT      NOT NULL,
    activity_id  BIGINT      NOT NULL,
    volunteer_id BIGINT      NOT NULL,
    PRIMARY KEY (table_name, row_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='V30 前置检查：无场次却有考勤/违规的问题行';

INSERT INTO v30_precheck_orphan (table_name, row_id, activity_id, volunteer_id)
SELECT 'activity_attendance', a.id, a.activity_id, a.volunteer_id
FROM activity_attendance a
WHERE NOT EXISTS (SELECT 1 FROM activity_slot s
                  WHERE s.activity_id = a.activity_id AND s.is_deleted = 0);

INSERT INTO v30_precheck_orphan (table_name, row_id, activity_id, volunteer_id)
SELECT 'activity_violation', v.id, v.activity_id, v.volunteer_id
FROM activity_violation v
WHERE NOT EXISTS (SELECT 1 FROM activity_slot s
                  WHERE s.activity_id = v.activity_id AND s.is_deleted = 0);

-- 闸门：orphan_rows 必须为 0，否则 CHECK 约束失败，整个迁移在此中止。
-- （MySQL 8.0.16+ 强制执行 CHECK；约束名即中止原因，会原样出现在报错里。）
DROP TABLE IF EXISTS v30_precheck_gate;
CREATE TABLE v30_precheck_gate (
    id          TINYINT NOT NULL PRIMARY KEY,
    orphan_rows INT     NOT NULL,
    CONSTRAINT ck_v30_abort_attendance_on_activity_without_slot CHECK (orphan_rows = 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO v30_precheck_gate (id, orphan_rows)
SELECT 1, COUNT(*) FROM v30_precheck_orphan;

-- ---------------------------------------------------------------------------
-- 审计表：本迁移所有【推断】出来的归属都留底，便于事后核对与人工纠正。
--
-- 为什么需要：旧 schema 的 uk_activity_volunteer(activity_id, volunteer_id) 决定了
-- 「一人一活动只有一条考勤」，它<b>物理上没有记录这条考勤属于哪一场</b>。
-- 因此回填只能推断，推断就可能错；而无论对错都不该无声无息。
--
-- 本表只记推断，不记删除——上面的前置断言已保证没有需要删除的行。
CREATE TABLE IF NOT EXISTS v30_attendance_slot_backfill_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    table_name    VARCHAR(64)  NOT NULL COMMENT '来源表 activity_attendance / activity_violation',
    row_id        BIGINT       NOT NULL COMMENT '来源行 id',
    activity_id   BIGINT       NULL,
    volunteer_id  BIGINT       NULL,
    resolved_slot BIGINT       NOT NULL COMMENT '推断出的 slot_id',
    resolution    VARCHAR(32)  NOT NULL COMMENT 'enrollment=按报名推断（较强）/ earliest_slot=按活动最早场次兜底（较弱，优先人工复核）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_table_row (table_name, row_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='V30 考勤/违规场次回填推断留底';

ALTER TABLE activity_attendance
    ADD COLUMN slot_id BIGINT NULL COMMENT '场次 activity_slot.id（V30 新增）' AFTER activity_id;

-- 回填：优先取该志愿者在本活动「已通过」的报名场次；同一活动多场报名时取最早的一场。
--
-- ⚠️ 这一步是【推断】，不是数据迁移：一人报了上下午两场却只有一条考勤时，旧数据无法表达他到底签的哪一场，
--    这里取最早的一场。所有被推断的行都写入 v30_attendance_slot_backfill_log，可事后核对。
-- 说明：尚未上线，库中仅有开发/测试数据，故单值推断可接受；
--       真实多场次考勤将在改造后由签到接口逐场写入，不依赖本回填。
UPDATE activity_attendance a
SET a.slot_id = (
    SELECT e.slot_id
    FROM activity_enrollment e
             JOIN activity_slot s ON s.id = e.slot_id AND s.is_deleted = 0
    WHERE e.activity_id = a.activity_id
      AND e.volunteer_id = a.volunteer_id
      AND e.status = 1
      AND e.is_deleted = 0
    ORDER BY s.start_time, s.id
    LIMIT 1
)
WHERE a.slot_id IS NULL;

-- 留底：按报名推断出来的行
INSERT INTO v30_attendance_slot_backfill_log
    (table_name, row_id, activity_id, volunteer_id, resolved_slot, resolution)
SELECT 'activity_attendance', a.id, a.activity_id, a.volunteer_id, a.slot_id, 'enrollment'
FROM activity_attendance a
WHERE a.slot_id IS NOT NULL;

-- 兜底：无报名记录的考勤（后台补录/历史脏数据）挂到该活动最早的一个场次。
-- ⚠️ 比上一步更弱的推断——这些行连报名都没有，只能挂到活动的第一场。同样留底。
UPDATE activity_attendance a
SET a.slot_id = (
    SELECT s.id
    FROM activity_slot s
    WHERE s.activity_id = a.activity_id
      AND s.is_deleted = 0
    ORDER BY s.start_time, s.id
    LIMIT 1
)
WHERE a.slot_id IS NULL;

-- 留底：靠「活动最早场次」兜底挂上的行
INSERT INTO v30_attendance_slot_backfill_log
    (table_name, row_id, activity_id, volunteer_id, resolved_slot, resolution)
SELECT 'activity_attendance', a.id, a.activity_id, a.volunteer_id, a.slot_id, 'earliest_slot'
FROM activity_attendance a
WHERE a.slot_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM v30_attendance_slot_backfill_log g
                  WHERE g.table_name = 'activity_attendance' AND g.row_id = a.id);

-- 到这里不可能再有 NULL：前置断言已排除「活动无场次」，其余行必被上面两步之一挂上。
-- 万一仍有（说明断言与回填条件不一致，属实现 bug），NOT NULL 会在下一句直接失败——这是期望行为。
ALTER TABLE activity_attendance
    MODIFY COLUMN slot_id BIGINT NOT NULL COMMENT '场次 activity_slot.id（V30 新增）';

-- 唯一键换成场次粒度：一人一场次一条考勤。
-- 保留 activity_id 作前导列，既维持既有「按活动查某人考勤」的索引效率，也让唯一性落在场次上。
ALTER TABLE activity_attendance
    DROP INDEX uk_activity_volunteer,
    ADD UNIQUE KEY uk_activity_volunteer_slot (activity_id, volunteer_id, slot_id),
    ADD KEY idx_slot (slot_id);

-- ---------------------------------------------------------------------------
-- 违规记录同样下沉到场次
--
-- 需求来源：`小程序设想【第十版】.xlsx` · 前端 · Row 32「C 前端信息」（活动负责人）逐字写着：
--   「显示【活动场次】，活动名称，【活动时间段】，参加志愿者人数……或者负责人点击志愿者是否到位
--     （正常到位，请假，迟到，缺席），【活动过程中志愿者是否违规】（如玩手机，服装不合格……）」
-- 即：负责人的管理界面本就是【按场次】组织的，「到位状态」与「违规」都记在那个场次的界面里。
-- 到位状态挂在 activity_attendance 上（已随本迁移下沉），违规此前只有 activity_id，故一并补齐。
ALTER TABLE activity_violation
    ADD COLUMN slot_id BIGINT NULL COMMENT '场次 activity_slot.id（V30 新增）' AFTER activity_id;

UPDATE activity_violation v
SET v.slot_id = (
    SELECT a.slot_id
    FROM activity_attendance a
    WHERE a.activity_id = v.activity_id
      AND a.volunteer_id = v.volunteer_id
      AND a.is_deleted = 0
    ORDER BY a.id
    LIMIT 1
)
WHERE v.slot_id IS NULL;

-- 留底：按同人同活动的考勤行推断出来的违规归属
INSERT INTO v30_attendance_slot_backfill_log
    (table_name, row_id, activity_id, volunteer_id, resolved_slot, resolution)
SELECT 'activity_violation', v.id, v.activity_id, v.volunteer_id, v.slot_id, 'enrollment'
FROM activity_violation v
WHERE v.slot_id IS NOT NULL;

UPDATE activity_violation v
SET v.slot_id = (
    SELECT s.id FROM activity_slot s
    WHERE s.activity_id = v.activity_id AND s.is_deleted = 0
    ORDER BY s.start_time, s.id LIMIT 1
)
WHERE v.slot_id IS NULL;

-- 留底：靠「活动最早场次」兜底挂上的违规
INSERT INTO v30_attendance_slot_backfill_log
    (table_name, row_id, activity_id, volunteer_id, resolved_slot, resolution)
SELECT 'activity_violation', v.id, v.activity_id, v.volunteer_id, v.slot_id, 'earliest_slot'
FROM activity_violation v
WHERE v.slot_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM v30_attendance_slot_backfill_log g
                  WHERE g.table_name = 'activity_violation' AND g.row_id = v.id);

-- 同考勤：前置断言已排除「活动无场次」，此处不应再有 NULL。
ALTER TABLE activity_violation
    MODIFY COLUMN slot_id BIGINT NOT NULL COMMENT '场次 activity_slot.id（V30 新增）',
    ADD KEY idx_violation_slot (slot_id);

-- ---------------------------------------------------------------------------
-- 走到这里说明前置断言通过、回填全部落位，检查用的临时表可以撤了。
-- （中止时这两张表会留在库里，正是为了让人查出问题行。）
DROP TABLE IF EXISTS v30_precheck_gate;
DROP TABLE IF EXISTS v30_precheck_orphan;
