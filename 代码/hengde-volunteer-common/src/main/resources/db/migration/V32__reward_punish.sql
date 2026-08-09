-- 奖惩中心（V2 第 5 批）。
--
-- 需求原文（`小程序设想【第十版】.xlsx` · 前端 sheet）：
--   Row 41 C：「各类违规记录和奖励」
--   Row 41 F：「各类违规记录和奖励均需【组织部同学审核才可显示】，审核之后，
--              志愿者会收到提示，并有【7天申诉期】」
--   Row 59 C：后台首页待办列出「…【活动违规审核】…」——「违规记录本身要审」的直接出处
--   Row 73 C：「志愿者使用的前端功能如出现违规行为，可由该部门负责的同学【限制其使用】，
--              包括但不限制于【限制其使用指定天数】、【拒绝其使用本程序】。监察部拥有全部限制能力」
--
-- 原型 `志愿平台设想【第十版】.pdf` P109「奖惩记录」：
--   处罚卡片正文＝「限制参加活动7天」「限制发布社区」「警告」；
--   处罚详情＝「违规处罚：限制参加活动7天 / 扣除积分：0 / 违规类型：违规玩手机 /
--              处罚时间 / 处罚编号：251363568415641 / 申诉」；
--   「处罚内容明细：限制参加活动 → 限制参加活动7天」；
--   奖励卡片＝「推荐评选雷州市优秀共青团员 / 奖励类型：积极参加活动 / 奖励积分：200 / 查看详情」。
--
-- 🛑 **P109 已经回答了「Row 73 是不是另一个子系统」**：处置措施就是奖惩记录的正文内容。
--    Row 73 讲的是【谁有权限做】，Row 41 讲的是【记录与展示形态】，两者是同一件事的两面。
--    `V2规划.md` 原先把它列为「待决策」，那与原型不符，已在本批更正。
--
-- 🛑 Row 47「评优评先」原文写明「需预留」，不在本批。

-- ── 一、活动违规记录：补上审核环节 ──
-- 出处 Row 59 的待办项「活动违规审核」+ Row 41 F「审核才可显示」。
-- 现状是负责人现场一记，志愿者在「我的活动」立刻看得到条数（`MyActivityVO.violationCount`），
-- 中间没有任何闸门——与 Row 41 F 直接冲突。
--
-- ⚠️ **存量一律置 0（待审核），刻意不伪造审核痕迹**：
--    把从未发生过的审核标成「已通过」，`reviewed_by` 只能填 NULL，
--    于是库里会出现「已通过但没有审核人」这种自相矛盾的行，审计上是撒谎。
--    代价是存量违规在组织部逐条处理前不再对志愿者显示——而那正是新口径要求的方向，
--    且这些行会自动出现在 Row 59 的待办队列里，不会消失无踪。
ALTER TABLE activity_violation
    ADD COLUMN review_status TINYINT      NOT NULL DEFAULT 0 COMMENT '组织部审核 0待审核/1已通过/2已驳回',
    ADD COLUMN reviewed_by   BIGINT       DEFAULT NULL COMMENT '审核人 admin_user.id',
    ADD COLUMN review_time   DATETIME     DEFAULT NULL COMMENT '审核时间',
    ADD COLUMN reject_reason VARCHAR(512) DEFAULT NULL COMMENT '驳回原因',
    -- 待办队列按状态捞、按 id 倒序翻页，且要能按活动过滤。
    -- 【两条而不是一条】默认视图是全局的 `review_status=? ORDER BY id DESC`，
    -- 单一的 (review_status, activity_id) 吃不到那个排序（中间隔着 activity_id），会退化成 filesort；
    -- 按活动筛时才轮到第二条。这是 V25/V31 记过的同一课：看清查询的前导列与排序列。
    ADD KEY idx_review (review_status, id),
    ADD KEY idx_review_activity (review_status, activity_id, id);

