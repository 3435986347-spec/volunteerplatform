-- 积分流水账本（V2 第 1 批·地基）。
--
-- 背景：此前积分只落在 activity_attendance.points_award 单个字段上，V1 够用（唯一来源是活动、只增不减），
-- 但撑不起 V2 的「积分中心」——需求要「总积分/已用积分/增减明细」，且勋章奖励、奖惩调整、(V3)兑换扣减
-- 都会成为新的积分来源。本表建立后，积分以流水为唯一事实来源，activity_attendance.points_award 退化为
-- 「该次考勤发了多少分」的业务快照，不再直接用于汇总。
--
-- 幂等：uk_source(source_type, source_id) 是核心防线——积分等同权益，重复入账不可接受。
--   各 source_type 的 source_id 指向不同单据，避免撞键：
--     1 活动积分 → activity_attendance.id     （一条考勤只发一次）
--     2 积分修正 → activity_attendance_change.id（**不能用 attendance.id**：同一考勤可被多次修正，会撞唯一键）
--     3 勋章奖励 → honor_medal_grant.id        （V2 第 3 批启用）
--     4 兑换消费 → 兑换单 id                    （V3 预留）
--     5 管理员手工调整 → NULL                   （无天然单据；幂等改由 request_id 保证，见下）
--     6 奖惩调整     → honor_reward_punish.id  （V2 第 5 批启用；**必须与 2 分开**——两张表的自增 id
--                                                会撞车，共用 source_type 会让奖惩单被误判成「已入账的积分修正」。
--                                                PointService 的载荷复核只能发现「载荷不同」的撞键，
--                                                同人同额的两笔仍会被当成重放丢掉——独占来源码是必要条件）
--
-- 手工调整的幂等：source_id 为 NULL 而 MySQL 唯一索引视多个 NULL 互不相同，uk_source 对它形同虚设，
--   双击/超时重试/网关重放都会重复加减分。故另设 request_id（前端每次打开调整弹窗生成一个 UUID）
--   + uk_request_id 兜底。允许管理员多次独立调整——每次是不同的 request_id；重放的是同一个。
--
-- 余额：**不冗余存储**，一律由 SUM(change_amount) 求得。
--   设计上曾考虑存 balance_after 便于对账，但入账必须与调用方业务变更同事务（积分与考勤要同成同败），
--   而本项目既有约定是「锁在事务之外获取」（见 EnrollmentService），二者不可兼得：
--   锁若落在调用方事务内，线程B可能在线程A解锁后、提交前拿到锁，读到旧余额从而算错 balance_after。
--   改为纯 SUM 后写入变成无状态追加，竞态整类消失，且 SUM 是自校正的（不会因冗余列写坏而失真）。
--
-- 本表为**追加型账本，业务上永不删除**（is_deleted 列仅为满足 MyBatis-Plus 全局逻辑删除配置而存在）。
CREATE TABLE point_record (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id  BIGINT       NOT NULL COMMENT '志愿者id',
    change_amount INT          NOT NULL COMMENT '变动值：正=入账，负=出账',
    source_type   TINYINT      NOT NULL COMMENT '来源 1活动积分/2积分修正/3勋章奖励/4兑换消费/5管理员手工调整/6奖惩调整',
    source_id     BIGINT       DEFAULT NULL COMMENT '来源单据id（手工调整为 NULL）',
    request_id    VARCHAR(64)  DEFAULT NULL COMMENT '幂等键（手工调整必填，由前端每次生成；其余来源为 NULL）',
    -- 512 而非 255：明细页的搜索框搜的就是 remark，而积分修正会把申请理由（activity_attendance_change.reason，
    -- 列宽 512）拼进来。留 255 会把长理由从中间截断，后半段的关键词永远搜不到。
    -- 512 覆盖绝大多数情况；仍超长的由 PointService 安全截断（末位省略号），完整原文以各业务单据表为准。
    remark        VARCHAR(512) DEFAULT NULL COMMENT '说明（对志愿者展示，也是明细搜索的匹配对象）',
    operator_type TINYINT      NOT NULL DEFAULT 0 COMMENT '操作方 0系统/1管理员',
    operator_id   BIGINT       DEFAULT NULL COMMENT '操作人id（管理员时为 admin_user.id）',
    create_time   DATETIME     DEFAULT NULL,
    update_time   DATETIME     DEFAULT NULL,
    is_deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source (source_type, source_id),
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_volunteer_id (volunteer_id, id),
    KEY idx_volunteer_time (volunteer_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分流水';

-- 历史积分回填：把已发放的考勤积分补成流水，否则新账本从零起、与志愿者既有积分对不上。
-- 只回填真正发过分的行：points_status=1 且 points_award 非空非零
--   （历史活动补录会置 points_status=1 但 award=0，属「只记时长不发分」，无需入账）。
--
-- remark 带上活动名称而非统一写「活动积分（历史回填）」：明细页的搜索框搜的就是 remark，
--   若所有历史流水共用一句话，志愿者按活动名根本搜不到自己以前的积分，搜索对存量数据等于不可用。
--   活动可能已被逻辑删除，故用 LEFT JOIN + COALESCE 兜底；activity.title 为 VARCHAR(128)，
--   拼接后不会超 remark 的 512，仍用 LEFT() 加一道保险，避免严格模式下因超长整个迁移失败。
INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id,
                          remark, operator_type, operator_id, create_time, update_time, is_deleted)
SELECT a.volunteer_id,
       a.points_award,
       1,
       a.id,
       LEFT(CONCAT('参加活动「', COALESCE(act.title, '未知活动'), '」（历史回填）'), 512),
       0,
       NULL,
       COALESCE(a.update_time, a.create_time),
       COALESCE(a.update_time, a.create_time),
       0
FROM activity_attendance a
         LEFT JOIN activity act ON act.id = a.activity_id
WHERE a.is_deleted = 0
  AND a.points_status = 1
  AND a.points_award IS NOT NULL
  AND a.points_award <> 0
ORDER BY a.volunteer_id, a.id;

-- 积分中心相关权限点。查看与调整分开：多数运营只需查明细，手工加减分是高危操作应单独授予
-- （沿用「审核权要搭查看权」的既有口径）。
-- type=2「操作」而非 3「审核」：见 V2 的 permission.type 注释 1菜单/2操作/3审核——这两个是普通查看与
-- 操作动作，不走审批流；3 留给 service-confirm / attendance-audit / publish-audit 那类审核点。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('activity:points-view', '积分明细查看', 'activity', 2, 39, NOW(), 0),
('activity:points-adjust', '积分手工调整', 'activity', 2, 40, NOW(), 0);
