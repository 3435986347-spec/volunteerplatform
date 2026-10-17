# AGENTS.md

雷州市恒德爱心公益协会志愿者管理平台的**总入口**，写给所有 coding agent（Codex、Claude Code、Cursor……）。Claude Code 经根 `CLAUDE.md` 的 `@AGENTS.md` 读到同一份。

> **这个文件只放硬规矩和路由**，正文在 `知识库/`（同时发布为独立仓库 Gitee `henge-volunteerplatform-knowlegebase`）。
> 先按任务找到页，再读那一页的「现行规则 / 现行要点」。冲突时：**代码 > 知识库现行要点 > 按批次的记录 > `文档/` 里的规划与历史材料**。

## 项目一句话

微信小程序 + 后台管理控制台 + Spring Boot 4 后端（Java 17，**按领域垂直切分的模块化单体**，14 个 Maven 模块，`groupId=com.hengde`，`version=2.0.1`）。版本号只在父 POM 维护（子模块不写自身 `groupId` / `version`，互相引用不写 version；`<parent>` 里的版本号例外，改版本用 `versions:set`）。

## 工作区与四个仓库

```
<工作区>/
├── volunteerplatform/                文档仓库（GitHub）：本文件、知识库/、文档/、部署/、tools/
│   ├── 知识库/                       知识库源头；以快照提交发布到 Gitee 知识库仓库
│   └── 代码/                         后端仓库（Gitee volunteerplatform-backend，自身是独立 git，文档仓库也跟踪它）
├── volunteerplatform-miniprogram/    小程序（Gitee）
└── volunteerplatform-back/           后台控制台（Gitee）
```

- 改前端去同级目录；文档仓库里原先的 `hengde-volunteer-miniprogram/`、`volunteer-platform-back/` 已清空。
- 后端改动要在 `代码/`（Gitee）与文档仓库各提交一次，提交说明同名并注明「同 Gitee 后端仓库同名提交」。
- **单独克隆后端仓库时**，把知识库仓库克隆到与它同级、名为 `知识库` 的目录，模块入口里的 `../../知识库/…` 路径才成立。

## 当前状态

- **迁移**：全项目唯一序列在 common，当前到 **V100**，下一个从 V101 起；**权限点现行 99 个**（清单 `知识库/生成/权限点.md`，脚本生成）。
- **控制台**：无构建、纯 `React.createElement`；⚠️ **在本地打开 `index.html` 时 API 基址指向线上**，点一下就写线上库，联调用 `tools/serve_admin_local.py`。见 `知识库/跨端/后台控制台.md`。
- **小程序**：`app.js` 的 `globalData.useMockApi = false`、`apiBaseUrl` 指向**线上**服务器（开发版放行 HTTP）——开发者工具里点的也写线上库；本地联调改成 `http://localhost:8080/api`。（`utils/api-mode.js` 的 `DEFAULT_USE_MOCK_API` 只在 `app.js` 没给值时生效，现为 `false`；小程序 `README.md` 里「默认 mock」的说法已过期。）
- **线上**：2026-10-17 仍是 9 月的旧包；`GET /api/actuator/info` 免登录查版本与迁移号。
- **只验到假渠道 / 未端到端**：微信支付、快递100 推送、小程序码、对象存储视频直传、服务号模板消息。

## 知识库路由

| 想知道 | 读 |
|---|---|
| 业务口径、功能状态、需求出处、待办（**按业务说的任务从这里进**） | `知识库/领域/01…12-*.md`（用户与身份认证 / 志愿活动 / 积分体系 / 公益捐赠 / 组织管理 / 爱心企业 / 社区交流 / 荣誉展示 / 信息与公示 / 个人中心 / 后台管理系统 / 监管后台） |
| 某个模块的代码不变式、关键类、承重测试、踩过的坑（**改代码前读**） | `知识库/模块/<模块>.md` |
| 协会答复与口径（统一编号 **XH01…**，原编号如「乙-5」「丙-3」也能 grep 到） | `知识库/决策/协会决策.md` |
| 需求原文（「Row 47」＝表格行号、「原型 P83」＝PDF 页码） | `知识库/需求/需求表.md`、`知识库/需求/原型.md` |
| 接口清单、权限点、表、迁移（**脚本生成，不手改**） | `知识库/生成/` |
| 跨模块套路：并发与锁、积分口径、通知与处置、测试纪律、依赖与部署 | `知识库/横切/` |
| 后台控制台的组件坑与联调方式 | `知识库/跨端/后台控制台.md` |
| 跨模块批次的历史记录 | `知识库/变更日志.md` |

