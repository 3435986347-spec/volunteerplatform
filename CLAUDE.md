# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

雷州市恒德爱心公益协会志愿者管理平台，基于微信小程序的多端志愿者服务系统（后端为 Spring Boot 4 / Spring Cloud 微服务）。所有模块 `groupId=com.hengde`，`version=1.0-SNAPSHOT`，Java 17。

## 当前状态（重要）

代码位于 `代码/` 子目录，各模块独立目录，`<parent>` 通过本地 Maven 仓库引用父 POM（`relativePath` 为空）。

### 已完成

| 模块 | 路径 | 说明 |
|---|---|---|
| `hengde-volunteer-common` | `代码/hengde-volunteer-common` | **已完成**：返回体/异常/错误码（`Result`/`BusinessException`/`ResultCode`）、基础实体与自动填充（`BaseEntity`/`MyMetaObjectHandler`）、Redis（`RedisConfig`/`RedisUtil`）、加解密（`CryptoUtil`：AES-GCM + HMAC，`SecurityProperties` 持密钥，dev 默认值生产须覆盖）、密码（`PasswordUtil`）、JWT（`JwtUtil`，备用）、常量（`UserStatus`/`Gender`/`Grade`/`CommonConstants`）、短信（`SmsProperties`/`SmsService`/`VerifyCodeService`/`SmsScene`）、对象存储（`OssProperties`/`FileStorageService`/`AliyunOssFileStorageService`/`FileValidator`）、Excel（`ExcelUtil`）、分页（`PageQuery`/`PageResult`）、Testcontainers 测试基座（`TestcontainersConfig`=MySQL、`RedisTestcontainersConfig`=Redis，均 test-jar 暴露）、全局搜索统一出参（`search/SearchItemVO`：type/id/title/summary/imageUrl，跨领域聚合用）、Flyway 迁移脚本与依赖（`db/migration/`，全项目唯一序列，当前到 **V12**：V1 init、V2 RBAC、V3/V4 activity、V5 organization 小组/分队/架构、V6 publicity、V7 group admin/history、V8 activity 发布团队/联系人/分角色报名开放时间、V9 group_member「一人一组」唯一约束、V10 activity 签到/时长/积分闭环+现场负责人（activity 加经纬度/半径/run_status/总结、新建 activity_leader/activity_attendance/activity_violation、6 权限点）、V11 volunteer 加 manager_flag「管理团队」标记（接通积分 ×1.2 倍率）、V12 新增权限点 `org:manager-flag`（管理团队标记后台手动开关）、V13 volunteer 加 `manager_flag_by`/`manager_flag_time` 记标记操作人与时间） |
| `hengde-volunteer-api` | `代码/hengde-volunteer-api` | **全局配置层已完成**：`SaTokenConfigure`、`WebMvcConfig`（CORS）、`JacksonConfig`、`MybatisPlusConfig`（含分页拦截器 `PaginationInnerInterceptor`，**仅此处装配**，领域模块测试上下文没有）、`AsyncConfig`、`SpringDocConfig`（Knife4j，分组 volunteer/admin/enterprise）、`GlobalExceptionHandler`、`TraceFilter`、`HomeAggregateController`（占位）、`SearchController`（**已实现**：`/v/search` 全局搜索，聚合 activity/publicity/organization 四类——活动+公告+小组+分队，各领域 `countSearch`+`search(offset,limit)` 跨块精确分页，合并成单一信息流 `PageResult<SearchItemVO>`）、`SuperAdminInitializer`（启动预置超管）。迁移脚本在 common；`RedissonClient` 由 `redisson-spring-boot-starter` 自动从 `spring.data.redis` 装配 |
| `hengde-volunteer-auth` | `代码/hengde-volunteer-auth` | **已完成**：志愿者端（微信登录/发注册验证码/实名注册/企业微信群校验/退出，默认 `StpUtil`，loginId=`volunteer.id`）；管理端（账号登录/找回密码，`StpAdminUtil` 独立 `StpLogic`，与志愿者端隔离）；志愿者 PII（身份证/手机号）AES-GCM 密文 + HMAC 哈希列；`VolunteerQueryService` 跨模块只读出参（`VolunteerProfileView` 资格档案、`VolunteerDisplayView` 展示信息含解密手机号、`isManager` 管理团队判定）；`VolunteerAdminService`（管理端写，与只读 query 分开：`setManagerFlag(id,flag,operatorId)` 设/取消「管理团队」标记——flag 硬校验仅 0/1[非法抛异常不静默取消]、设为 1 要求已实名、取消 0 放行[可清游客脏标记]、幂等、落 `manager_flag_by`/`time` 审计；鉴权由 organization 控制器负责） |
| `hengde-volunteer-organization` | `代码/hengde-volunteer-organization` | **已完成**。子账号/RBAC（`com.hengde.organization` 包）：`permission` 种子（V2 23 个 + V4 `activity:enroll-view` + V10 活动 6 个 + V12 `org:manager-flag`，共 31）、`AdminStpInterface`（`@SaCheckPermission` 权限数据源，超管走 `*` 通配）、子账号管理与权限分配（`assignPermissions` 仅超管，service 层防自助提权；`user:edit`/`org:perm-assign` 写死仅超管、不入权限表）。小组/分队（`com.hengde.organization.biz` 包，V5+V7 表）：`GroupService`（小组发起/审批加入/退出/转移组长/解散[V7 起 dissolve_time/reason/by 独立字段，与 reject_reason 解耦；同步清成员防卡「一人一组」]/批量导入/V7 起组长 setAdmin·revokeAdmin[≤3 人]+管理员可与组长一同 approveMember/rejectMember/removeMember；审批写 audit_by；`transferLeader`/建组通过写入 `volunteer_group_leader_history`；V9 `approveMember`/`rejectMember` 改 CAS 防并发覆盖；`approveCreate`/`rejectCreate` 也改 CAS；V9 DB 唯一约束 `uk_active_volunteer(active_volunteer_lock)` 兜底「一人一组」；`importGroups` 仅建 ACTIVE 小组[显式非 ACTIVE 行直接拒绝——导入 PENDING 不插组长成员行会绕开「一人一组」且让 `approveCreate` 找不到待生效组长行成死组]，每行 `ensureNoActiveGroup` + 必插 ACTIVE 组长成员行 + `DuplicateKeyException` 友好降级；**导入视同审批**：写 `approved_time`/`approved_by` + 组长成员 `audit_by` + 补一条 `OP_TYPE_INITIAL` 组长历史，审计链与 `approveCreate` 对齐；解析与落库拆开为 `importGroups(MultipartFile,adminId)`→`importGroupRows(List,adminId)`，后者可脱离 Excel 单测）、`GroupQueryService`（V7 跨模块只读：`requireSameActiveGroup` 供 activity 代报名校验同小组）、`SquadService`（分队增改删、申请加入/审批[CAS 条件更新 + 人数上限预校验 + `squad_id is null` 条件归属]、归属差异视图[未归属仅看负责人；已归属看同分队成员，字段按 `visible_fields` 收敛]、停用分队志愿者端不可达 `requireEnabledSquad`）、`StructureService`（组织架构树）。志愿者管理团队标记（V12 权限点 `org:manager-flag`）：`OrganizationVolunteerController` 的 `PUT /a/organization/volunteers/{id}/manager-flag` 调 auth `VolunteerAdminService.setManagerFlag` 手动开关 `volunteer.manager_flag`（DTO flag 0/1 校验 + service 层硬校验兜底；操作人取 `StpAdminUtil.getLoginIdAsLong()` 落审计；预留的「报名管理团队」问卷审批将复用同一标记通道） |
| `hengde-volunteer-publicity` | `代码/hengde-volunteer-publicity` | **已完成**：轮播图/公告/文件下载（`PublicityService`）。管理端（`/a/publicity`，`@SaCheckPermission` pub:banner/announcement/file）增改删 + 排序 + 开关下载；志愿者端（`/v/publicity`）只读已上架/已发布（公告详情按 `status=1` 过滤防草稿泄露）。表在 V6；权限点在 V2 种子。无领域内迁移——脚本集中 common |
| `hengde-volunteer-activity` | `代码/hengde-volunteer-activity` | **已完成**：活动发布/修改/删除/复制（`ActivityService`，创建即发布、`serial_no`=自增 id）、志愿者端列表/详情（仅已发布可见）、报名/取消/我的报名/**代报名**（`EnrollmentService`：Redisson「志愿者维度」锁 + 事务内提交、防重 + 全平台同时段冲突 + 资格校验[年龄/年级/性别/已参加场次]；V7 起 `proxyEnroll` 同小组成员互相代报：依赖 `organization.GroupQueryService` 校验同组，每 target 仍跑完整资格校验，`runLockedMany` 按 id 升序多锁 + 单事务原子，落 `proxy_by_volunteer_id`，批量上限 20）、管理端报名管理（`EnrollmentAdminService`：列表/审核通过·拒绝[CAS 条件更新保原子]/手动新增[管理员越权,跳资格/截止但拦禁用账号]/Excel 导出/逻辑删除；列表 VO 含 `proxyByName` 追溯代报名来源）。V8 起 activity 表补 `contact_name/contact_phone/publisher_dept_name` 与 `enroll_open_manager/leader/volunteer` 三个时间——志愿者端 enroll/proxy 在 deadline 校验前加 `enroll_open_volunteer` 拦截；管理端 manualEnroll 越权不受限。**V10 签到/时长/积分闭环 + 现场负责人（V1.1 第 1 批·主干）**：`ActivityLeaderService`（指派/取消/列表，leaderType 1报名志愿者[须本活动活跃报名]/2管理团队；`isVolunteerLeader` 供 /v 鉴权与积分倍率）、`AttendanceService`（活动开始/结束 run_status 0未开始/1进行中/2已结束；志愿者 GPS 自助 `checkIn`[Haversine 距活动坐标 ≤ `check_in_radius_m` 默认500 + 活动开始前2h~结束后2h 窗口 + 已通过报名校验 + 重复签到拒绝]；负责人 `markAttendStatus`[1正常/2请假/3迟到/4缺席——缺席→时长0+自动违规type5、请假→时长0]、`recordViolation`、`bulkCheckOut`[活动结束后2h内统一签退，服务时长=签退−签到]、负责人视图 `myLedActivities`/`leaderDetail`[名单含名字/电话/学校]）、`ServiceRecordService`（我的服务记录、服务记录大板块[全员可筛选]、`secretaryConfirm`[CAS，须已签退]、`grantPoints`[CAS，须秘书已确认；积分=基数×角色倍率(负责人1.4/管理团队1.2[经 V11 `volunteer.manager_flag`，`VolunteerQueryService.isManager`]/普通1.0)×违规系数(正常1/减半0.5/不发0)，请假/缺席=0]）；控制器 `/v` ManagedActivityController(负责人,经 requireVolunteerLeader)+AttendanceController(签到/我的服务记录)、`/a` ActivityManageAdminController(指派+现场管理 activity:leader-assign/manage)+ServiceRecordAdminController(大板块/确认/积分 activity:service-confirm/points-grant)。**V1.1 第 2 批·PR1（考勤周边，无新迁移，全用 V10 已建列）已完成**：`MyActivityService`「我的活动」(参与者视角,列表/详情含负责人/考勤/违规数/签到二维码占位 `checkInQrContent`/确认到家/评价回显,`MyActivityController` /v/activity/my-activities)、`AttendanceService` 扩 `confirmHome`(活动结束后记 confirm_home_*,超时只在视图派生标记不拒绝,坐标范围守卫)/`submitReview`(志愿者评活动+负责人 1~5 写 vol_*,**须实际签到**——以 check_in_time 为参加凭据,挡掉被负责人补建考勤行的未到场者)/`leaderEvaluate`(负责人评志愿者写 leader_evaluation,无行补建)/`uploadSummary`(写 activity.summary_*,**须活动已结束** isEnded,/v 负责人 + /a activity:manage 共用)、`EnrollmentService.checkEligibility` 接「已参加时长门槛」(`require_min_join_minutes`,累计 secretary_status=1 的 `sumConfirmedMinutes`≥门槛,自动覆盖 enroll/proxyEnroll,manualEnroll 不受限;经 `ActivityCreateDTO`/`ActivityUpdateDTO` 配置、`ActivityAdminDetailVO` 回显)。**搁置**：`enroll_scope=1` 指定分队报名、`target_squad_ids`→关联表、活动补录；**V1.1 待续**：第2批·PR2(组织部改签到/签退/积分 + 部长二次审核——待建 attendance_change 表，权限点 attendance-edit/audit 已在 V10)、第3批(活动留言/周期发布/补录)；**活动相册**(上传照片+评论默认发交流平台)依赖社区 social，推迟到 social 落地一起做 |

### 待实现（V1 优先级）

V1 落地顺序：`auth` → `organization`（子账号权限） → `activity` → `organization`（小组/分队） → `publicity`（含全局搜索聚合） → `data`。**前五段已完成**，剩余：

- `data`：首页数据看板/数据展示（`/v/home` 现为占位）、投诉建议（默认到监察部）。
- `activity` 签到/时长/积分闭环 **V1.1 分三批**：第 1 批（主干：现场负责人/GPS 签到/统一签退算时长/秘书部确认/服务记录大板块/积分发放）**已完成**（V10）；第 2 批（我的活动页、确认到家、活动相册、双向评价、活动总结、组织部改签到签退积分+部长二次审核、已参加时长门槛）、第 3 批（活动留言、固定日期周期发布、活动补录）**待实现**。另 `enroll_scope=1` 指定分队报名、`target_squad_ids`→关联表 仍搁置。auth 注册手写签名+协议阅读 待实现（独立小批）。

新建领域模块时：先建 Maven 子模块（`<parent>` 指向 `hengde-volunteer-parent`、`relativePath` 空），在 api pom 加该模块依赖，再按领域内部分包约定写业务代码。

### 注意

- 四份设计文档（见 Design Docs）已统一为**领域垂直切分**口径，模块边界与本文件一致；如个别处仍有出入，**以本文件为准**。
- 写 Controller 前必须先查阅 `文档/url文档v1.md`（见「接口 URL 约定」节）。

## 落地范围（V1 首版）

首版范围见 `雷州市恒德爱心公益协会志愿者小程序V1.md`（每个功能均标注对应 xlsx 行号与所属领域）。概括：

- **纳入 V1**：志愿者注册/登录/实名 + 后台账号登录（auth）、角色体系（仅 游客/志愿者/管理团队）、活动发布与报名（粗粒度，暂不做签到/时长/公示闭环）、组织（志愿小组/归属分队/子账号与权限）、信息公示（轮播图/公告栏/全局搜索）。
- **暂不纳入 V1（后续版本）**：爱心企业（enterprise，依赖积分/社区）、活动临时负责人及其考试（organization）。

注意：V1 是全量需求的子集；本文件的「模块架构 / 角色」描述的是**全量规划**，不要把「V1 暂缓项」误当成系统不存在的功能。

## Common Commands

所有 Maven 命令在父工程目录 `hengde-volunteer-parent/` 下执行（仓库根目录没有 POM）。该目录自带 Maven Wrapper（`mvnw` / `mvnw.cmd`，Maven 3.9.15），可用 `./mvnw` 代替本机 `mvn`。

**重要：父 POM 是纯依赖管理、没有 `<modules>` 聚合段**，因此 `-pl <module>` / `-am` 这类 reactor 参数用不了；构建/测试单个模块必须用 `-f ../hengde-volunteer-<module>/pom.xml` 指向该模块自己的 POM。各模块经**本地仓库**解析父 POM 与彼此依赖，所以被依赖的模块改动后要先 `install` 才能被下游模块看到。

```bash
cd hengde-volunteer-parent

# 1) 安装父 POM 到本地仓库（改了父 POM 的版本/依赖管理后必须重跑）
./mvnw install -N

# 2) 构建并安装单个模块到本地仓库（按依赖顺序：common → auth → organization → activity → publicity → api）
./mvnw clean install -DskipTests -f ../hengde-volunteer-common/pom.xml

# 3) 运行某模块全部测试（JUnit 5 + Testcontainers MySQL/Redis，需本机 Docker）
./mvnw test -f ../hengde-volunteer-activity/pom.xml

# 4) 运行单个测试类/方法
./mvnw test -f ../hengde-volunteer-activity/pom.xml -Dtest=EnrollmentServiceTest

# 5) 启动应用（唯一可部署单元，需 MySQL/Redis 在线）
./mvnw spring-boot:run -f ../hengde-volunteer-api/pom.xml
```

## Module Architecture（规划）

**按领域垂直切分的模块化单体**：父工程 `hengde-volunteer-parent`（packaging=pom，纯依赖管理）下直接挂各个**领域模块**，每个领域是一个 jar，内部自带 controller/service/dao(mapper)/entity 三层；`hengde-volunteer-api` 依赖所有领域模块、持有唯一的 `@SpringBootApplication` 启动类，是唯一可部署单元。

```
hengde-volunteer-parent              ← 父工程，仅做版本/依赖管理
├── hengde-volunteer-common          ← 公共基础设施（返回体/异常/错误码、Redis、密码、短信通道+验证码、对象存储+上传校验、Excel、分页、Testcontainers 测试基座、全局搜索聚合）
├── hengde-volunteer-auth            ← 认证（小程序登录/注册/实名/企业微信群校验、后台账号登录/找回密码）
├── hengde-volunteer-user            ← 用户/我的（基本信息/志愿者证/证书/归属组织/保险/地址/安全中心）
├── hengde-volunteer-activity        ← 志愿活动/相册/推荐活动/我的活动/服务记录/活动负责人
├── hengde-volunteer-organization    ← 组织架构/志愿小组/活动临时负责人考试·资格/报名管理团队
├── hengde-volunteer-donate          ← 积分兑换/众筹/捐书/微心愿/助学结对/捐赠记录
├── hengde-volunteer-honor           ← 排行榜/榜样/评优评先/奖惩
├── hengde-volunteer-social          ← 社区(最热/关注/官方)/私信/互动/举报
├── hengde-volunteer-enterprise      ← 爱心企业
├── hengde-volunteer-publicity       ← 名单公示/公告/轮播图/文件下载
├── hengde-volunteer-data            ← 数据看板(含首页数据展示)/积分中心/投诉中心
└── hengde-volunteer-api (jar)       ← 启动类 + 全局配置；依赖以上全部领域模块，聚合首页/社区/我的接口
```

领域归属上几个容易混淆、四份文档已据此对齐的点：

- **首页数据看板/数据展示** 归 `data`（展示位置在首页，但领域不属于 publicity）。
- **投诉建议**（默认到监察部）归 `data`；**帖子举报** 归 `social`——两者是不同功能，不要混。
- **活动临时负责人考试·资格** 归 `organization`（不属于 activity）；activity 里的「活动负责人」指单次活动的现场负责人，是另一回事。
- **全局搜索** 跨领域，落在 `common`/`api` 聚合层。

此外有一个**独立于以上工程之外的平台级应用 ——「监管后台」**（运营方控制台），不属于 `hengde-volunteer-api`，单独部署：

```
监管后台（独立应用，凌驾于各组织实例之上）
├── 多系统部署    ← 一键为新社会组织开通/部署同款小程序及后台
├── 系统账号管理  ← 各组织后台账号密码、有效期、权限
├── 增值功能收费  ← 按年分配短信/存储/身份证校验额度，超额追加购买
├── 数据管理      ← 汇总监管各组织实例的数据与使用情况
└── 切换账号      ← 切入任意组织后台
```

每个领域模块的内部分包约定（以 user 为例）：

```
hengde-volunteer-user (jar)
└── com.hengde.user
    ├── controller   ← REST 接口
    ├── service      ← 业务逻辑
    ├── dao          ← MyBatis-Plus Mapper
    ├── entity       ← 数据库实体
    └── dto / vo     ← 出入参（用 MapStruct 与 entity 互转）
```

约定：

- **所有领域模块和 common 的 `<parent>` 都直接指向 `hengde-volunteer-parent`**（扁平结构，没有 dao/service 中间聚合层）。
- 领域间如需互相调用，依赖对方的 jar 并通过其 service 接口；**避免循环依赖**，公共能力下沉到 `hengde-volunteer-common`。
- 只有 `hengde-volunteer-api` 含启动类与 `main`，其余领域模块都是被依赖的库（不可独立启动）。**Flyway 迁移脚本集中放在 `common`**（`common/src/main/resources/db/migration/`，全局唯一版本序列；Flyway 依赖 `flyway-core`/`flyway-mysql`/`spring-boot-flyway` 也在 common），这样 api 运行期与各领域模块的 Testcontainers 测试都经依赖 common 拿到脚本并自动建表。`application.yml` 等运行期配置放 `api`。
- 虽然引入了 Spring Cloud / Alibaba 依赖，但当前形态是**单体**（api 聚合全部 controller）；尚未拆分为独立部署的微服务。
- **多组织部署 = 白标多实例**：每个社会组织一套独立部署（独立库 + 独立配置，跑同一份 api 制品），由「监管后台」统一开通管理。这与单实例后端是单体还是微服务无关。

## 接口 URL 约定

**写任何 Controller 前必读**，完整端点表见 `文档/url文档v1.md`。

### 路径格式

```
/{role}/{domain}/{resource}/{action?}
```

context-path `/api` 由 `server.servlet.context-path` 配置，Controller 代码里**不写**。

| 角色前缀 | 含义 |
|---|---|
| `/v` | 小程序志愿者端 |
| `/a` | 管理后台 |
| `/e` | 爱心企业端（V1 暂缓，路径预留） |

### 核心规则

- 资源路径用**复数名词**：`/activities`、`/announcements`、`/sub-accounts`
- 动作走 **HTTP Method**：GET 查询、POST 创建、PUT 全量更新、PATCH 局部更新、DELETE 删除
- **动词性操作**用子资源后缀，不造 `/doXxx`：

```
POST /v/activity/activities/{id}/enroll          报名
DELETE /v/activity/activities/{id}/enroll        取消报名
POST /v/activity/activities/{id}/proxy-enrollments  同小组代报名
POST /a/activity/enrollments/{id}/approve        审核通过
POST /a/activity/enrollments/{id}/reject         审核拒绝
POST /a/activity/activities/{id}/copy            复制活动
```

- 分页参数统一：`page`（从 1 开始）、`size`（默认 10，最大 100）
- 搜索参数统一：`keyword=`

### Sa-Token 路由鉴权白名单（已配置于 SaTokenConfigure）

| 路径 | 状态 |
|---|---|
| `/v/auth/login/wechat`、`/v/auth/sms/codes`、`/v/auth/wechat/group-membership` | 公开 |
| `/v/**` 其余（含 `/v/auth/register`、`/v/auth/logout`） | 需登录（注册用微信登录拿到的游客 token） |
| `/a/auth/login`、`/a/auth/sms/codes`、`/a/auth/password/reset` | 公开 |
| `/a/**` 其余 | 需登录（管理端独立 StpLogic，`StpAdminUtil`） |

## 依赖注入与测试约定

- **依赖注入统一用 setter 注入**：`@Autowired` 标在 **setter 方法**上（不是字段，也不用构造器）。注意 Lombok `@Setter` 生成的 setter **不带 `@Autowired`**，注入用的 setter 需手写；entity 等纯读写属性仍可用 Lombok。
- **测试统一用 `@SpringBootTest`**：不写纯 JUnit/Mockito 单测，所有测试都加载 Spring 上下文、由 Spring 经 setter 注入真实 bean，被测对象用 `@Autowired` 取得。
- 领域模块是无 `main` 的库，`@SpringBootTest` 找不到配置类，因此**每个领域模块必须在 `src/test/java/com/hengde/<domain>/` 放一个 `@SpringBootApplication` 启动类**（仅测试用，不进 src/main，模块对外仍是纯库）。
- DB 测试用 **Testcontainers-MySQL**（**不要 H2**——MyBatis-Plus 走 MySQL 方言、`flyway-mysql` 迁移不兼容），用 `@ServiceConnection` 自动接管 datasource、Flyway 在容器库跑迁移。**跑测试需本机有 Docker。**
- 共享容器配置在 `hengde-volunteer-common` 的 test-jar：`@Import(TestcontainersConfig.class)` 取 MySQL；用到 Redisson 锁的模块（如 activity）需**额外** `@Import(RedisTestcontainersConfig.class)`——引入 redisson starter 后 `RedissonClient` 在 bean 创建时即连 Redis，故这类模块每个 `@SpringBootTest` 都要有 Redis 容器。两份配置分开，避免强制不用锁的模块（如 organization）也连 Redis。
- **Docker api.version 坑**：docker-java 默认 API 版本可能低于本机 Docker Engine 的 `MinAPIVersion` 致 HTTP 400。已在父 POM surefire 配 `-Dapi.version=1.43`（系统属性，环境变量 `DOCKER_API_VERSION` 无效），无需写死 TCP 端口。
- **分页拦截器只在 api**：`PaginationInnerInterceptor` 在 api 的 `MybatisPlusConfig`，领域模块测试上下文没有它——`selectPage` 不会加 `LIMIT`，`total` 为 0、`records` 返回全部匹配行。领域模块测试**断言 `records` 内容而非 `total`**。

## Technology Stack

父 POM 已在 `dependencyManagement` 统一锁定版本，子模块引依赖时**不要写 version**。除 CLAUDE.md 易忽略的几项外，完整清单见 `hengde-volunteer-parent/pom.xml`。

| 技术 | 用途 |
|---|---|
| Spring Boot 4 + Spring Cloud 2025 + Spring Cloud Alibaba | 微服务基座（注意是 Boot 4 / Java 17，部分三方库需 jakarta 版本） |
| Sa-Token + JWT（jjwt） | 多角色认证（游客/志愿者/管理团队/活动临时负责人/爱心企业），token 存 Redis |
| MyBatis-Plus + HikariCP + MySQL | ORM 与连接池（`mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser`，版本 3.5.16；Boot 4 必须用 boot4 starter，boot3 starter 的 autoconfig 在 Boot 4 不创建 SqlSessionFactory）；Druid 不兼容 Spring Boot 4，已移除，改用 Boot 4 内置 HikariCP |
| Flyway（flyway-mysql） | 数据库版本迁移，SQL 放 `resources/db/migration/` |
| Redisson | 分布式锁（已用于 activity 报名并发；后续积分扣减等同理）。**Boot 4 必须用 4.x 线**（`redisson-spring-boot-starter:4.4.0` 按 spring-boot 4.0.6 构建；3.x 是 Boot 3 线）；父 POM 用独立 `${redisson.version}`，勿误用 `${redis.version}`（那是 Redis server 镜像号）。锁不指定 leaseTime，走 watchdog 自动续期 |
| XXL-Job | 定时任务（年级 9 月自动升级、活动状态变更等） |
| Aliyun OSS / MinIO | 对象存储（头像、活动照片、文件下载等）；V1 已落地 Aliyun OSS（`common` 的 `FileStorageService`），MinIO 留作备选实现 |
| EasyExcel + Apache POI | 后台批量导入/导出（志愿者名单、捐书物资等）；注：`com.alibaba:easyexcel:4.0.3` 是空壳包，实体类透传依赖 `easyexcel-core` |
| MapStruct | Entity ↔ DTO 映射 |
| ZXing | 活动签到/签退二维码、积分兑换/捐书条形码 |
| iText (itext-core) | 协会电子证书 PDF 生成 |
| weixin-java-miniapp / -mp / -cp / -pay | 小程序登录与订阅消息 / 服务号模板消息 / 企业微信群校验 / 微信支付 |
| volc-sdk-java | 火山引擎短信（验证码、通知；签名「雷州市恒德爱心公益协会」）。注：火山无独立短信 artifact，用 all-in-one 的 `com.volcengine:volc-sdk-java`（1.0.x），短信能力在 `com.volcengine.service.sms` |
| Sentinel | 接口限流 |
| WebSocket | 社区私信实时通信 |
| springdoc + Knife4j | OpenAPI 接口文档 |
| Hutool / FastJSON2 / Guava / commons-lang3 | 通用工具 |
| Testcontainers (MySQL) + JUnit 5 | 集成测试 |

## User Roles

全量规划共 5 个角色，权限逐级递增（V1 仅落地第 1、2、4 三个，第 3、5 暂缓）：

1. **游客** — 已登录小程序但未完成实名注册
2. **志愿者** — 完成身份证二要素实名认证
3. **活动临时负责人** — 通过考试的志愿者，可管理单次活动（考试/资格归 organization 领域；**V1 暂缓**）
4. **管理团队** — 各部门（组织部/秘书部/宣传部/监察部等）子账号，细粒度功能/数据/审核权限
5. **爱心企业** — 独立账号，可发布积分商品、发帖，不参与志愿活动（**V1 暂缓**）

## Design Docs（需求与拆分的权威来源）

实现功能前先对照这些文档，它们定义了页面结构、业务规则与模块边界。四份文档已统一为领域垂直切分口径：

- `小程序设想【第十版】.xlsx` — **需求源头**：前端逐功能需求 + 后台要求（`前端` Sheet，约 77 行；列含 前端信息/前端备注/后端功能要求备注(CF)/后端功能备注(CG)）；含微信模板消息规划。
- `业务领域划分.md` — 12 大业务领域的功能树（用户认证/志愿活动/积分/捐赠/组织/企业/社区/荣誉/公示/个人中心/后台/SaaS 监管后台）。
- `志愿者小程序开发.md` — 小程序三 Tab（首页/社区/我的）页面树 + 领域垂直切分模块表 + POM 约定 + 多系统部署说明；模块结构与本文件一致。
- `雷州市恒德爱心公益协会志愿者小程序V1.md` — **V1 首版落地范围**：逐功能标注对应 xlsx 行号与所属领域，并就近附拆分后的需求明细表；文末列出 V1 暂缓项。
- `志愿平台设想【第十版】.pdf` — 设计与流程图（捐书/微心愿物资流转等）。
- `url文档v1.md` — **V1 全量接口路径约束表**：按角色前缀（/v、/a）和领域分组，含鉴权要求和暂缓说明；**写 Controller 时以此为准**。

小程序前端分三个 Tab：**首页 / 社区 / 我的**。后台还有面向运营的管理端，以及一个可为其他社会组织一键部署同款系统的 **SaaS 监管后台**（多租户）。
