# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **这个文件只放规矩和路由。** 各模块的现行要点与历史记录在 `知识库/模块/*.md`（改到某个模块时，`代码/hengde-volunteer-*/CLAUDE.md` 会自动把对应页导入进来）；
> 跨模块的约定与踩坑在 `知识库/横切/`，后台控制台在 `知识库/跨端/`。2026-10-17 从 15 万字符拆下来，拆分前的盘点见 `文档/知识库-现状评审-2026-10-17.md`。

## Project Overview

雷州市恒德爱心公益协会志愿者管理平台：微信小程序 + 后台管理控制台 + Spring Boot 4 后端（Java 17，**模块化单体**）。后端所有模块 `groupId=com.hengde`，`version=2.0.1`。**版本号只在父 POM 维护**：子模块不写自身 `groupId`/`version`，模块之间互相引用也不写 version（父 POM `dependencyManagement` 的「本项目内部模块」段按 `${project.version}` 统一管）；唯一例外是各子模块 `<parent>` 里的版本号（Maven 3 要求写死）——改版本号用 `versions:set` 一条命令改完这 15 处，见「Common Commands」。

## 当前状态（重要）

**四个独立仓库**（2026-07-28 拆分），三端代码不在同一个工作区：

| 内容 | 位置 | 远程 |
|---|---|---|
| 后端（14 个 Maven 模块 + 父工程） | 本仓库 `代码/`（**自身是独立 git 仓库**，本仓库也跟踪它） | Gitee `volunteerplatform-backend` |
| 微信小程序 | **`../volunteerplatform-miniprogram/`**（同级目录） | Gitee `volunteerplatform-miniprogram` |
| 后台管理控制台 | **`../volunteerplatform-back/`**（同级目录） | Gitee `volunteerplatform-back` |
| 文档 / 知识库 / 部署配置 | 本仓库 `文档/`、`知识库/`、`部署/`、`tools/` | GitHub `volunteerplatform` |

本仓库内原先的 `hengde-volunteer-miniprogram/`、`volunteer-platform-back/` 已清空——**改前端要去同级目录**。后端各模块的 `<parent>` 用 `relativePath` 指向 `../hengde-volunteer-parent/pom.xml`。

- **控制台**：无构建、纯 `React.createElement`，入口 `index.html` + `assets/`；`assets/api.js` 经 `window.__API_BASE__` 适配同源 / nginx 分离部署。⚠️ **在本地打开时基址指向线上**，联调用 `tools/serve_admin_local.py`。详见 `知识库/跨端/后台控制台.md`。
- **小程序**：原生 `app.json` / `pages` / `utils`；`utils/api-mode.js` 的 `DEFAULT_USE_MOCK_API = true`——**默认走 mock**。
- **迁移**：全项目唯一序列在 common，当前到 **V100**；权限点现行 **99** 个。
- **线上**：2026-10-17 仍是 9 月的旧包，新代码未部署（`GET /api/actuator/info` 可不登录查版本）。

## 知识库与路由

先按任务找到模块，再读那一页的「现行要点」：

| 模块 | 管什么 | 页面 |
|---|---|---|
| common | 基础设施、**迁移序列**、短信模板登记、上传校验、锁助手 | `知识库/模块/common.md` |
| api | 启动与全局配置、鉴权路由、Jackson、上传端点、webhook 控制器 | `知识库/模块/api.md` |
| auth | 三套登录态、实名、志愿者 PII 与只读查询、**处置表与闸门**、**通知短信入口**、注销冷静期 | `知识库/模块/auth.md` |
| organization | 子账号与权限点、小组、分队、组织架构、报名管理团队、**问卷引擎**、临时负责人考试 | `知识库/模块/organization.md` |
| publicity | 轮播图、公告、文件下载 | `知识库/模块/publicity.md` |
| activity | 活动、报名、签到、时长、名单公示、相册、**积分账本** | `知识库/模块/activity.md` |
| user | 志愿者管理、我的资料、地址、年级升级、志愿者证 | `知识库/模块/user.md` |
| data | 数据看板与汇总、**投诉建议** | `知识库/模块/data.md` |
| honor | 排行榜、勋章与榜样、**电子证书**、**奖惩与申诉** | `知识库/模块/honor.md` |
| trade | 微信支付收付、退款、对账（未真实调用过） | `知识库/模块/trade.md` |
| donate | **积分商城**、捐书与物资流转、微心愿、结对、众筹、捐款与发票、物流 | `知识库/模块/donate.md` |
| system | 操作日志、水印、菜单排序、编号、文件网盘 | `知识库/模块/system.md` |
| social | 社区、审核与风控、举报、禁言、私信 | `知识库/模块/social.md` |
| enterprise | 爱心企业账号、赞助商品、企业积分、企业帖、评价 | `知识库/模块/enterprise.md` |

