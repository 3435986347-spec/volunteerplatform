# data 模块 · 数据看板、数据汇总与投诉建议

> 代码：`代码/hengde-volunteer-data/`。本页由根 `CLAUDE.md` 的「已完成」表下沉而来（2026-10-17）；
> 上半部分是**现行要点**，下半部分是按批次的原始记录（为什么这样做、撞过什么）。两者冲突时以上半部分与代码为准，并顺手修正下半部分。

## 职责

只读聚合（看板、Row 79 数据汇总、捐赠汇总）与投诉建议工单。

## 现行要点

- 每个指标的口径都调 owner 域的只读 service，data 不碰外域表；**系统里不记的东西给 null 不给 0**。
- 「修建书屋数」＝收到过捐书物资的受赠单位数（协会答复五 乙-6）。
- 投诉按类别进部门（`hengde.data.complaint.routes.*`，须与账号上的部门一字不差）；工单在谁手上谁看；状态迁移 CAS 带「读到时的部门」；输家的报错在事务结束后重读再拼。
- 模块的权限常量类必须叫 `PermissionCode`（契约脚本按文件名收集）。

## 按批次的记录

**已完成（M4，数据看板）**：跨域只读聚合。`DashboardService.overview()`→`DashboardVO`（注册志愿者数/活动场次/总服务时长[时+分]/参与人次/管理团队数/分队数）；控制器 `AdminDashboardController` `GET /a/data/dashboard` + `VolunteerDashboardController` `GET /v/data/dashboard`（两端**共用同一服务、同一组数字**，**均仅需登录**——纯聚合数字不挂权限点；`data:dashboard`[V2 已 seed] 仅作前端「数据看板」菜单可见性，不强制于接口，与 url 文档「需登录」一致）。各指标语义收在 owner 域只读 service、data 只调不碰外域表：auth `VolunteerQueryService.countRegistered`(register_time 非空)/`countManagers`(manager_flag=1 且已实名+status 正常，对齐 `isActiveManager`)、activity `ActivityStatsService`、organization `SquadQueryService.count`。**待办计数不入看板**：概览 7 张待办卡走各自<b>权限受控</b>列表端点 `size=1` 取 total（只有缺的「待审报名」补了全局接口），避免把待审数泄给无权访问该队列的账号。测试 `DashboardServiceTest` 1/1（增量断言，含禁用 manager/游客 manager/草稿活动签到的负向）。

**V3 收尾批加捐赠数据汇总** `GET /a/data/donation-summary`（Row 79 的微心愿 / 结对 / 捐书活动三块）：口径收在 donate 的 `DonateStatsService`/`DonateStatsMapper`，data 只转发；与 dashboard 分开是因为它含金额而头部统计志愿者端也在用；「修建书屋数」＝收到过捐书物资（已送达）的受赠单位数（协会 2026-10-16 答复五 乙-6：一所乡村小学一间书屋；此前给 null）。

**V4 数据汇总批（2026-09-18，无迁移）**：Row 79 九块合成 `GET /a/data/summary`（`data:dashboard`——这个点此前只作前端菜单可见性，自本批起真的挂在端点上；`GET /a/data/dashboard` 保持「仅需登录」不变，志愿者端首页也在用那组数字）。**每个数字的口径都收在 owner 域的只读 service**：auth 新增 `countAllUsers`（**含游客**，Row 79 平台数据第一项）/ `countDisabled`（封号人数）、social 新增 `SocialStatsService`（帖子 / 发帖人 / 违规帖与违规人次 / 举报四项 / 禁言单）、enterprise 新增 `EnterpriseStatsService`（企业 / 商品 / 已兑换与已发放 / 收回积分 / 评价）、organization 新增 `GroupStatsService`、activity 新增 `AlbumStatsService` 与 `countProxyEnrollments`、honor 新增 `HonorStatsService`；捐赠三块直接复用 `DonateStatsService`。⚠️ **Row 79 的字面与系统里的概念对不上的地方不硬凑，出参字段名与文档都写明**：「被封小组数」→ 系统里的动作是**解散**；「违规帖子数」＝被驳回 ∪ 被后台隐藏（**命中关键词还在排队的不算**，那只是没审完）；「处罚数」＝已通过的奖惩处罚单 + 社区禁言单；「已兑换」（下单占住）与「已发放」（东西到手）分成两个数。⚠️ **系统里根本不记的东西给 null 不给 0**（相册的视频总数、查看人次、下载人数与人次）——出参省略 null 字段，前端拿到的是「没有这个键」；给 0 会被读成「一次都没有」。测试 `PlatformSummaryTest` 1：**断言一律是增量**（全库聚合，同一个库里别的用例也在写），每条口径配一个不该算进去的反例（游客不算注册、关键词待审不算违规、同一个人的两条违规帖只算一个人）。七处变异均已验证必红：用户人数不含游客 / 封号人数把注销也算进去 / 违规帖子把「命中关键词还在排队」也算进去 / 违规人次不去重 / 举报人数不去重 / 已解散小组数算成全部小组 / 相册的视频数与查看下载人次给 0 而不是 null。