| 模块 | 管什么 | | 模块 | 管什么 |
|---|---|---|---|---|
| common | 基础设施、**迁移序列**、短信模板登记、上传校验、锁助手 | | trade | 微信支付收付、退款、对账（未真实调用过） |
| api | 启动与全局配置、鉴权路由、Jackson、上传、webhook 控制器 | | donate | **积分商城**、捐书与物资流转、微心愿、结对、众筹、捐款与发票、物流 |
| auth | 三套登录态、实名、PII、**处置表与闸门**、**通知短信入口**、注销冷静期 | | user | 志愿者管理、我的资料、地址、年级、志愿者证 |
| organization | 子账号与权限点、小组、分队、组织架构、管理团队、**问卷引擎**、临时负责人考试 | | data | 数据看板与汇总、**投诉建议** |
| publicity | 轮播图、公告、文件下载 | | honor | 排行榜、勋章与榜样、**电子证书**、**奖惩与申诉** |
| activity | 活动、报名、签到、时长、名单公示、相册、**积分账本** | | social / enterprise / system | 社区与私信 / 爱心企业 / 日志·水印·编号·网盘 |

常见跨模块话题：**积分**（账本在 activity、商城在 donate、企业积分在 enterprise、排行在 honor）· **证书**（activity 确认考勤 / donate 捐赠事件 → honor 出证）· **处置与禁言**（表与闸门在 auth、开单在 honor、社区禁言在 social）· **通知短信**（模板在 common、入口在 auth）· **收付**（trade；订阅方在 donate）。

## 硬规矩（完整说明见 `知识库/横切/`）

- **迁移**只放 common 的 `db/migration/`；挑号前跑跨分支最大号命令（`知识库/模块/common.md`）；跑过的不能原地改、只能加新号；一个文件只放一条有失败可能的语句；脚本连注释里也不能出现美元花括号占位符。
- **积分只认账本**：读写一律经 `PointService`，不要 `SUM(activity_attendance.points_award)`；新来源独占类型码。
- **通知短信只走 `SmsNotifyService`**，模板先在 `SmsNotifyTemplate` 登记（键 + 变量名 + 顺序），并在 `NotifyTopic` 归到一个话题。
- **分布式锁用 `DistributedLockSupport`**，锁在事务外取；「幂等键 + 唯一键」挡不住并发重放时按幂等键上锁。
- **CAS 失败之后的复核读必须是当前读**；**别对可能还不存在的行加锁或做范围 UPDATE**（间隙锁会死锁）。
- **`AFTER_COMMIT` 监听里写库用 `REQUIRES_NEW`**；高并发入口改为事务外发布 + 订阅方 `fallbackExecution = true`。
- **第三方 webhook 的应答直接写 `HttpServletResponse`**，不要返回 `String`。
- **魔法数收在各模块 `constant` 持有类**，新增状态判断复用 / 扩展它们。
- **依赖注入统一用 setter 注入**：`@Autowired` 标在手写的 setter 上（Lombok `@Setter` 不带注解）；不用字段注入、不用构造器注入。
- **测试统一 `@SpringBootTest` + Testcontainers MySQL（不要 H2）**，不写纯 Mockito 单测；每个领域模块在 `src/test/java/com/hengde/<domain>/` 放一个测试用 `@SpringBootApplication`；用到 Redisson 的模块另加 Redis 容器。分页拦截器只在 api，领域模块测试断言 `records` 不断言 `total`。
- **测试纪律**：变异验证必须 clean 构建并重装本轮动过的所有模块；赛跑类并发用例断言每种结局都出现过；api 全量跑一次才算绿（单独跑绿 ≠ 全量跑绿）。
- **Excel 依赖钉**：升 `poi.version` 时 `commons-compress` 与 `commons-io` 一起改，并跑一次真实导入导出。
- **线上接口「GET 就 500」**先查 `/actuator/info` 确认部署的是不是新包。
- **父 POM 里声明了但代码没用的别照着写**：Spring Cloud / Alibaba、Sentinel、MapStruct、XXL-Job、MinIO、FastJSON2、Guava。定时任务用 Spring `@Scheduled`。