常见的跨模块话题：**积分**（账本在 activity；商城在 donate；企业积分在 enterprise；排行在 honor）· **证书**（activity 确认考勤 / donate 捐赠事件 → honor 出证）· **处置与禁言**（表与闸门在 auth；开单在 honor；社区禁言在 social）· **通知短信**（模板在 common，入口在 auth）· **收付**（trade；订阅方在 donate）。

其他材料：协会答复与决策 → `文档/待协会答复-2026-09-27.md`（回执五）与 `文档/v2/协会待确认清单.md`（回执一～四）；接口 → `文档/v1~v4/url文档v*.md`；部署 → `文档/v1/部署说明.md`、`部署/`；跨端联调 → `文档/小程序联调反馈-*.md`、`文档/小程序待做-*.txt`。

**维护规则**：每批收尾时更新所涉模块页的「现行要点」（只写现在是什么）和「按批次的记录」（为什么、撞过什么）；根文件只改规矩与路由，不再往这里堆模块细节。

## 硬规矩（一句话版，完整说明见 `知识库/横切/约定与踩坑.md`）

- **迁移**只放 common 的 `db/migration/`；挑号前跑跨分支最大号命令（见 `知识库/模块/common.md`）；跑过的不能原地改；一个文件只放一条有失败可能的语句；脚本连注释里也不能出现美元花括号占位符。
- **积分只认账本**：读写一律经 `PointService`，不要 `SUM(activity_attendance.points_award)`；新来源独占类型码。
- **通知短信只走 `SmsNotifyService`**，模板先在 `SmsNotifyTemplate` 登记（键 + 变量名 + 顺序）。
- **分布式锁用 `DistributedLockSupport`**，锁在事务外取；「幂等键 + 唯一键」挡不住并发重放时按幂等键上锁。
- **CAS 失败之后的复核读必须是当前读**；**别对可能还不存在的行加锁或做范围 UPDATE**（间隙锁会死锁）。
- **`AFTER_COMMIT` 监听里写库用 `REQUIRES_NEW`**；高并发入口改为事务外发布 + 订阅方 `fallbackExecution = true`。
- **第三方 webhook 的应答直接写 `HttpServletResponse`**，不要返回 `String`。
- **魔法数收在各模块 `constant` 持有类**，新增状态判断复用 / 扩展它们。
- **测试**：变异验证必须 clean 构建并重装本轮动过的所有模块；赛跑类并发用例断言每种结局都出现过；api 全量跑一次才算绿。
- **Excel 依赖钉**：升 `poi.version` 时 `commons-compress` 与 `commons-io` 一起改，并跑一次真实导入导出。
- **线上接口「GET 就 500」**先查 `/actuator/info` 确认部署的是不是新包。

## 未实现与冻结

- **监管后台（SaaS 多租户）**：未建。多组织部署＝白标多实例（每个组织一套独立部署）。
- **冻结**：纸质证书申请、i志愿证书（第 4B 批）；评优评先按 Row 47「需预留」推迟。
- **XXL-Job 未接**：定时任务走 Spring `@Scheduled`（接它要先部署独立的调度中心）。
- **只验到假渠道 / 未端到端**：微信支付、快递100 订阅推送、小程序码、对象存储视频直传、服务号模板消息（服务号 openid 前置未解决）。

## Common Commands

所有 Maven 命令在父工程目录 `hengde-volunteer-parent/` 下执行（仓库根目录没有 POM）。该目录自带 Maven Wrapper（`mvnw` / `mvnw.cmd`，Maven 3.9.15），可用 `./mvnw` 代替本机 `mvn`。