**V4 投诉建议批（V65，2026-09-17，`com.hengde.data.complaint`）**：Row 43「类似于问卷收集 / 投诉默认到监察部，后续监察部可根据工作需要选择流转到其他部门，含处理进度」。两张表 `data_complaint`（工单）+ `data_complaint_log`（进度，只追加：1 提交 / 2 受理 / 3 流转 / 4 答复办结 / 5 内部备注，`visible` 决定志愿者看不看得到）。**部门就是 `admin_user.department` 那段文字**，没有主数据表；auth 新增只读的 `AdminQueryService`（`departmentOf` / `activeDepartments` / `listNamesByIds`）——「账号启用」语义归 auth 一处，**流转只能转给当前有启用账号的部门**（转过去一定有人看得到）；**进哪个部门按类别分**（V84，2026-09-29 改：协会 2026-09-02 活动类 → 组织部 / 协会内部类 → 秘书部 / 社区类 → 宣传部，2026-09-16 监察部已撤销）：提交必填 `category`，部门名走配置 `hengde.data.complaint.routes.*`（须与账号上的部门一字不差），原 `default-department`（默认监察部）已删；V84 之前的旧工单类别为空、停在哪个部门由哪个部门处理。**工单在谁手上谁看**：`data:complaint` 只看得到、只动得了当前在本部门的工单（转走就看不到，没填部门的账号一条都看不到），`data:complaint-all` 看全部可代处理；范围由控制器按登录态算成 `ComplaintService.Scope(adminId, department, allDepartments)` 传入，服务层不碰 Sa-Token。⚠️ data 模块的权限常量类**必须叫 `PermissionCode`**——契约脚本按这个文件名收集常量，起别的名字（第一版叫 `DataPermission`）会让 7 个端点全报「代码只需登录」。**状态迁移全是 CAS，条件里带「读到时的部门」**，写入用短 `TransactionTemplate`；**输家的报错在事务结束之后重新读一次**（`conflict(id)`）再说「当前：已办结，在宣传部」——用读到的旧对象拼、或在事务里普通读复核都会说成旧状态。志愿者的进度只含提交 / 受理 / 流转 / 答复四步，**流转理由与内部备注不下发**。**答复即办结**：站内提示（`VolunteerNotification.TYPE_COMPLAINT_REPLIED=5` / `BIZ_COMPLAINT=3`）同一事务，短信 `COMPLAINT_REPLIED` 由 `SmsNotifyService` 等提交后发、答复只放开头（`sms-reply-max-chars` 默认 30）。所有登录账号都能提（游客也能），禁用 / 注销不能；**每人 24 小时条数上限**（`daily-limit` 默认 5，按人上 Redisson 锁 `lock:complaint:volunteer:` 再数，连点越不过）；图片最多 6 张、只收本系统上传的（`isOwnUpload(url, "form")`，与问卷文件题同一判定）；协会为问卷场景 5 发布了问卷时答卷必填、与工单同一事务（`submitForScene`）。测试：`ComplaintServiceTest` 5（全流程与志愿者可见范围 / 站内提示与短信截断 / 部门范围与流转目标 / 图片·条数上限·账号状态 / 问卷接入）、`ComplaintConcurrencyTest` 4（**必跑**：裸 JDBC 持行锁确定性构造「输家的报错说的是刚发生的事」/ 同样确定性地构造「答复撞上正在进行的流转」钉住 CAS 里的部门条件 / 流转对答复 30 轮偏置起跑两种结局都出现 / 10 人同时受理只成一个）、压测 `ComplaintStressTest`（`@Tag("stress")`：80 单 × 6 名三部门处理人乱序受理 / 流转 / 答复 / 备注 + 10 人各连点 8 次提交，断言当前部门＝最后去向、流转次数＝流转进度条数、办结恰好一条答复、连点恰好停在上限、零异常）、api 的 `ComplaintApiFlowTest`（**必跑**：真建监察部 / 宣传部两个子账号只授 `data:complaint` 真登录，走完提交 → 受理 → 流转 → 答复，断言转走后监察部列表看不到、越权答复被拒，超管看全部）。data 全量 10。⚠️ **变异验证撞出一处用例缺口**：去掉 CAS 里的部门条件，30 轮赛跑用例照样全绿——预检读与 CAS 之间只有几微秒，靠调度撞不到；补了确定性用例才红。**赛跑用例证明「两种结局都出现过」，证明不了「那个窄窗口里的条件是承重的」**。八处变异均已验证必红：输家报错拿旧对象拼 / CAS 不带部门 / 志愿者进度带上内部备注 / 列表不按部门收窄 / 流转目标不核启用账号 / 去掉每日上限 / 短信塞整段答复 / 图片不核是否本系统上传。依赖 common/auth/organization/activity/**donate**，V4 数据汇总批起再加 **social / enterprise / honor**（都不依赖 data，无环；父 POM modules 里 data 因此移到 enterprise 之后）；经 activity 传递引入 redisson starter 故测试需 Redis；api pom 已加 data 依赖