-- ── 二、奖惩单 ──
CREATE TABLE honor_reward_punish (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    rp_no          VARCHAR(64)  NOT NULL COMMENT '奖惩编号，对外展示（原型 P109「处罚编号：251363568415641」）',
    volunteer_id   BIGINT       NOT NULL COMMENT '志愿者 volunteer.id',
    type           TINYINT      NOT NULL COMMENT '1奖励/2处罚',
    -- 【为什么是 VARCHAR 而不是枚举码】P113 的违规类型列表结尾明写「........」，
    -- P109 又出现了「信息泄露」这种不属于活动现场违规枚举的类型，奖励类型「积极参加活动」同理。
    -- 需求给的是一个【开放集合、由协会自己维护】，硬做成 TINYINT 枚举等于替协会把它封死，
    -- 每加一类都要改代码 + 迁移。
    category       VARCHAR(64)  NOT NULL COMMENT '类别：奖励类型 / 违规类型（开放集合，见 P113 结尾的省略号）',
    title          VARCHAR(128) DEFAULT NULL COMMENT '标题（P109「推荐评选雷州市优秀共青团员」）',
    description    VARCHAR(1024) DEFAULT NULL COMMENT '说明（P109「您在【xx活动中】因多次玩手机被处罚，请您下次注意」）',
    -- 奖励为正、处罚为负；0 = 只警告不涉及积分（P109「扣除积分：0」就是这种）
    points_delta   INT          NOT NULL DEFAULT 0 COMMENT '积分变动；奖励为正、处罚为负、0=不涉及积分',
    activity_id    BIGINT       DEFAULT NULL COMMENT '关联活动（P109「违规详情：违规活动」）',
    slot_id        BIGINT       DEFAULT NULL COMMENT '关联场次；V30 起场次是参与的最小单元',
    violation_id   BIGINT       DEFAULT NULL COMMENT '来源的现场违规 activity_violation.id；奖励与非活动处罚为 NULL',
    -- 处置内容（P109「处罚内容明细：限制参加活动 → 限制参加活动7天」、「违规处罚：限制参加活动7天」）。
    -- 【为什么记在单子上而不是只写进 volunteer_sanction】处置要到【审核通过】才真正生效，
    -- 而单子在待审核期间就得说清「打算怎么罚」——否则审核人看不到自己在批准什么，
    -- 详情页也印不出那句「违规处罚：限制参加活动7天」。
    -- 【为什么一张单子只挂一条处置】P109 给的每个例子都是一条（限制参加活动7天 / 限制发布社区 / 警告），
    -- Row 73 的「包括但不限制于」说的是【处置种类】可扩展，不是【一次罚几项】。
    -- 真需要一次罚两项时开两张单，每张各自可申诉——这比把两项绑死在一张单上更贴合申诉的粒度。
    sanction_scope TINYINT      DEFAULT NULL COMMENT '处置能力域 1限制参加活动/2限制发布社区/3拒绝使用本程序；NULL=仅警告不限制',
    sanction_days  INT          DEFAULT NULL COMMENT '限制天数；NULL 且 scope 非空 = 不设期限',
    -- 审核（Row 41 F「均需组织部同学审核才可显示」）
    review_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '审核 0待审核/1已通过/2已驳回',
    reviewed_by    BIGINT       DEFAULT NULL COMMENT '审核人 admin_user.id',
    review_time    DATETIME     DEFAULT NULL COMMENT '审核时间',
    reject_reason  VARCHAR(512) DEFAULT NULL COMMENT '驳回原因',
    -- 申诉（Row 41 F「审核之后，志愿者会收到提示，并有 7 天申诉期」）
    -- 截止时刻【落库而不是每次由 review_time + 7 天现算】：申诉期是对志愿者的承诺，
    -- 现算意味着哪天把 7 改成 3，所有在途的申诉权会被追溯性地缩短甚至当场失效。
    appeal_deadline    DATETIME DEFAULT NULL COMMENT '申诉截止 = 审核通过时刻 + 7 天，审核通过时写死',
    appeal_status      TINYINT  NOT NULL DEFAULT 0 COMMENT '0未申诉/1申诉中/2申诉成立/3申诉驳回',
    appeal_reason      VARCHAR(1024) DEFAULT NULL COMMENT '志愿者提交的申诉理由',
    appeal_time        DATETIME DEFAULT NULL COMMENT '提交申诉时间',
    appeal_handled_by  BIGINT   DEFAULT NULL COMMENT '申诉受理人 admin_user.id',
    appeal_handle_time DATETIME DEFAULT NULL COMMENT '申诉受理时间',
    appeal_result      VARCHAR(512) DEFAULT NULL COMMENT '受理结论说明',
    create_by      BIGINT       DEFAULT NULL COMMENT '开单人 admin_user.id',
    create_time    DATETIME     DEFAULT NULL,
    update_time    DATETIME     DEFAULT NULL,
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    -- 一条现场违规最多转出一张处罚单。用生成列排除软删行：
    -- 软删后应当能对同一条违规重新开单，裸 UNIQUE(violation_id) 会让软删行继续占位而永远开不了
    -- （与 V31 样本 active_scope_key 同一写法、同一理由）。
    -- violation_id 为 NULL 的行（奖励、非活动处罚）不受本键约束——MySQL 视多个 NULL 互不相同，这是刻意的。
    active_violation_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 THEN violation_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rp_no (rp_no),
    UNIQUE KEY uk_active_violation (active_violation_id),
    -- 志愿者端按人倒序翻页
    KEY idx_volunteer (volunteer_id, id),
    -- 后台待审核队列
    KEY idx_review (review_status, id),
    -- 申诉受理队列
    KEY idx_appeal (appeal_status, id),
    -- ⚠️ MySQL 8.0.16 起 CHECK 才真正生效，低版本【静默忽略】——部署前须确认服务端版本。
    -- 奖励不产生处置、也没有申诉入口（P109 奖励卡片只有「查看详情」，没有「申诉」按钮），
    -- 故只有处罚才允许挂来源违规。
    CONSTRAINT ck_rp_violation_only_punish CHECK (violation_id IS NULL OR type = 2),
    -- 奖励不产生处置：一张「奖励」单挂着「限制参加活动」是自相矛盾的，
    -- 而它会一路走到审核通过、真的把人限制住，且从奖惩记录上看这个人是被表扬的。
    CONSTRAINT ck_rp_sanction_only_punish CHECK (sanction_scope IS NULL OR type = 2),
    -- 只填天数不填能力域会得到一条「有期限但什么也不限制」的单，而详情页会照着 sanction_days
    -- 印出「限制 7 天」——看着被罚了、实际没有任何约束。天数为 0 或负数同理无意义。
    CONSTRAINT ck_rp_sanction_days CHECK (
        sanction_days IS NULL OR (sanction_scope IS NOT NULL AND sanction_days > 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '奖惩单（奖惩中心）';

-- ── 三、处置措施 ──
-- 【为什么这张表落在 auth 域而不是 honor】处置是【账号能力状态】，与 volunteer.status 同一性质。
-- 执行闸门必须挂在报名、签到、社区发帖这些【业务入口】上，而那些入口在 activity / publicity；
-- 若把表放 honor，activity 与 publicity 就要反向依赖 honor，与既定方向 honor → activity 成环。
-- auth 是 activity / publicity / honor 都已经依赖的模块，放这里没有新的依赖负担。
CREATE TABLE volunteer_sanction (
    id             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id   BIGINT   NOT NULL COMMENT '志愿者 volunteer.id',
    source_type    TINYINT  NOT NULL DEFAULT 1 COMMENT '来源 1奖惩单',
    source_id      BIGINT   DEFAULT NULL COMMENT '来源单据 id（奖惩单 honor_reward_punish.id）',
    -- 能力域来自 P109 的处罚正文与 Row 73：
    --   1 限制参加活动（P109「限制参加活动7天」）
    --   2 限制发布社区（P109「限制发布社区」）
    --   3 拒绝使用本程序（Row 73「拒绝其使用本程序」，监察部）
    -- ⚠️ 取值 2 目前【没有可限的对象】：社区（social）模块全项目未建（见 `文档/功能清单.md` 第七节）。
    --    先把取值定下来是为了让 P109 画到的处罚能如实记录，但落到执行时它是空转的，
    --    social 上线时必须回到 SanctionQueryService 把这条闸门接上。
    scope          TINYINT  NOT NULL COMMENT '能力域 1限制参加活动/2限制发布社区/3拒绝使用本程序',
    effective_time DATETIME NOT NULL COMMENT '生效时间',
    -- 【到期靠比时间，不靠定时任务改状态】定时任务漏跑一次，处罚就会超期继续生效，
    -- 而「到期即自动恢复」是对志愿者的承诺，不能取决于某个 cron 有没有跑成功。
    -- 故判定生效的条件恒为：status = 1 AND effective_time <= NOW() AND (expire_time IS NULL OR expire_time > NOW())
    expire_time    DATETIME DEFAULT NULL COMMENT '到期时间；NULL = 不设期限（如「拒绝其使用本程序」）',
    status         TINYINT  NOT NULL DEFAULT 1 COMMENT '1生效/2已解除（申诉成立或管理员撤销）',
    lifted_by      BIGINT   DEFAULT NULL COMMENT '解除人 admin_user.id；申诉成立由系统解除时为 NULL',
    lifted_time    DATETIME DEFAULT NULL COMMENT '解除时间',
    lift_reason    VARCHAR(512) DEFAULT NULL COMMENT '解除原因',
    create_time    DATETIME DEFAULT NULL,
    update_time    DATETIME DEFAULT NULL,
    is_deleted     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    -- 闸门是热路径（每次报名/签到都查），按「人 + 能力域 + 状态」定位
    KEY idx_volunteer_scope (volunteer_id, scope, status),
    KEY idx_source (source_type, source_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '志愿者处置措施（限制使用）';

-- ── 四、权限点 ──
-- type: 2=功能操作 3=审核类。
-- 【申诉受理单列一个权限点】需求没写申诉由谁受理——Row 41 F 只说了审核方是组织部。
-- 与其在代码里替协会猜一个部门，不如把它做成一个独立权限点：谁受理由后台授权决定，
-- 协会改主意时是改一次配置，不是改一次代码。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('activity:violation-review',  '活动违规审核',     'activity', 3, 35, NOW(), 0),
('honor:reward-punish',        '奖惩记录管理与审核', 'honor',   3, 51, NOW(), 0),
('honor:reward-punish-appeal', '奖惩申诉受理',      'honor',   3, 52, NOW(), 0),
('honor:sanction',             '处置措施解除',      'honor',   2, 53, NOW(), 0);