**重要：父 POM 现已带 `<modules>` 聚合段（全部 14 个模块，按依赖顺序 common→auth→organization→publicity→activity→**trade→donate**→user→honor→**social→enterprise→data→system**→api；⚠️ V4 数据汇总批起 **data 排在 enterprise 之后**——它要调 social / enterprise / honor 的只读汇总口径）**——在父目录直接 `./mvnw install -DskipTests` 即可全量按序构建，`-pl`/`-am` 可用。**2026-07-02 教训**：此前该列表漏了 user/data 两模块，全量 reactor 构建「SUCCESS」但 api 打包进的是本地仓库里的旧 user/data jar（新功能悄悄缺失）——全量构建后核对 Reactor Summary 是否列全 **14 个模块（含 parent 共 15 行）**。**新增模块时必须同步这里的数字与顺序**，否则这条检查会悄悄失效——它存在的意义正是发现「少了一个模块」。单模块构建/测试仍用 `-f ../hengde-volunteer-<module>/pom.xml`；用 `-f` 时父 POM 经 `relativePath` 从磁盘读，彼此依赖仍经**本地仓库**解析，被依赖的模块改动后要先 `install` 才能被下游模块看到。

```bash
cd hengde-volunteer-parent

# 0) 改版本号：父 POM + 14 个子模块 <parent> 里的版本号共 15 处，一条命令改完（2.0.1 升级时实测只动那 12 行；V4 社区核心批加了 social，爱心企业批加了 enterprise，系统治理批加了 system）。
#    jar 文件名随之改变，服务器上 systemd 只认软链，发版步骤见 文档/v1/部署说明.md 第 6 节
./mvnw versions:set -DnewVersion=2.0.2 -DgenerateBackupPoms=false

# 1) 安装父 POM 到本地仓库：只在走下面第 2 步那种 -f 单模块构建、又没跑过全量 install 时需要
#    （被依赖模块装进本地仓库的 POM 引用着父 POM，要从本地仓库找）；全量构建直接读磁盘上的父 POM，并会顺带装好。
#    ⚠️ 别把子模块的 relativePath 改回空：那样全量构建也只认本地仓库里装过的父 POM——改了版本号不先 install -N
#    直接报 Non-resolvable parent POM（2.0.1 升级时实测），只改依赖管理则静默用旧的那份
./mvnw install -N

# 2) 构建并安装单个模块到本地仓库（按依赖顺序：common → auth → organization → publicity → activity → trade → donate → user → honor → social → enterprise → data → system → api，与父 POM <modules> 一致）
./mvnw clean install -DskipTests -f ../hengde-volunteer-common/pom.xml

# 3) 运行某模块全部测试（JUnit 5 + Testcontainers MySQL/Redis，需本机 Docker）
./mvnw test -f ../hengde-volunteer-activity/pom.xml

# 4) 运行单个测试类/方法
./mvnw test -f ../hengde-volunteer-activity/pom.xml -Dtest=EnrollmentServiceTest

# 4b) 压力测试（JUnit @Tag("stress")，surefire 默认排除；排除优先于包含，所以两个参数都要给）
#     服务层：几十到几百线程，断言四本账守恒；HTTP 全链路：api 模块 RANDOM_PORT 起真 Tomcat + Testcontainers
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -f ../hengde-volunteer-donate/pom.xml
#     donate 的 stress 类：MallCouponStressTest / BookDonationStressTest / WishStressTest / PairStressTest / LogisticsPushStressTest / MallExpressStressTest / DonationStressTest
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -f ../hengde-volunteer-trade/pom.xml
#     trade 的 stress 类：TradeStressTest（回调 / 查单 / 扫描三路同时写回同一笔支付 + 并发退款）
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=FormStressTest -f ../hengde-volunteer-organization/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=ComplaintStressTest -f ../hengde-volunteer-data/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=AddressConcurrencyTest -f ../hengde-volunteer-user/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=StructureConcurrencyTest,ExamConcurrencyTest -f ../hengde-volunteer-organization/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=RosterSquadStressTest,AlbumConcurrencyTest -f ../hengde-volunteer-activity/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=SocialStressTest,SocialGovStressTest,SocialChatConcurrencyTest -f ../hengde-volunteer-social/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=EnterpriseConcurrencyTest,EnterpriseSponsorConcurrencyTest,EnterpriseSocialConcurrencyTest -f ../hengde-volunteer-enterprise/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=SystemConcurrencyTest -f ../hengde-volunteer-system/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=VolunteerCardServiceTest -f ../hengde-volunteer-user/pom.xml
./mvnw test -Dtest.excludedGroups=none -Dgroups=stress -Dtest=MallCouponApiStressTest -f ../hengde-volunteer-api/pom.xml

# ⚠️ 仓库放在 iCloud 同步的桌面上时，target/ 里会冒出「X 2.class」「classes 2」这类冲突副本
#    （api 打包报「两个 main class」、Spring 扫描可能扫到重复类）——构建一律带 clean：
#    ./mvnw -o -q clean install -DskipTests

# 5) 启动应用（唯一可部署单元，需 MySQL/Redis 在线）
./mvnw spring-boot:run -f ../hengde-volunteer-api/pom.xml
```