## 构建与测试

Maven 在 `代码/hengde-volunteer-parent/` 下执行（仓库根没有 POM），自带 Maven Wrapper（`./mvnw`，Windows 用 `mvnw.cmd`）。父 POM 的 `<modules>` 聚合全部 14 个模块，顺序：common → auth → organization → publicity → activity → trade → donate → user → honor → social → enterprise → data → system → api。**全量构建后核对 Reactor Summary 列全 15 行**（含 parent）——少一个模块时 api 会悄悄打进本地仓库里的旧 jar；**新增模块时同步这里的数字与顺序**。

```bash
cd 代码/hengde-volunteer-parent
./mvnw clean install -DskipTests                                           # 全量
./mvnw clean install -DskipTests -f ../hengde-volunteer-common/pom.xml     # 单模块（下游靠本地仓库解析，被依赖的改了先 install）
./mvnw test -f ../hengde-volunteer-activity/pom.xml                        # 模块测试（需要 Docker）
./mvnw test -f ../hengde-volunteer-activity/pom.xml -Dtest=EnrollmentServiceTest
./mvnw spring-boot:run -f ../hengde-volunteer-api/pom.xml                  # 启动（需要 MySQL ≥ 8.0.17 与 Redis）
./mvnw versions:set -DnewVersion=2.0.2 -DgenerateBackupPoms=false          # 改版本号（父 POM + 14 个 <parent>）
```

- 压力测试（`@Tag("stress")`，默认排除）的命令见 `知识库/横切/测试纪律.md`。
- ⚠️ 仓库在 iCloud 同步的桌面上时 `target/` 里会冒出「X 2.class」冲突副本——构建一律带 `clean`；更稳妥的是 rsync 到临时目录（排除 `target/` 与「* 2*」）再构建测试。
- 改了 Controller：`python3 tools/verify_url_contract.py`（核对 `文档/v2~v4/url文档v*.md`），再 `javap -p` 确认方法真的在编译产物里。
- 改了知识库或代码里被知识库点名的东西：`python3 tools/verify_knowledge_base.py`；改了迁移 / 权限点 / Controller：`python3 tools/gen_knowledge_base.py`；改了本文件：`python3 tools/gen_agent_entries.py`（重新生成后端仓库里的 `AGENTS.md` / `CLAUDE.md`）。三个生成脚本都有 `--check`。

## 模块架构

父工程 `hengde-volunteer-parent`（packaging=pom，纯依赖管理）下直接挂各领域模块（扁平，没有 dao/service 中间层）；每个领域一个 jar，内部 `controller / service / dao(MyBatis-Plus Mapper) / entity / dto / vo`（手写转换）；`hengde-volunteer-api` 依赖全部领域模块、持有唯一的 `@SpringBootApplication`，是唯一可部署单元。领域间调用经对方的 service 接口（**不直接用对方的 Mapper**），避免循环依赖，公共能力下沉到 common。Flyway 迁移集中在 common，运行期配置在 api。

容易混淆的归属：首页数据看板归 **data**；投诉建议归 **data**、帖子举报归 **social**；活动**临时负责人考试**归 **organization**（activity 里的「活动负责人」是单次活动的现场负责人）；全局搜索在 api 聚合层；处置表在 **auth**（放 honor 会成环）。

**多组织部署＝白标多实例**（每个组织一套独立库 + 配置，跑同一份 api 制品）；「监管后台」是另一个独立应用，未建（`知识库/领域/12-监管后台.md`）。

## 接口 URL 约定

写 Controller 前先查 `文档/v1~v4/url文档v*.md`（按版本切；v1 冻结）与 `知识库/生成/接口/<模块>.md`。

- 路径 `/{role}/{domain}/{resource}/{action?}`，context-path `/api` 不写进代码。role：`/v` 小程序志愿者端、`/a` 管理后台、`/e` 爱心企业端。
- 资源用复数名词；动作走 HTTP 方法；动词性操作用子资源后缀（`POST /a/activity/enrollments/{id}/approve`），不造 `/doXxx`。
- 分页 `page`（从 1 起）、`size`（默认 10，最大 100）；搜索 `keyword=`。
- Jackson 行为在 api 的 `JacksonConfig`（不用 `spring.jackson.*`）：时间 `yyyy-MM-dd HH:mm:ss`、入参兼容 ISO `T`；Long 序列化为字符串。

