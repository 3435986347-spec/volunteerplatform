-- 荣誉排行榜（V2 第 2 批）。
--
-- 需求：4 个板块（活动次数 / 活动时长 / 积分 / 微心愿）× 3 个周期（月 / 年 / 总），
--   并要一个下拉框能看历史月份的排行。**微心愿排行本批不做**——它的数据源属 donate 领域，尚未建设；
--   rank_type 预留取值 4，V3 建完 donate 后直接接入，不必改表结构。
--
-- 为什么要快照表，而不是每次都实时算：
--   历史名次必须**冻结**。志愿者的历史数据事后会变（活动补录、考勤修正、积分手工调整），
--   若「2026 年 7 月排行」每次都按当前数据实时聚合，同一个历史月份今天看和明天看名次会不一样，
--   下拉框选历史月份就失去意义。故：**当期实时聚合，往期读快照**。
--
-- 「总榜」（period_type=3）恒为当期，**不快照**——它没有「历史的总榜」这种东西，永远实时算。
--   本表因此只会有 period_type ∈ {1,2} 的行。
CREATE TABLE honor_ranking_snapshot (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    period_type  TINYINT     NOT NULL COMMENT '周期 1月/2年（3总榜恒为当期，不快照）',
    -- 变长文本而非拆成 year/month 两列：periodKey 是前端下拉框直接传回来的值，
    -- 月用 '2026-07'、年用 '2026'，一个字段既能等值查询又能直接展示，省掉两侧的拼装与解析。
    period_key   VARCHAR(16) NOT NULL COMMENT '周期标识：月 2026-07 / 年 2026',
    rank_type    TINYINT     NOT NULL COMMENT '榜单 1活动次数/2活动时长/3积分/4微心愿（V3 预留）',
    volunteer_id BIGINT      NOT NULL COMMENT '志愿者id',
    rank_no      INT         NOT NULL COMMENT '名次，从 1 起',
    -- BIGINT 而非 INT：时长榜的单位是分钟，总榜维度下累计分钟数会远超次数与积分的量级。
    -- 三个板块共用一列，取最宽的类型，避免时长榜溢出。
    metric_value BIGINT      NOT NULL COMMENT '指标值：次数/分钟/积分，随 rank_type 变',
    create_time  DATETIME    DEFAULT NULL,
    update_time  DATETIME    DEFAULT NULL,
    is_deleted   TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 幂等的落点：一个志愿者在「某周期某板块」里只能有一行。快照生成是可重跑的（定时任务失败要补、
    -- 历史月份首次上线要批量补），靠这个唯一键做覆盖写，重跑不会产生重复行。
    UNIQUE KEY uk_period_rank_volunteer (period_type, period_key, rank_type, volunteer_id),
    -- 读榜单的唯一形态：定周期定板块、按名次正序取前 N。
    KEY idx_query (period_type, period_key, rank_type, rank_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '荣誉排行榜快照';

-- ── 以下两个索引服务于排行聚合查询，不改变任何既有语义 ──
--
-- 排行聚合的形态是「按时间区间过滤 + 按志愿者分组求和/计数」，而现有索引的前导列都是 volunteer_id
-- （point_record.idx_volunteer_time、activity_attendance.uk_activity_volunteer），
-- 时间区间无法走索引定位，月榜/年榜会退化成全表扫描。补两个以 time 打头的索引。

-- 次数榜按 check_in_time 区间数行、时长榜按 check_in_time 区间累加 service_minutes，
-- 两者都以 volunteer_id 分组，故把 volunteer_id 带进索引减少回表。
ALTER TABLE activity_attendance ADD KEY idx_checkin_time (check_in_time, volunteer_id);

-- 积分榜按 create_time 区间累加 change_amount，同样以 volunteer_id 分组。
ALTER TABLE point_record ADD KEY idx_create_time (create_time, volunteer_id);

-- 排行榜权限点。查看与快照生成分开：看榜是常规运营动作，而生成/补跑快照会**改写历史名次**
-- （补跑会按当前数据重算某个历史月份），属高危操作，沿用 V24「查看与调整分开」的口径。
-- type=2「操作」：两者都不走审批流，3 留给审核类权限点。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('honor:ranking-view', '排行榜查看', 'honor', 2, 41, NOW(), 0),
('honor:ranking-snapshot', '排行榜快照生成', 'honor', 2, 42, NOW(), 0);