## Module Architecture

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
├── hengde-volunteer-social          ← 社区(最热/关注/官方)/私信/互动/举报（V4 社区核心批已建：帖子/关注/主页/官方帖）
├── hengde-volunteer-enterprise      ← 爱心企业（V4 爱心企业批已建：账号 / 第三套登录态 / 审核 / 主页）
├── hengde-volunteer-system          ← 系统治理（操作日志/界面水印/菜单排序/十位编号/文件网盘，V4 系统治理批已建）
├── hengde-volunteer-publicity       ← 名单公示/公告/轮播图/文件下载
├── hengde-volunteer-data            ← 数据看板(含首页数据展示)/积分中心/投诉中心
└── hengde-volunteer-api (jar)       ← 启动类 + 全局配置；依赖以上全部领域模块，聚合首页/社区/我的接口
```

领域归属上几个容易混淆、各设计文档已据此对齐的点：

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
    └── dto / vo     ← 出入参（手写转换；MapStruct 虽在父 POM 里声明，代码里没用）
```

约定：

- **所有领域模块和 common 的 `<parent>` 都直接指向 `hengde-volunteer-parent`**（扁平结构，没有 dao/service 中间聚合层）。
- 领域间如需互相调用，依赖对方的 jar 并通过其 service 接口；**避免循环依赖**，公共能力下沉到 `hengde-volunteer-common`。
- 只有 `hengde-volunteer-api` 含启动类与 `main`，其余领域模块都是被依赖的库（不可独立启动）。**Flyway 迁移脚本集中放在 `common`**（`common/src/main/resources/db/migration/`，全局唯一版本序列；Flyway 依赖 `flyway-core`/`flyway-mysql`/`spring-boot-flyway` 也在 common），这样 api 运行期与各领域模块的 Testcontainers 测试都经依赖 common 拿到脚本并自动建表。`application.yml` 等运行期配置放 `api`。
- **当前形态是单体**（api 聚合全部 controller）。父 POM 里声明过 Spring Cloud / Alibaba，但代码里一处都没用，不要按微服务的方式写。
- **多组织部署 = 白标多实例**：每个社会组织一套独立部署（独立库 + 独立配置，跑同一份 api 制品），由「监管后台」统一开通管理。这与单实例后端是单体还是微服务无关。

## 接口 URL 约定

**写任何 Controller 前必读**，完整端点表分在 `文档/v1~v4/url文档v*.md` 四份里（按版本切）；改完跑 `python3 tools/verify_url_contract.py`，它逐条核对代码与文档。

### 路径格式

```
/{role}/{domain}/{resource}/{action?}
```

context-path `/api` 由 `server.servlet.context-path` 配置，Controller 代码里**不写**。