### 路由鉴权（`SaTokenConfigure`）

| 路径 | 状态 |
|---|---|
| `/v/auth/login/wechat`、`/v/auth/login/dev`（dev 限定）、`/v/auth/login/sms`、`/v/auth/login/password`、`/v/auth/password/reset`、`/v/auth/agreement`、`/v/auth/sms/codes`、`/v/auth/wechat/group-membership`、`/v/user/volunteer-cards/verify`（只收随机令牌） | 公开 |
| `/v/**` 其余 | 需登录 + 每次查账号状态（非正常即登出 403；禁用账号只放行奖惩、申诉等小口子；游客照常放行） |
| `/a/auth/login`、`/a/auth/sms/codes`、`/a/auth/password/reset` | 公开 |
| `/a/**` 其余 | 需后台登录（`StpAdminUtil`） |
| `/e/auth/login`、`/e/auth/register`、`/e/auth/sms/codes`、`/e/auth/password/reset` | 公开 |
| `/e/**` 其余 | 需企业登录（`StpEnterpriseUtil`）+ `EnterpriseAccountGate` 按状态放行 |
| `/share/files/{token}` | 公开，靠随机令牌（要不要登录由那条分享决定） |
| `/ws/social/chat?token=` | 握手时按 token 认身份；nginx 要放行 `Upgrade` / `Connection` 头 |
| `/actuator/info` | 公开，只报构建版本、时间与迁移号 |
| `/callback/**`（微信支付、快递100 webhook） | 公开，**靠验签不靠登录态** |

⚠️ 拦截器注册在 `/**`，处理器内部只对 `/v/**` `/a/**` `/e/**` 做 check，其余路径落不到 check 上才得以放行——**往处理器里加一条全局 `match("/**")` 会让 webhook 当场 401 且毫无征兆**（`TradeCallbackApiTest` 钉住）。

## 技术栈（实际在用的）

Spring Boot 4 · Sa-Token（三套登录态，token 存 Redis）· MyBatis-Plus 3.5.16（`mybatis-plus-spring-boot4-starter`）+ HikariCP + MySQL ≥ 8.0.17（不用 Druid）· Flyway（flyway-mysql）· Redisson 4.x（父 POM 用 `${redisson.version}`；锁不指定 leaseTime）· 阿里云 OSS / 火山 TOS · 火山引擎短信 · 腾讯云 faceid 实名 · weixin-java（小程序、支付）· EasyExcel + POI · ZXing / iText · Hutool · Spring WebSocket · Knife4j（`/api/doc.html`，生产 403）· Testcontainers + JUnit 5。子模块引依赖**不写 version**。

## 角色

游客（登录未实名）→ 志愿者（实名）→ 活动临时负责人（考试通过，资格按时间现算）/ 管理团队（`manager_flag=1`）；后台子账号按权限点授权（部门：理事会、组织部、秘书部、宣传部——**监察部、分队管理部已撤销**）；爱心企业是独立账号（`/e`）。

## 未实现与冻结

监管后台（SaaS）未建 · 纸质证书申请、i志愿证书冻结 · 评优评先按 Row 47「需预留」推迟 · Row 70 仓库管理系统待协会说清 · XXL-Job 未接（要先部署调度中心）。

## 维护规则

- **每批收尾**：更新所涉模块页的「现行要点」（只写现在是什么）与「按批次的记录」（为什么、撞过什么）；业务口径变了改 `知识库/领域/`；协会有新答复追加 `知识库/决策/协会决策.md`（新号，不复用）；跨模块的批次记 `知识库/变更日志.md`；跑 `gen_knowledge_base.py` 与 `verify_knowledge_base.py`。
- 本文件只在规矩或路由变了时才改；一条事实只写在一个页里，别处放链接。
- **入口文件成对出现**：每个入口目录都有 `AGENTS.md`（内容；Codex、Cursor 等读它）与 `CLAUDE.md`（Claude Code 读它，用 `@` 导入 `AGENTS.md` 或模块页）。后端仓库里的那几对由 `gen_agent_entries.py` 生成，不手改。
- 发布知识库：`tools/publish_knowledge_base.sh`（校验 → 敏感信息扫描 → 把 `知识库/` 当前这棵树作为一个快照提交推到 Gitee 知识库仓库；不推历史，早期提交里清掉的内容不会带出去）。
