-- 荣誉排行榜「快照已冻结」的完成标记（V2 第 2 批，与 V25 同批）。
--
-- ── 为什么单独一版，而不是并进 V25 ──
-- 这张表在评审中才补出来，当时 V25 已经写好。**迁移一旦可能在任何环境执行过，就不能再原地改**：
--   · Flyway 用 checksum 校验历史版本，改动 V25 会让已跑过它的库直接 migrate 失败；
--   · 而 `repair` 的职责是「修复 schema history、重新对齐 checksum」，
--     **不会重新执行已记录为 success 的版本迁移**。
-- 于是「改 V25 → repair」的环境会把 checksum 对齐得干干净净，却永远没有这张表，
-- 直到运行期查询报「表不存在」才暴露。新增一版才能让各环境收敛到同一结果：
--   ① 空库 / 停在 V23 或 V24 → 顺序执行到 V26，建表；
--   ② 跑过**最终版 V25**（即当前这份，不含本表）
--        → V25 checksum 照旧匹配，V26 作为新版本正常补执行，建表；
--   ③ 跑过**中间版 V25**（开发期一度把本表写在 V25 里，未发布）
--        → 该库的 V25 checksum 记的是中间版，与当前 V25 不匹配，`migrate` 会先失败。
--          运维步骤：先 `flyway repair` 把 V25 的 checksum 重新对齐到当前文件，再 `migrate`；
--          此时本表已由中间版 V25 建好，靠下面的 IF NOT EXISTS 跳过（两版 DDL 完全一致）。
--
-- **两个已知环境均已确认不属场景 ③**：本地持久库停在 V23（只读核对：无 V24+ 记录、无 honor 表），
-- 公网库停在 V24（用户确认）——中间版 V25 只存在于开发工作区，从未提交也从未发布。
-- 故 IF NOT EXISTS 是给未普查到的开发机留的**纵深防御**，不是在迁就某个已知的坏状态；
-- 本项目其余迁移一律用裸 CREATE TABLE，此处破例的理由仅限于此。
-- 升级路径由 common 的 V26HonorRankingBatchMigrationTest 覆盖（场景 ② 与 ③ 各一例）。
--
-- ── 这张表本身解决什么 ──
-- 为什么不能靠「快照表里有没有行」来判断已冻结：**空榜单也是一种合法的冻结结果**。
-- 某个月若无人参加活动，该月榜本就该是空的；若把「查不到行」当成「还没冻结」，
-- 那个月就会永远退回实时聚合——事后补录一笔该月的数据，这个「历史」月榜就会凭空冒出人来，
-- 冻结对空榜完全失效。（已用真实 MySQL 复现：生成 1988-01 空快照 written=0 →
-- 查询 fromSnapshot=false → 事后补一笔 1988-01 积分 → 历史榜出现 1 人。）
--
-- 也不用「插一条虚拟志愿者行」当哨兵：那会污染所有读路径——榜单要过滤它、名次要跳过它、
-- 跨域换名会查不到它，每个消费方都得记得排除，迟早漏一处。完成标记与榜单数据是两件事，分表存。
--
-- 粒度到 rank_type：跳过与强制重算本来就是逐板块进行的（某个板块补跑不该影响另一个板块的冻结状态）。
CREATE TABLE IF NOT EXISTS honor_ranking_snapshot_batch (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    period_type TINYINT     NOT NULL COMMENT '周期 1月/2年',
    period_key  VARCHAR(16) NOT NULL COMMENT '周期标识：月 2026-07 / 年 2026',
    rank_type   TINYINT     NOT NULL COMMENT '榜单 1活动次数/2活动时长/3积分',
    -- 冻结时写入了多少行。0 是合法值（那个周期确实没人上榜），仅供人工核对与排障，不参与判定。
    row_count   INT         NOT NULL DEFAULT 0 COMMENT '本次冻结写入的行数（0 表示空榜）',
    create_time DATETIME    DEFAULT NULL,
    update_time DATETIME    DEFAULT NULL,
    is_deleted  TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一个「周期 × 板块」只能有一条完成标记；强制重算走「物理删除后重写」，与快照行同样的纪律
    -- （唯一键不含 is_deleted，逻辑删除会让旧行占着键使重写撞键）。
    UNIQUE KEY uk_batch (period_type, period_key, rank_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '荣誉排行榜快照完成标记';