| 角色前缀 | 含义 |
|---|---|
| `/v` | 小程序志愿者端 |
| `/a` | 管理后台 |
| `/e` | 爱心企业端（V4 起，第三套登录态） |

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
| `/v/auth/login/wechat`、`/v/auth/login/dev`（开发登录，dev 限定）、`/v/auth/login/sms`、`/v/auth/login/password`、`/v/auth/password/reset`、`/v/auth/agreement`、`/v/auth/sms/codes`、`/v/auth/wechat/group-membership`、**`/v/user/volunteer-cards/verify`**（志愿者证扫码核验，V4 志愿者证批；只收随机令牌） | 公开 |
| `/v/**` 其余（含 `/v/auth/register`、`/v/auth/logout`） | 需登录 + **账号状态校验**（`checkVolunteerEnabled`：每次查 volunteer status，非 NORMAL（禁用/注销/已删）即登出 403，与管理端 `checkAdminEnabled` 同口径；游客 NORMAL 照常放行；兜底「停用但 token 仍在」越权窗口，覆盖改资料/换绑手机号/上传等本人写接口） |
| `/a/auth/login`、`/a/auth/sms/codes`、`/a/auth/password/reset` | 公开 |
| `/e/auth/login`、`/e/auth/register`、`/e/auth/sms/codes`、`/e/auth/password/reset`（V4 爱心企业批） | 公开 |
| `/e/**` 其余 | 需企业登录（第三套 StpLogic `StpEnterpriseUtil`，与 `/v`、`/a` token 互不通用）+ **`EnterpriseAccountGate` 按当前状态放行**：已删除 / 已暂停踢出 403；待审核 / 已驳回只放行 `/e/auth/**`、`/e/enterprise/profile(/**)`、`/e/files/image`；正常放行 |
| `/a/**` 其余 | 需登录（管理端独立 StpLogic，`StpAdminUtil`） |
| `/share/files/{token}`（文件分享，V4 系统治理批） | **公开，靠随机令牌**；要不要登录由那条分享自己的开关决定（服务层判）。与 `/callback/**` 同一机制：它不在 `/v` `/a` `/e` 之下，落不到任何 `check` 上才得以放行 |
| `/ws/social/chat?token=`（私信 WebSocket，V4 私信批） | 不在 `/v` `/a` `/e` 之下，路由拦截器管不着；**身份由握手时的 token 参数认**（握手处理器在 social）。线上 nginx 要放行 `Upgrade` / `Connection` 头 |
| `/actuator/info` | 公开，只报构建版本、构建时间与迁移版本号——不登录就能分辨线上跑的是不是新包 |
| `/callback/**`（微信支付 webhook，V3 trade 批） | **公开，靠验签不靠登录态**。⚠️ 机制要说准：拦截器注册在 `/**` 上，只是处理器内部只对 `/v/**` `/a/**` `/e/**` 做 `check`，别的路径落不到任何 `check` 上才得以放行——**往处理器里加一条全局 `match("/**")` 会让两条 webhook 当场变成 401 且毫无征兆**（钱已经收了、微信只看到通知失败），由 `TradeCallbackApiTest` 钉住 |

## 依赖注入与测试约定

- **依赖注入统一用 setter 注入**：`@Autowired` 标在 **setter 方法**上（不是字段，也不用构造器）。注意 Lombok `@Setter` 生成的 setter **不带 `@Autowired`**，注入用的 setter 需手写；entity 等纯读写属性仍可用 Lombok。
- **测试统一用 `@SpringBootTest`**：不写纯 JUnit/Mockito 单测，所有测试都加载 Spring 上下文、由 Spring 经 setter 注入真实 bean，被测对象用 `@Autowired` 取得。
- 领域模块是无 `main` 的库，`@SpringBootTest` 找不到配置类，因此**每个领域模块必须在 `src/test/java/com/hengde/<domain>/` 放一个 `@SpringBootApplication` 启动类**（仅测试用，不进 src/main，模块对外仍是纯库）。
- DB 测试用 **Testcontainers-MySQL**（**不要 H2**——MyBatis-Plus 走 MySQL 方言、`flyway-mysql` 迁移不兼容），用 `@ServiceConnection` 自动接管 datasource、Flyway 在容器库跑迁移。**跑测试需本机有 Docker。**
- 共享容器配置在 `hengde-volunteer-common` 的 test-jar：`@Import(TestcontainersConfig.class)` 取 MySQL；用到 Redisson 锁的模块（**activity** 报名 / **organization** 小组 create·join 并发，二者均引 redisson starter）需**额外** `@Import(RedisTestcontainersConfig.class)`——starter 装配的 `RedissonClient` 在 bean 创建时即连 Redis，故这类模块每个 `@SpringBootTest` 都要有 Redis 容器。两份配置分开，避免无锁上下文也连 Redis：`common` 自身只引 redisson **核心库**（API、无 starter）故不建 `RedissonClient`、其测试仅需 MySQL，`auth`/`publicity` 同理只取 MySQL。
- **Docker api.version 坑**：docker-java 默认 API 版本可能低于本机 Docker Engine 的 `MinAPIVersion` 致 HTTP 400。已在父 POM surefire 配 `-Dapi.version=1.43`（系统属性，环境变量 `DOCKER_API_VERSION` 无效），无需写死 TCP 端口。
- **api 模块多个测试上下文与 Sa-Token 的静态状态（2026-09-17 全量回归撞出）**：HTTP 用例按 `properties` 不同会起好几个 Spring 上下文，每个上下文各有自己的 MySQL / Redis 容器；而 Sa-Token 的权限数据源 `StpInterface` 与令牌存储 `SaTokenDao` 放在 JVM 级静态的 `SaManager` 里，**最后启动的上下文会覆盖它**——复用更早缓存的上下文时，查权限走的是别的库，子账号授权一律 403（`ExamApiFlowTest` / `SocialApiFlowTest` / `SocialGovApiFlowTest` 单独跑绿、全量跑红）。已由测试专用的 `com.hengde.api.support.SaTokenContextRebinder`（登记在 api 的 `src/test/resources/META-INF/spring.factories`）在每个测试类 / 方法前重新指回当前上下文；**新增会建子账号的 HTTP 用例不用再管**，但单独跑绿≠全量跑绿，批次收尾要跑一次 api 全量。生产只有一个上下文，不受影响。
- **分页拦截器只在 api**：`PaginationInnerInterceptor` 在 api 的 `MybatisPlusConfig`，领域模块测试上下文没有它——`selectPage` 不会加 `LIMIT`，`total` 为 0、`records` 返回全部匹配行。领域模块测试**断言 `records` 内容而非 `total`**。

## Technology Stack

父 POM 已在 `dependencyManagement` 统一锁定版本，子模块引依赖时**不要写 version**。

**实际在用的**：

| 技术 | 用途 |
|---|---|
| Spring Boot 4（Java 17） | 基座；部分三方库需 jakarta 版本 |
| Sa-Token | 三套登录态（志愿者 / 后台 / 企业），token 存 Redis |
| MyBatis-Plus 3.5.16 + HikariCP + MySQL ≥ 8.0.17 | ORM（Boot 4 必须用 `mybatis-plus-spring-boot4-starter`）；Druid 不兼容 Boot 4，已移除 |
| Flyway（flyway-mysql） | 迁移，脚本集中在 common |
| Redisson 4.x | 分布式锁（Boot 4 必须用 4.x 线；父 POM 用 `${redisson.version}`，勿误用 `${redis.version}`）；锁不指定 leaseTime，走 watchdog 续期 |
| Redis | Sa-Token、验证码、热度、缓存 |
| 阿里云 OSS / 火山 TOS | 对象存储（`FileStorageService` 两家实现） |
| 火山引擎短信（`volc-sdk-java`） | 验证码与通知（签名「雷州市恒德爱心公益协会」） |
| 腾讯云 faceid | 身份证二要素实名核验 |
| weixin-java（binarywang） | 小程序登录与小程序码、微信支付 |
| EasyExcel + Apache POI | 导入导出（`easyexcel:4.0.3` 是空壳包，实体透传 `easyexcel-core`） |
| ZXing / iText | 二维码与条码 / 电子证书 PDF |
| Hutool | 身份证与统一社会信用代码校验等 |
| Spring WebSocket | 私信推送 |
| Swagger 注解 + Knife4j | 接口文档（`/api/doc.html`，生产 403） |
| Testcontainers（MySQL / Redis）+ JUnit 5 | 集成测试 |

**父 POM 里声明了但代码里没用的**（别照着写）：Spring Cloud / Alibaba、Sentinel、MapStruct、XXL-Job、MinIO、FastJSON2、Guava。

## User Roles

1. **游客** — 已登录小程序但未完成实名注册
2. **志愿者** — 完成身份证二要素实名认证
3. **活动临时负责人** — 考试通过的志愿者（考试与资格在 organization；资格按时间现算）
4. **管理团队** — `manager_flag=1` 的志愿者；后台的各部门子账号另有细粒度权限点（组织部 / 秘书部 / 宣传部 / 理事会等；**监察部、分队管理部已撤销**）
5. **爱心企业** — 独立账号（`/e`），可发布赞助商品、发帖，不参与志愿活动

## Design Docs（需求与拆分的权威来源）

实现功能前先对照这些文档，它们定义了页面结构、业务规则与模块边界。其中描述模块边界的几份已统一为领域垂直切分口径：

- `文档/小程序设想【第十版】.xlsx` — **需求源头**：前端逐功能需求 + 后台要求（`前端` Sheet，约 77 行；列含 前端信息/前端备注/后端功能要求备注(CF)/后端功能备注(CG)）；含微信模板消息规划。
- `文档/业务领域划分.md` — 12 大业务领域的功能树（用户认证/志愿活动/积分/捐赠/组织/企业/社区/荣誉/公示/个人中心/后台/SaaS 监管后台）。
- `文档/志愿者小程序开发.md` — 小程序三 Tab（首页/社区/我的）页面树 + 领域垂直切分模块表 + POM 约定 + 多系统部署说明；模块结构与本文件一致。
- `文档/v1/雷州市恒德爱心公益协会志愿者小程序V1.md` — **V1 首版落地范围**（历史）：逐功能标注对应 xlsx 行号与所属领域，并就近附拆分后的需求明细表；文末列出 V1 暂缓项。
- `文档/志愿平台设想【第十版】.pdf` — 原型（127 页）（捐书/微心愿物资流转等）。
- `文档/v1~v4/url文档v*.md` — **接口路径约束表**（四份，按版本切；契约脚本核对过）；**写 Controller 时以此为准**。
- `文档/待协会答复-2026-09-27.md` — **V2 余项 + V3 全部的待协会答复汇总**（与原型逐页、协会四次答复逐条核对过，文末有自查记录）；第四部分是「协会已答、代码未跟上」的开发待办（2026-09-29 除申诉短信模板外已全部落地，见该节回执）；**监察部与分队管理部一年前已撤销**，材料里凡写这两个部门的，一律按协会 9/2、9/16 的答复改读。发协会的纯文本版是 `文档/发协会-待答复-2026-09-27.txt`，给小程序开发的待做清单是 `文档/小程序待做页面-2026-09-29.txt`。
- `文档/功能清单.md` — **全量需求 × 完成状态对照表**：按 `业务领域划分.md` 的 12 领域逐功能列出，前缀打勾（✅已完成/🔶部分/⬜未实现），以 `知识库/模块/*.md` 为完成判定权威；⚠️ **停在 2026-09-19**，缺 V84 之后两批。
- `文档/v1/自测清单.md` — 本地起 api 后照着点一遍的**手动自测清单**：按 Knife4j 分组顺序、带可直接粘的请求体与预期；配套的 `文档/v1/volunteer.md`/`admin.md` 是 **7 月的 Knife4j 导出、已严重过期**，查接口请用 URL 文档或运行中的 `/api/doc.html`。
- `文档/v1/后台管理页面设计prompt.md` — **后台管理前端设计 prompt**：枚举全部已实现 `/a` 接口/按钮/可视化，供据以实现后台控制台；与 `../volunteerplatform-back/` 配套（V1 时期的，之后的页面见 `知识库/跨端/后台控制台.md`）。
- `文档/恒德志愿者平台-已完成功能与架构设计说明.docx` — **对外交付说明文档**（2026-07-03 生成）：已完成功能清单 + 架构层次 + 设计模式详解，宋体小四正式排版，面向需求方/第三方阅读；⚠️ 早已过期，重大交付后应从知识库重新生成。

小程序前端分三个 Tab：**首页 / 社区 / 我的**。后台还有面向运营的管理端，以及一个可为其他社会组织一键部署同款系统的 **SaaS 监管后台**（多租户）。
