# 接口 URL 约束文档 V4

> **本文件是 V4（社区 / 爱心企业 / 个人中心补全 / 问卷与考试 / 系统治理）端点的约束清单，随各批落地逐批追加。**
> V1 → V2 的全量端点以 [`文档/v2/url文档v2.md`](../v2/url文档v2.md) 为准，V3 以 [`文档/v3/url文档v3.md`](../v3/url文档v3.md) 为准，**三份并存、互不覆盖**。
> 契约脚本 `tools/verify_url_contract.py` 同时读三份文档。
>
> 批次规划见 [`V4规划.md`](V4规划.md)。**只登记已落地批次的端点**——V3 的骨架写法（先占路径、打未实现标记）在 V4 不再沿用：
> V4 的社区 / 企业路径形态要到对应批次开工时才定得下来，先占的路径多半要改。
>
> ⚠️ **脚本能证明「注解写对了」，不能证明「端点真的注册进了 Spring」**（V2 第 5 批 javadoc 漏 `*/` 那一课），
> 每批都配一条走真实 HTTP 的 `*ApiFlowTest`。

## 设计规范（继承 V2 / V3）

| 规范项 | 值 |
|---|---|
| context-path | `/api`（Controller 代码中不写） |
| 角色前缀 | `/v` 志愿者端 / `/a` 管理后台 / `/e` 爱心企业端（爱心企业批启用）/ `/callback` webhook |
| 分页 / 搜索 | `page`（从 1）、`size`（默认 10，最大 100）、`keyword=` |

---

## 问卷引擎 —— `/v/organization/forms` · `/a/organization/forms`（问卷引擎批）

> 一个引擎服务五处（Row 43 投诉建议 / 44·46 报名管理团队 / 47 评优评先 / 48 意见反馈，考试共用题型），落 organization（V4规划 D2）。
> **场景** `scene`：1 通用问卷 / 2 报名管理团队 / 3 评优评先 / 4 意见反馈 / 5 投诉建议。志愿者能**直接**填的是 1 / 3 / 4
> （4 意见反馈自个人中心补全批起放开，但**不进问卷列表**，入口在安全中心、取 `scenes/4/current`；不传「每人一次」时默认不限次数）；
> 2 的答卷随报名管理团队申请提交，5 的答卷随投诉建议提交。
> **题型** `type`：1 单选 / 2 多选 / 3 判断 / 4 填空 / 5 简答 / 6 文件 / 7 日期。
> **答案** `value` 的形状：单选给选项编号 `"A"`、多选给编号数组 `["A","C"]`、判断给 `true/false`、填空 / 简答给文字、文件给 URL 数组（**只收 `POST /v/files/form-file` 传出来的**）、日期给 `"yyyy-MM-dd"`。

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/forms | **✅ 问卷引擎批** · 问卷列表（`?scene=&status=&keyword=`；带题数与答卷数） | 需登录（org:form 或 org:form-data） |
| POST | /a/organization/forms | **✅ 问卷引擎批** · 新建问卷（落草稿；题目整批提交，选项编号 A、B、C… 由服务端分配；报名管理团队场景不能设「每人一次」） | 需登录（org:form） |
| GET | /a/organization/forms/{id} | **✅ 问卷引擎批** · 问卷详情（含题目） | 需登录（org:form 或 org:form-data） |
| PUT | /a/organization/forms/{id} | **✅ 问卷引擎批** · 修改问卷（**仅草稿**，条件写在 UPDATE 的 WHERE 里；题目整批替换——发布后题目冻结，要改走复制） | 需登录（org:form） |
| DELETE | /a/organization/forms/{id} | **✅ 问卷引擎批** · 删除问卷（仅草稿） | 需登录（org:form） |
| POST | /a/organization/forms/{id}/publish | **✅ 问卷引擎批** · 发布（草稿 → 收集中；截止时间已过的拒绝；**除通用问卷外，一个场景同时只能有一份收集中**，撞 `uk_active_scene` 报「请先停止它」） | 需登录（org:form） |
| POST | /a/organization/forms/{id}/close | **✅ 问卷引擎批** · 停止收集（与提交串行：提交锁住问卷行再判状态，停止之后收不进答卷） | 需登录（org:form） |
| POST | /a/organization/forms/{id}/copy | **✅ 问卷引擎批** · 复制成新草稿（题目一并复制） | 需登录（org:form） |
| GET | /a/organization/forms/{id}/submissions | **✅ 问卷引擎批** · 答卷列表（逐题答案带「给人看的文字」+ 填写人姓名电话） | 需登录（org:form-data） |
| GET | /a/organization/forms/{id}/submissions/export | **✅ 问卷引擎批** · 导出答卷 xlsx（提交编号 / 姓名 / 手机号 / 提交时间 + 每道题一列；超过 20000 份报错不截断） | 需登录（org:form-data） |
| GET | /a/organization/forms/submissions/{submissionId} | **✅ 问卷引擎批** · 答卷详情 | 需登录（org:form-data） |
| GET | /a/organization/manager-applications/{id} | **✅ 问卷引擎批** · 报名管理团队申请详情（含电话与问卷答卷逐题答案） | 需登录（org:manager-flag） |
| GET | /a/organization/manager-applications/export | **✅ 问卷引擎批** · 批量下载申请 xlsx（Row 44；固定三项 + 电话 + 状态 + 问卷答卷合一列——不同时期答的可能是不同问卷；`?status=` 不传导出全部） | 需登录（org:manager-flag） |

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/organization/forms | **✅ 问卷引擎批** · 可以填写的问卷（通用 / 评优评先、收集中且在时间窗内；带 `submitted`） | 需登录 |
| GET | /v/organization/forms/scenes/{scene}/current | **✅ 问卷引擎批** · 某个场景当前的问卷（报名管理团队页面先取它渲染；没有时 `data` 为空；`scene=1` 报错） | 需登录 |
| GET | /v/organization/forms/{id} | **✅ 问卷引擎批** · 问卷详情（含题目；已停止 / 草稿对志愿者等于不存在） | 需登录 |
| POST | /v/organization/forms/{id}/submissions | **✅ 问卷引擎批** · 提交答卷（body `answers`；须收集中、在时间窗内、按问卷设置须已实名；「每人一次」的重复提交报「你已经提交过」；**报名管理团队的问卷不走这里**） | 需登录 |
| GET | /v/organization/forms/{id}/submissions/mine | **✅ 问卷引擎批** · 我在这份问卷上的答卷 | 需登录 |
| POST | /v/files/form-file | **✅ 问卷引擎批** · 上传问卷附件（multipart `file`，图片与常见文档，扩展名白名单同后台「文件下载」），返回 `{url,name,size}`；`url` 随答卷提交，提交时服务端核对它确实是传到 `form/` 下的对象 | 需登录 |

> **报名管理团队接入**：`POST /v/organization/manager-applications` 的 body 新增可选 `answers`（同上格式）。
> 协会在后台为场景 2 发布了问卷时答卷必填、与申请**同一事务**落库（申请被拒不留答卷）；没有问卷时不传，V23 的固定三项照旧；
> 问卷已停止而请求里带着答案时报「问卷已停止收集，请刷新后重新填写」——不默默丢掉他填的东西。

---

## 投诉建议 —— `/v/data/complaints` · `/a/data/complaints`（投诉建议批）

> Row 43「类似于问卷收集 / 投诉默认到监察部，后续监察部可根据工作需要选择流转到其他部门，含处理进度」。
> **工单在谁手上谁看**：只持 `data:complaint` 的账号只看得到、只动得了当前在**本部门**（`admin_user.department`）的工单；
> 持 `data:complaint-all` 的看全部、可代任何部门处理。部门是后台账号上的那段文字，流转只能转给**当前有启用账号**的部门。
> 状态：0 待受理 / 1 处理中 / 2 已办结；流转后回到待受理。进度动作：1 提交 / 2 受理 / 3 流转 / 4 答复办结 / 5 内部备注。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /v/data/complaints | **✅ 投诉建议批** · 提交（body `type` 1投诉/2建议、`content` ≤2000、可选 `images` ≤6 张（先经 `POST /v/files/form-file`，只收本系统上传的）、可选 `answers`（协会为场景 5 发布了问卷时必填，先调 `GET /v/organization/forms/scenes/5/current`））；进默认部门（监察部）；**24 小时内条数有上限**（默认 5）；游客也能提，禁用 / 注销的账号不能 | 需登录 |
| GET | /v/data/complaints/mine | **✅ 投诉建议批** · 我的投诉建议 | 需登录 |
| GET | /v/data/complaints/{id} | **✅ 投诉建议批** · 详情（含处理进度——**只含对志愿者可见的几步**，流转理由与内部备注不下发；答复；问卷答卷）；别人的与不存在同一句话 | 需登录 |

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/data/complaints | **✅ 投诉建议批** · 工单列表（`?status=&type=&department=&keyword=`，keyword 试编号精确 / 内容模糊；**只有 `data:complaint` 时 `department` 无效、恒为本部门**，没填部门的账号列表为空） | 需登录（data:complaint 或 data:complaint-all） |
| GET | /a/data/complaints/departments | **✅ 投诉建议批** · 可流转的部门（当前有启用后台账号的部门，字典序） | 需登录（data:complaint 或 data:complaint-all） |
| GET | /a/data/complaints/{id} | **✅ 投诉建议批** · 详情（全部进度含流转理由与内部备注及操作人、提交人姓名电话、问卷答卷）；范围外的与不存在同一句话 | 需登录（data:complaint 或 data:complaint-all） |
| POST | /a/data/complaints/{id}/accept | **✅ 投诉建议批** · 受理（待受理 → 处理中） | 需登录（data:complaint 或 data:complaint-all） |
| POST | /a/data/complaints/{id}/transfer | **✅ 投诉建议批** · 流转（body `department` 必填、`reason` 可选 ≤500 仅后台可见；回到待受理、清掉受理人；已办结的不能流转） | 需登录（data:complaint 或 data:complaint-all） |
| POST | /a/data/complaints/{id}/reply | **✅ 投诉建议批** · 答复并办结（body `content` ≤2000）；同一事务写站内提示，事务提交后发短信 `COMPLAINT_REPLIED`（答复只放开头，默认 30 字） | 需登录（data:complaint 或 data:complaint-all） |
| POST | /a/data/complaints/{id}/notes | **✅ 投诉建议批** · 内部备注（仅后台可见；已办结的也能补记） | 需登录（data:complaint 或 data:complaint-all） |

> ⚠️ **状态迁移全是 CAS，条件里带「读到时的部门」**：受理 / 流转 / 答复并发时只有一个成功；输家的报错在事务结束后重新读一次，
> 说明「刚被处理成了什么、在哪个部门」（`工单刚刚被处理过（当前：已办结，在宣传部），请刷新后再操作`）。

---

## 个人中心补全 —— `/v/user/*` · `/a/user/center-contents`（个人中心补全批）

> Row 40 地址管理、Row 42 我的保险、Row 48 安全中心（联系客服 / 意见反馈 / 手写签名板 / 订阅通知）、Row 25 年级每年 9 月升一级。
> 意见反馈用问卷引擎的场景 4（见上方问卷一节），这里不另开端点。年级升级是定时任务，没有端点——资料里多了 `gradePromptPending`。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/user/addresses | **✅ 个人中心补全批** · 我的地址（置顶的在最前，其余按最后修改倒序；电话明文） | 需登录 |
| POST | /v/user/addresses | **✅ 个人中心补全批** · 新增地址（body `recvName` / `recvPhone` 手机号 / `region` 省市区 / `detail`；**最多 20 个**，按人上锁再数） | 需登录 |
| PUT | /v/user/addresses/{id} | **✅ 个人中心补全批** · 修改地址（只能改本人的；别人的与不存在同一句话） | 需登录 |
| DELETE | /v/user/addresses/{id} | **✅ 个人中心补全批** · 删除地址 | 需登录 |
| POST | /v/user/addresses/{id}/top | **✅ 个人中心补全批** · 置顶（**一人至多一条**：置顶新的会取消旧的；按人上锁 + 生成列唯一键兜底；已是置顶再点不报错） | 需登录 |
| DELETE | /v/user/addresses/{id}/top | **✅ 个人中心补全批** · 取消置顶 | 需登录 |
| GET | /v/user/center-contents/{key} | **✅ 个人中心补全批** · 我的保险（`key=insurance`）/ 联系客服（`key=customer-service`）：标题 + 正文 + 图片；协会还没设置时 `data` 为空；别的 key 报错 | 需登录 |
| GET | /v/user/notify-preferences | **✅ 个人中心补全批** · 我的订阅：全部话题 `topic` / `label` / `optional`（能不能关）/ `smsEnabled`；默认全开 | 需登录 |
| PUT | /v/user/notify-preferences | **✅ 个人中心补全批** · 打开 / 关闭一个话题的短信提醒（body `topic` + `smsEnabled`）；**奖惩与违规、活动取消等不可关闭**（拒绝）；只影响短信，站内提示照常留存；过滤统一在 `SmsNotifyService` 做 | 需登录 |
| PUT | /v/user/pad-signature | **✅ 个人中心补全批** · 设置手写签名板（body `url`，先经 `POST /v/files/profile-image?dir=signature` 上传，只收本系统传的）；**与注册协议签名分开**，不覆盖协议留痕 | 需登录 |
| DELETE | /v/user/pad-signature | **✅ 个人中心补全批** · 清除手写签名板 | 需登录 |

> `GET /v/user/profile` 自本批起多两个字段：`gradePromptPending`（读完六年级 / 九年级 / 高三 / 大三 / 大四 / 大五后的 9 月为 true，提示他改学校和年级；
> `PATCH /v/user/profile` 带上 `grade` 即清除）、`padSignatureUrl`。

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/user/center-contents/{key} | **✅ 个人中心补全批** · 读个人中心内容 | 需登录（user:center-content） |
| PUT | /a/user/center-contents/{key} | **✅ 个人中心补全批** · 设置个人中心内容（**整份替换**：没传的图片即清掉；标题 / 正文 / 图片至少一项；图片最多 6 张、先经 `POST /a/files/upload?dir=center`，只收本系统传的） | 需登录（user:center-content） |

> 后台通用上传 `POST /a/files/upload` 新增目录 `center`（需 `user:center-content`，仅图片）。

---

## 组织架构维护 —— `/a/organization/structure`（组织架构维护批）

> Row 6「由不同的小方框组成，插入志愿者之后显示他是哪个部门的什么职位，框架新增、减少，均有最高权限操作」；Row 5 F「输入职位信息，前端名字下面展示」。
> 节点沿用 V5 的 `organization_structure_node`，人放在 V69 的 `organization_structure_member`。**所有写操作串行**（全局锁 `lock:org-structure`）。
> **一个人在架构里只有一个位置**（生成列唯一键）；名字下面那一行「部门 · 职位」由架构位置现算，`volunteer.position` 那一列不再读写。

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/structure | **✅ 组织架构维护批** · 架构树（每个节点带 `members`：`memberId` / `volunteerId` / `name` / `position` / `phone` / `sort`） | 需登录（org:structure） |
| POST | /a/organization/structure/nodes | **✅ 组织架构维护批** · 新增节点（body `parentId` 必填——架构只有一个根 / `name` / `title` / `sort`；最多 10 层） | 需登录（org:structure） |
| PUT | /a/organization/structure/nodes/{id} | **✅ 组织架构维护批** · 修改节点（名称 / 说明 / 排序 / 上级；**不能挂到自己或自己的下级下面**，挪动后整棵子树仍不超过 10 层；根节点不能换上级；`title` 不传即清空、`sort` 不传即保留） | 需登录（org:structure） |
| DELETE | /a/organization/structure/nodes/{id} | **✅ 组织架构维护批** · 删除节点（根不能删；**下面还有节点或人的不能删**——先挪走，不连带删） | 需登录（org:structure） |
| POST | /a/organization/structure/nodes/{id}/members | **✅ 组织架构维护批** · 把志愿者放进节点（body `volunteerId` / `position` / `sort`；只收已实名且账号正常的；已在架构里的报「请用修改把他挪过去」） | 需登录（org:structure） |
| PUT | /a/organization/structure/members/{memberId} | **✅ 组织架构维护批** · 改职位 / 排序 / 挪到另一个节点（body `nodeId` 不传＝不挪 / `position` / `sort`） | 需登录（org:structure） |
| DELETE | /a/organization/structure/members/{memberId} | **✅ 组织架构维护批** · 把人移出架构 | 需登录（org:structure） |

> 志愿者端 `GET /v/organization/structure`（V1 起就有，见 v2 文档）自本批起节点带 `members`；**电话只下发给已实名的查看者**——游客收个验证码就能登录，给游客下发等于把部门成员的电话公开。
> `GET /v/user/profile` 与 `GET /a/user/volunteers`（列表 / 详情）的 `position` 自本批起是「部门 · 职位」，不在架构里的人没有这个字段。

---

## 活动补全 —— `/v/activity/rosters` · `/a/activity/activities/{id}/roster`（活动补全批）

> Row 13「志愿者报完活动，由组织部的同学审核通过，会在这里用一个固定模板展现，普通志愿者电话中间打*号，活动负责人则全显示，
> 活动如果有多个时间段的则按时间段显示……活动开始后该公示就自动消失」+ Row 13 F「公示显示时间为组织部确认名单到活动开始」；
> 以及 V1.1 起搁置的「指定分队报名」（单个分队）。迁移 V70；**不新增权限点**。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/rosters | **✅ 活动补全批** · 正在公示的名单（组织部确认名单后、活动开始前；按活动开始时间）。每场带 `leaders`（负责人，不占名额）与按时间段分组的 `slots[].members`（只列**已通过**的报名，管理团队在前、其余按报名先后；`name` / `phone` / `leader` / `manager`） | 需登录 |
| GET | /v/activity/rosters/{activityId} | **✅ 活动补全批** · 某场正在公示的名单；没确认 / 已撤回 / 负责人已点开始 / 已到开始时间 / 已取消一律报「没有正在公示的名单」 | 需登录 |

> **电话**：普通成员中间打 *（`135****0002`），负责人全显示——**但查看的人是游客（未实名）时负责人也打 ***（游客收个验证码就能登录）。
> 名单是实时的，确认之后再审过 / 删掉的人即时反映，不是确认那一刻的快照。

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/activities/{id}/roster | **✅ 活动补全批** · 预览名单（不看公示窗口，电话全显示） | 需登录（activity:enroll-view） |
| POST | /a/activity/activities/{id}/roster/publish | **✅ 活动补全批** · 确认名单、开始公示（只有已发布、还没开始的普通活动；已公示的再点不报错、不刷新公示时间） | 需登录（activity:enroll-audit） |
| DELETE | /a/activity/activities/{id}/roster/publish | **✅ 活动补全批** · 撤回公示（没公示过的再点不报错） | 需登录（activity:enroll-audit） |

> **指定分队报名**：`POST /a/activity/activities`（及修改、周期发布、志愿者端提交发布）的 `enrollScope=1` 自本批起可用，配 **`targetSquadId`**（单个、须是启用中的分队；
> `enrollScope=0` 时填了分队报错而不是静默丢掉）。V3 起 DTO 里那个从没生效的 `targetSquadIds`（逗号分隔）**已移除**。
> 自助报名与同组代报名只收**报名那一刻归属该分队**的人（「本活动仅限「X」的成员报名」）；后台补录报名照旧越权。
> 活动详情（`GET /v/activity/activities/{id}`、`GET /a/activity/activities/{id}`）多 `targetSquadId` / `targetSquadName`；后台详情另有 `rosterPublishTime`。改回全平台时分队真的清掉；复制活动带着分队、不带公示。

---

## 社区核心 —— `/v/social/**` · `/a/social/**`（社区核心批）

> Row 23 C/D：最热 / 关注 / 官方 / 发布（文字 · 图片 · 视频）/ 搜索 / 帖子（查看 · 点赞 · 评论 · 分享）/ 帖子数据 / TA的主页 / 自己主页 / 帖子与主页设置。
> 新模块 `hengde-volunteer-social`，迁移 V71。审核 / 关键词风控 / 举报 / 禁言入口 / 置顶 / 互动通知聚合属**社区治理批**，私信属**私信批**。
>
> **四层门**（V4规划 D1）：① 登录态 ② **看帖要已验手机号**（`phone_hash` 非空，微信登录没绑手机的挡在外面）③ **发帖 / 评论 / 点赞 / 关注要已实名** ④ 处置闸门——
> 发帖（含修改）挡「禁止发帖」、评论挡「禁止评论」、点赞挡「禁止点赞」，**「限制发布社区」蕴含这三个、「拒绝使用本程序」蕴含一切**（`SanctionScope` 4/5/6）。
> 志愿者只露**昵称与头像**，不露真实姓名与学校；官方帖 / 官方评论露「官方 · 部门」。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/social/posts | **✅ 社区核心批** · 帖子流：`tab=latest` 最新（默认）/ `hot` 最热（本小时 + 上一小时按剩余比例折算，Redis 不可用时退回按时间）/ `following` 我关注的人 / `official` 官方；`keyword=` 搜正文与官方名称（按字面，% 不是通配）；只列看得到的 | 需登录（+已验手机号） |
| GET | /v/social/posts/{id} | **✅ 社区核心批** · 帖子详情（查看量 +1，按打开次数计）；看不到的与不存在同一句话 | 需登录（+已验手机号） |
| POST | /v/social/posts | **✅ 社区核心批** · 发帖（body `content` ≤2000 / `imageUrls` ≤9 张只收 `social/` 下本系统上传的 / `videoUrl` 1 个只收 `social-video/` 下的，二选一 / `visibility` 0 不限制 · 1 隐藏 · 2 我的关注可看 · 3 关注我的可看 / `allowComment` / `allowLike`）；先发后审，落「待审核」不影响显示 | 需登录（+已实名，禁止发帖闸门） |
| PUT | /v/social/posts/{id} | **✅ 社区核心批** · 修改自己的帖子（同上整份提交；改完重回待审核） | 需登录（+已实名，禁止发帖闸门） |
| DELETE | /v/social/posts/{id} | **✅ 社区核心批** · 删除自己的帖子 | 需登录 |
| POST | /v/social/posts/{id}/share | **✅ 社区核心批** · 分享（分享量 +1） | 需登录（+已验手机号） |
| POST | /v/social/posts/{id}/like | **✅ 社区核心批** · 点赞（已赞过 `data=false` 不报错；帖子禁止点赞或作者主页禁止点赞则拒绝） | 需登录（+已实名，禁止点赞闸门） |
| DELETE | /v/social/posts/{id}/like | **✅ 社区核心批** · 取消点赞（不挂闸门） | 需登录 |
| GET | /v/social/posts/{id}/comments | **✅ 社区核心批** · 评论（按时间先后；`deletable` 表示我能删） | 需登录（+已验手机号） |
| POST | /v/social/posts/{id}/comments | **✅ 社区核心批** · 评论 / 回复（body `content` ≤500 / `parentId` 回复同一帖下的评论）；帖子禁止评论或作者主页禁止评论则拒绝 | 需登录（+已实名，禁止评论闸门） |
| DELETE | /v/social/comments/{commentId} | **✅ 社区核心批** · 删评论：自己的评论，或自己帖子下的评论 | 需登录 |
| GET | /v/social/users/{id} | **✅ 社区核心批** · 主页：昵称 / 头像 / 已获得的勋章 / 注册时间 / 备注 / 发帖量（我看得到的）/ 粉丝量 / 关注量 / 收到的点赞量 / 我关注了 TA / TA 关注了我 / TA 禁止关注 / 我是否不让 TA 看 | 需登录（+已验手机号） |
| GET | /v/social/users/{id}/posts | **✅ 社区核心批** · TA 的帖子（只列我看得到的） | 需登录（+已验手机号） |
| GET | /v/social/users/{id}/comments | **✅ 社区核心批** · TA 的评论（只列我看得到的帖子下的） | 需登录（+已验手机号） |
| GET | /v/social/users/{id}/followers | **✅ 社区核心批** · 粉丝 | 需登录（+已验手机号） |
| GET | /v/social/users/{id}/following | **✅ 社区核心批** · 关注的人 | 需登录（+已验手机号） |
| POST | /v/social/users/{id}/follow | **✅ 社区核心批** · 关注（已关注 `data=false`；不能关注自己；TA 禁止关注则拒绝） | 需登录（+已实名） |
| DELETE | /v/social/users/{id}/follow | **✅ 社区核心批** · 取消关注 | 需登录 |
| GET | /v/social/settings | **✅ 社区核心批** · 我的主页设置 | 需登录 |
| PUT | /v/social/settings | **✅ 社区核心批** · 保存主页设置（`forbidFollow` / `forbidComment` / `forbidLike` / `bio` ≤200，整份提交，没传的开关按关） | 需登录（+已实名） |
| GET | /v/social/blocks | **✅ 社区核心批** · 我设置了「不让TA看」的人 | 需登录 |
| POST | /v/social/blocks/{userId} | **✅ 社区核心批** · 不让 TA 看（TA 看不到我的任何帖子，也就评不了、赞不了） | 需登录（+已实名） |
| DELETE | /v/social/blocks/{userId} | **✅ 社区核心批** · 取消「不让TA看」 | 需登录 |
| POST | /v/files/social-image | **✅ 社区核心批** · 上传帖子图片（multipart `file`，限图片） | 需登录（+已实名） |
| POST | /v/files/social-video/presign | **✅ 社区核心批** · 帖子视频直传签名（`?extension=mp4\|mov&size=字节数`，上限 100MB）：返回 `uploadUrl` / `method` / `headers`（全部原样带上）/ `url`（发帖时作为 `videoUrl`）。⚠️ 真实对象存储上的直传未端到端验证 | 需登录（+已实名） |

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/social/official-posts | **✅ 社区核心批** · 官方帖列表（`?department=` 按部门筛） | 需登录（social:official） |
| POST | /a/social/official-posts | **✅ 社区核心批** · 发布官方帖（以本账号的部门发布，账号没填部门拒绝；body `content` / `imageUrls`（先经 `POST /a/files/upload?dir=social`）/ `videoUrl` / `label` 前端显示的报名或分队名称） | 需登录（social:official） |
| DELETE | /a/social/official-posts/{id} | **✅ 社区核心批** · 删除官方帖：本部门发的；持有 `social:official-all` 可删任何部门的 | 需登录（social:official） |
| GET | /a/social/official-posts/{id}/comments | **✅ 社区核心批** · 官方帖下的评论 | 需登录（social:official） |
| POST | /a/social/official-posts/{id}/comments | **✅ 社区核心批** · 以官方身份评论 / 回复（只能在官方帖下） | 需登录（social:official） |
| DELETE | /a/social/comments/{commentId} | **✅ 社区核心批** · 删除评论：持 `social:post-manage`（社区治理批）可删任意评论；否则只能删本部门官方帖下的（持有 `social:official-all` 不限部门） | 需登录（social:official 或 social:post-manage） |
| POST | /a/files/social-video/presign | **✅ 社区核心批** · 官方帖视频直传签名（同志愿者端） | 需登录（social:official） |

> 后台通用上传 `POST /a/files/upload` 新增目录 `social`（需 `social:official`，仅图片）。

---

## 社区治理 —— `/a/social/**` · `/v/social/interactions|reports`（社区治理批）

> Row 23 F：管理 / 禁言 / 隐藏 / 删除 / 置顶、两个按时间排序的界面（帖子、评论）、最高权限才看真实姓名与学校、多级审核与审核员、关键词风控；
> Row 23 C「互动」与「举报」；Row 23 D「点赞、评论等通知每隔 20 分钟汇总提示一次」；Row 59 待办「待审核帖子 / 举报待审核」（两个列表 `size=1` 取 total）。迁移 V72 / V73。
>
> **帖子对他人可见还要满足**：没被后台隐藏、没被驳回、**命中关键词的审完之前不显示**（先藏后审，D7 / Q1）；没命中的先发后审照常显示。作者自己始终看得到，帖子出参多 `reviewStatusLabel` / `hiddenByAdmin` / `awaitingKeywordReview`（仅自己的帖子）与 `pinned`。
> 最新与官方页签里**置顶的排在最前**。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/social/interactions | **✅ 社区治理批** · 我的互动（谁赞了我的帖子 / 评论了我的帖子 / 回复了我的评论 / 关注了我，新的在前；带帖子开头与评论内容，帖子看不到或已删时为空）；自己对自己不记，点赞与关注按「谁对谁的哪条」去重 | 需登录 |
| GET | /v/social/interactions/unread-count | **✅ 社区治理批** · 未读互动数 | 需登录 |
| POST | /v/social/interactions/read | **✅ 社区治理批** · 全部标为已读 | 需登录 |
| DELETE | /v/social/interactions/{id} | **✅ 社区治理批** · 删除一条互动（长按删除；只能删自己收到的） | 需登录 |
| POST | /v/social/reports | **✅ 社区治理批** · 举报帖子或评论（body `targetType` 1 帖子 / 2 评论、`targetId`、`reason` ≤200；要看得到那条帖子；不能举报自己的；**同一对象处理完之前只能举报一次**） | 需登录（+已实名） |

> 每 20 分钟一次的**汇总提示**是定时任务（`hengde.social.digest-cron`），落站内提示（`volunteer_notification` 类型 6，`GET /v/auth/notifications`）；微信订阅消息要协会报备模板（Q13）。同一段互动只提示一次。
> 帖子被驳回时作者收到站内提示（类型 7，关联帖子）。社区禁言出现在 `GET /v/honor/sanctions`（能力域 2 / 4 / 5 / 6）。

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/social/posts | **✅ 社区治理批** · 帖子列表（按发布时间；`?reviewStatus=&keywordHit=&hidden=&authorId=&keyword=`；带审核状态与级数、命中的关键词、隐藏 / 置顶、四个计数；**持 `social:real-name` 才带真实姓名与学校**） | 需登录（social:post-manage） |
| GET | /a/social/comments | **✅ 社区治理批** · 评论列表（按时间；`?postId=&authorId=&keyword=`；真实姓名同上） | 需登录（social:post-manage） |
| POST | /a/social/posts/{id}/hide | **✅ 社区治理批** · 隐藏帖子（除作者外谁都看不到，可恢复） | 需登录（social:post-manage） |
| DELETE | /a/social/posts/{id}/hide | **✅ 社区治理批** · 取消隐藏 | 需登录（social:post-manage） |
| POST | /a/social/posts/{id}/pin | **✅ 社区治理批** · 置顶（最新与官方页签排在最前，后置顶的在前） | 需登录（social:post-manage） |
| DELETE | /a/social/posts/{id}/pin | **✅ 社区治理批** · 取消置顶 | 需登录（social:post-manage） |
| DELETE | /a/social/posts/{id} | **✅ 社区治理批** · 删除任意帖子 | 需登录（social:post-manage） |
| GET | /a/social/reviews | **✅ 社区治理批** · 待我审核的帖子：只列我这一级能审的（超管看全部级），**命中关键词的插队在前**，其余先发先审 | 需登录（social:review） |
| POST | /a/social/reviews/{postId}/approve | **✅ 社区治理批** · 通过这一级（须是这一级的审核员或超管；**同一个人不能审同一条帖子的两级**；两个人同时点只成一个）；过完最后一级即「已通过」 | 需登录（social:review） |
| POST | /a/social/reviews/{postId}/reject | **✅ 社区治理批** · 驳回（body `reason`；对他人隐藏，站内提示作者） | 需登录（social:review） |
| GET | /a/social/review-setting | **✅ 社区治理批** · 审核级数 | 需登录（social:review-setting 或 social:review） |
| PUT | /a/social/review-setting | **✅ 社区治理批** · 修改审核级数（body `levels` 1~3，Q3；调低之后已经过够新级数的待审帖直接算通过） | 需登录（social:review-setting） |
| GET | /a/social/reviewers | **✅ 社区治理批** · 审核员列表 | 需登录（social:review-setting） |
| POST | /a/social/reviewers | **✅ 社区治理批** · 设置审核员（body `adminUserId` / `level`；该账号还须另外授予 `social:review`） | 需登录（social:review-setting） |
| DELETE | /a/social/reviewers/{id} | **✅ 社区治理批** · 移除审核员 | 需登录（social:review-setting） |
| GET | /a/social/keywords | **✅ 社区治理批** · 风控关键词 | 需登录（social:review-setting） |
| POST | /a/social/keywords | **✅ 社区治理批** · 添加关键词（body `word` ≤50；此后发帖 / 改帖正文不区分大小写地包含即命中） | 需登录（social:review-setting） |
| DELETE | /a/social/keywords/{id} | **✅ 社区治理批** · 删除关键词（已命中的帖子照常等审核） | 需登录（social:review-setting） |
| GET | /a/social/reports | **✅ 社区治理批** · 举报列表（`?status=` 0 待处理，先举报的在前 / 1 成立 / 2 不成立） | 需登录（social:report） |
| POST | /a/social/reports/{id}/uphold | **✅ 社区治理批** · 举报成立：**同一对象上全部待处理的举报一起结案**，body `action` 0 不处置 / 1 隐藏（仅帖子）/ 2 删除、`note` | 需登录（social:report） |
| POST | /a/social/reports/{id}/dismiss | **✅ 社区治理批** · 举报不成立（同一对象上全部待处理的一起结案） | 需登录（social:report） |
| GET | /a/social/bans | **✅ 社区治理批** · 禁言记录（`?volunteerId=`；带到期时间与是否还在生效） | 需登录（social:ban） |
| POST | /a/social/bans | **✅ 社区治理批** · 禁言（Q2：即时生效、不走奖惩审核；body `volunteerId` / `scope` 2 全部社区写入 · 4 禁止发帖 · 5 禁止评论 · 6 禁止点赞 / `days` 必填 / `reason`） | 需登录（social:ban） |
| POST | /a/social/bans/{id}/lift | **✅ 社区治理批** · 提前解除（body `reason`） | 需登录（social:ban） |

---

## 活动相册 —— `/v/activity/albums` · `/a/activity/albums`（活动相册批）

> Row 11：每次活动自动建相册（标题「编号 活动名称」）、上传记录（谁传的、几张）、原图、全部预览、上传给积分（审核才发放）；D 列七条权限；F 列管理 / 搜索 / 新增 / 删除；
> Row 30：到家后上传照片与评论，默认勾选发送到交流平台。迁移 V74。积分来源 `ALBUM=7`（非消费，计入累计获得与排行榜，D10）。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/albums | **✅ 活动相册批** · 相册列表（`?keyword=` 搜标题；带张数、封面＝最新一张、最后上传时间、`uploadable` 我能不能传） | 需登录（+已实名） |
| GET | /v/activity/activities/{activityId}/album | **✅ 活动相册批** · 某个活动的相册（**没有就自动建**；草稿 / 审核中 / 已取消的活动不建） | 需登录（+已实名） |
| GET | /v/activity/albums/{id} | **✅ 活动相册批** · 相册详情 | 需登录（+已实名） |
| GET | /v/activity/albums/{id}/photos | **✅ 活动相册批** · 全部照片（新的在前，带上传人；单张下载是前端动作） | 需登录（+已实名） |
| GET | /v/activity/albums/{id}/batches | **✅ 活动相册批** · 上传记录（谁传的、传了多少张、还剩几张、前 9 张预览、积分审核状态） | 需登录（+已实名） |
| POST | /v/activity/albums/{id}/photos | **✅ 活动相册批** · 上传一批（body `photoUrls` 1~50 张，先经 `POST /v/files/album-photo` 上传的原图 / `comment` ≤500 / `syncSocial` 默认 true）：**这个活动报名已通过的志愿者与活动负责人**能传，**管理团队**能往任何相册传；规则开着时这一批进积分审核；勾了同步就以本人名义发一条帖子（「【相册标题】评论」+ 前 9 张，照常过禁止发帖闸门与关键词风控；失败不影响上传） | 需登录（+已实名） |
| POST | /v/files/album-photo | **✅ 活动相册批** · 上传相册原图（multipart `file`，限图片） | 需登录（+已实名） |

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/albums | **✅ 活动相册批** · 相册列表（`?keyword=`） | 需登录（activity:album 或 activity:album-delete 或 activity:album-photo-delete 或 activity:album-download 或 activity:album-points-audit） |
| POST | /a/activity/albums | **✅ 活动相册批** · 新增相册（body `activityId` 给了就是那个活动的相册、已有直接返回；否则 `title` 必填、不挂活动） | 需登录（activity:album） |
| DELETE | /a/activity/albums/{id} | **✅ 活动相册批** · 删除相册（活动相册删了之后再打开会重新建一个空的） | 需登录（activity:album-delete） |
| GET | /a/activity/albums/{id}/photos | **✅ 活动相册批** · 相册照片 | 需登录（activity:album 或 activity:album-delete 或 activity:album-photo-delete 或 activity:album-download 或 activity:album-points-audit） |
| GET | /a/activity/albums/{id}/batches | **✅ 活动相册批** · 上传记录 | 需登录（activity:album 或 activity:album-points-audit） |
| POST | /a/activity/albums/{id}/photos | **✅ 活动相册批** · 后台上传（不参与积分、不同步社区；照片先经 `POST /a/files/upload?dir=album`） | 需登录（activity:album） |
| DELETE | /a/activity/album-photos/{photoId} | **✅ 活动相册批** · 删除照片 | 需登录（activity:album-photo-delete） |
| GET | /a/activity/albums/{id}/download | **✅ 活动相册批** · 批量下载：全部没删照片的 `url` 与建议文件名 `filename`（打包是前端动作） | 需登录（activity:album-download） |
| GET | /a/activity/album-batches | **✅ 活动相册批** · 上传积分审核队列（`?status=` 0 待审核（默认，先传的在前）/ 1 已通过 / 2 已驳回） | 需登录（activity:album-points-audit） |
| POST | /a/activity/album-batches/{id}/approve | **✅ 活动相册批** · 通过：按这一批**现在还没删的张数**与规则算分，**同一个人在同一相册累计不超过上限**，0 分不入账；返回实际发了几分 | 需登录（activity:album-points-audit） |
| POST | /a/activity/album-batches/{id}/reject | **✅ 活动相册批** · 驳回（body `reason`） | 需登录（activity:album-points-audit） |
| GET | /a/activity/album-rule | **✅ 活动相册批** · 上传积分规则 | 需登录（activity:album 或 activity:album-points-audit） |
| PUT | /a/activity/album-rule | **✅ 活动相册批** · 修改规则（body `enabled` / `photosPerUnit` 1~100 / `pointsPerUnit` 1~100 / `maxPointsPerAlbum` 0~1000；Q10 默认每 3 张 1 分、每人每相册 10 分；只影响之后审核的批次，关掉之后新上传的不进审核） | 需登录（activity:album） |

> 后台通用上传 `POST /a/files/upload` 新增目录 `album`（需 `activity:album`，仅图片）。

---

## 活动临时负责人考试 —— `/v/organization/exams` · `/a/organization/exam-papers|exam-attempts|temp-leaders`（临时负责人考试批）

> Row 14 / Row 45：「跟考试一样，考试分数达到多少，即可获得这个资格；评价过低由组织部审核后取消，也可以直接取消；有历史考试板块」；D 列五种题型（单选 / 多选 / 判断 / 填空 / 简答）；
> Row 45 F「填空题等主观题需要人工审核」；Row 14 F「管理活动临时负责人，管理考试试题，信息批量导出」；Row 24「所属职务」。迁移 V75。
> 题型与答案格式与问卷共用（D3，`FormAnswerValidator`），表不共用（`org_exam_*`）。资格是一张表、按时间现算（D4）。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/organization/exams/current | **✅ 临时负责人考试批** · 我的考试：当前开放的试卷（题目、分值、及格线；**能考时才带题目，从不带标准答案**）+ `tempLeader` 我现在是不是临时负责人 + `qualificationExpireTime` + `hasPendingAttempt` + `canTake` / `reason`（没实名 / 已经是 / 有答卷在阅 / 没有开放的考试） | 需登录 |
| POST | /v/organization/exams/attempts | **✅ 临时负责人考试批** · 交卷（body `paperId` + `answers`，格式同问卷答卷；没答的题按 0 分）。单选 / 多选 / 判断当场判分（**多选全对才得分**）；**没有主观题的当场出分，及格即获得资格**；有主观题的落「待阅卷」，一个人同时至多一份。须已实名、账号正常、现在不是临时负责人 | 需登录（+已实名） |
| GET | /v/organization/exams/attempts | **✅ 临时负责人考试批** · 我的考试历史（Row 45「历史考试板块」：试卷、交卷时间、状态、客观 / 主观 / 总分、是否及格） | 需登录 |
| GET | /v/organization/exams/attempts/{id} | **✅ 临时负责人考试批** · 我的一份答卷：自己的逐题作答与总分，**不含标准答案与逐题得分**（Q34：同一份试卷可以重考）；不是本人的与不存在的同一句话 | 需登录 |

> 「我的」`GET /v/user/profile` 新增 `duty`（Row 24：「活动临时负责人」/「志愿者」/「游客」）、`tempLeader`、`tempLeaderExpireTime`，资格到期或被撤销即时变回「志愿者」。

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/exam-papers | **✅ 临时负责人考试批** · 试卷列表（`?status=` 0 草稿 / 1 开放中 / 2 已停止，`?keyword=`；带题数与答卷数） | 需登录（org:exam 或 org:exam-grade） |
| POST | /a/organization/exam-papers | **✅ 临时负责人考试批** · 新建试卷（落草稿）：`title` / `description` / `passScore`（1~满分）/ `qualificationMonths`（1~120，不填＝长期有效）/ `questions[]`（`type` 1~5、`title`、`options`（单选 / 多选 2~50 个，编号服务端分配）、`maxLength`、`score` 1~100、`answer`：单选 `"B"` / 多选 `["A","C"]`（按选项顺序规范化）/ 判断 `true` 三者必填，填空 / 简答为选填的参考答案）；满分＝各题分值之和 | 需登录（org:exam） |
| GET | /a/organization/exam-papers/{id} | **✅ 临时负责人考试批** · 试卷详情（含分值、标准答案 `answer` 与 `answerDisplay`） | 需登录（org:exam 或 org:exam-grade） |
| PUT | /a/organization/exam-papers/{id} | **✅ 临时负责人考试批** · 修改（**仅草稿**，题目整批替换；开放过的要改走复制） | 需登录（org:exam） |
| DELETE | /a/organization/exam-papers/{id} | **✅ 临时负责人考试批** · 删除（仅草稿） | 需登录（org:exam） |
| POST | /a/organization/exam-papers/{id}/publish | **✅ 临时负责人考试批** · 开放（**同一时刻只能有一份开放中的试卷**，已有时报错请先停止） | 需登录（org:exam） |
| POST | /a/organization/exam-papers/{id}/close | **✅ 临时负责人考试批** · 停止（已交的答卷照常阅卷；与交卷串行，停止之后交进来的一律拒绝） | 需登录（org:exam） |
| POST | /a/organization/exam-papers/{id}/copy | **✅ 临时负责人考试批** · 复制成新草稿（题目、分值、标准答案、及格线、有效期一并复制） | 需登录（org:exam） |
| GET | /a/organization/exam-attempts | **✅ 临时负责人考试批** · 答卷列表（`?status=1` 为阅卷队列、先交的在前；`?paperId=` / `?volunteerId=` / `?keyword=` 姓名片段或完整手机号） | 需登录（org:exam-grade 或 org:temp-leader） |
| GET | /a/organization/exam-attempts/{id} | **✅ 临时负责人考试批** · 答卷详情（考生姓名、逐题作答、逐题得分、标准答案 / 参考答案、阅卷人与备注） | 需登录（org:exam-grade 或 org:temp-leader） |
| POST | /a/organization/exam-attempts/{id}/grade | **✅ 临时负责人考试批** · 阅卷（body `scores[]`：**每一道主观题恰好一次**、0~该题分值；`note` ≤255）：出分，总分达到及格线即授予资格（有效期从出分时刻起算）；已出分的再阅报「已经出分了」 | 需登录（org:exam-grade） |
| GET | /a/organization/temp-leaders | **✅ 临时负责人考试批** · 资格名单（`?status=` 1 有效 / 2 已到期 / 3 已撤销，按时间现算；`?keyword=`；带手机号、来源试卷与得分、撤销人与原因） | 需登录（org:temp-leader） |
| GET | /a/organization/temp-leaders/export | **✅ 临时负责人考试批** · 批量导出 xlsx（同上筛选，最多 20000 条，超了报错不截断） | 需登录（org:temp-leader） |
| POST | /a/organization/temp-leaders/{id}/revoke | **✅ 临时负责人考试批** · 撤销资格（body `reason` 必填 ≤255；只能撤有效的，已撤销 / 已到期的报错；Q6「评价过低」由组织部判断后手动撤销，不自动） | 需登录（org:temp-leader） |

> **消费方**：① 活动报名开放时间——管理团队 / 活动临时负责人若活动另设了自己那一档（`enrollOpenManager` / `enrollOpenLeader`）且早于志愿者开放时间，按更早的算，**留空＝没有提前**（Q35）；代报名按被代的每个人各自的身份算。
> ② 名单公示与后台按活动的报名列表——管理团队在前、考试通过的临时负责人其次（Row 13 F），出参新增 `tempLeader`（报名列表另有 `manager`）。

---

## 志愿者证 —— `/v/user/volunteer-card` · `/v/user/volunteer-cards/verify`（志愿者证批）

> Row 26：「生成类似身份证的一种志愿者身份证明，里面还会附带一个小程序码，其他人扫码的话能看得到他的志愿者信息」。迁移 V76。
> D11：核验页免登录，码里是**随机令牌、不是志愿者 id**（裸 id 可枚举＝公开全体名册）；公开字段取 Q5 默认。无新增权限点。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/user/volunteer-card | **✅ 志愿者证批** · 我的志愿者证（**第一次打开时发证**，一人一张）：编号 / 姓名 / 头像 / 性别 / 注册时间 / 所属职务 / 归属分队 / 累计服务时长（小时一位小数）/ 活动次数（与排行榜同一口径）/ 令牌签发时间 + 码图：`qrType` 为 `MINIAPP_CODE`（配了小程序 AppID，scene＝令牌，按令牌缓存）或 `QR_CODE`（回退：内容为 H5 核验地址 `?token=`，没配地址时为 `hengde-volunteer-card:令牌`），`qrImage` 是 `data:image/png;base64,…`，`qrContent` 是码里的内容。须已实名且账号正常 | 需登录（+已实名） |
| POST | /v/user/volunteer-card/reset | **✅ 志愿者证批** · 重置证件二维码（换新令牌，**旧码当场失效**；证件截图外流时用），返回新证件 | 需登录（+已实名） |
| GET | /v/user/volunteer-cards/verify | **✅ 志愿者证批** · 扫码核验（`?token=`）：头像 / 姓名（只留姓）/ 编号 / 注册时间 / 累计服务时长 / 活动次数 / 核验时间——**不含手机号与学校**（Q5）。未知令牌、已重置的旧令牌、持证人停用或注销，一律同一句「志愿者证无效或已失效」 | **公开**（免登录，登记在 `SaTokenConfigure`） |

> 配置 `hengde.user.card.*`（`部署/api.env.example` 的 `USER_CARD_*`）：小程序码打开的页面、版本、H5 核验地址、码图缓存秒数。⚠️ 没有真实 AppID，**小程序码那一段从未端到端跑通过**。

---

## 爱心企业账号 —— `/e/auth` · `/e/enterprise/profile` · `/v/enterprise/enterprises` · `/a/enterprise/enterprises`（爱心企业批·账号段）

> Row 15：企业注册（头像、企业名称、信用代码、企业介绍、项目负责人、电话验证码、账号密码）、企业登录管理自己的信息；F 列「爱心企业查看、管理、搜索、注册审核、批量导出，删除，暂停；后台注册企业账号」；Row 49「企业登录」。迁移 V77。
> D5：第三类主体、**第三套登录态**（`StpEnterpriseUtil`，type=enterprise），与志愿者端、管理端 token 互不通用。商品 / 核销员 / 积分账本 / 发帖 / 赞助商评价在后续两段。

### 企业端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /e/auth/sms/codes | **✅ 爱心企业批** · 发短信验证码（body `phone` / `scene`：`enterprise-register` 入驻注册、`enterprise-password-reset` 找回密码——后者须是已绑定企业的负责人手机号） | 公开 |
| POST | /e/auth/register | **✅ 爱心企业批** · 入驻注册（`name` / `creditCode` 18 位含校验位、服务端去空白转大写 / `logoUrl` 选填、须是企业端上传的 / `intro` / `address` / `contactPhone` / `leaderName` / `leaderPhone` + `smsCode` / `username` 4~32 位字母数字下划线、不区分大小写 / `password` 6~32）。**格式与唯一性先查完、最后才核验证码**；落**待审核** | 公开 |
| POST | /e/auth/login | **✅ 爱心企业批** · 登录（`username` / `password`；返回 `token` / `enterpriseId` / `status`）。**待审核、已驳回能登录**（只能看改自己的资料）；**已暂停的登录不了**（报暂停原因）；连错锁定同后台账号 | 公开 |
| POST | /e/auth/logout | **✅ 爱心企业批** · 退出 | 企业登录 |
| PUT | /e/auth/password/reset | **✅ 爱心企业批** · 找回密码（`username` + 负责人 `phone` + `smsCode` + `newPassword`；一个手机号可能是几家企业的负责人，所以要带账号；改完踢掉全部登录） | 公开 |
| PUT | /e/auth/password | **✅ 爱心企业批** · 修改密码（`oldPassword` / `newPassword`；改完踢掉全部登录） | 企业登录 |
| GET | /e/enterprise/profile | **✅ 爱心企业批** · 我的企业资料与入驻状态（0 待审核 / 1 正常 / 2 已驳回，含驳回原因） | 企业登录（待审核 / 驳回也可） |
| PUT | /e/enterprise/profile | **✅ 爱心企业批** · 修改资料：头像 / 介绍 / 地址 / 对外电话**随时可改**；企业名称 / 信用代码 / 项目负责人**只在待审核或被驳回时可改**（传空＝不改；条件写在 UPDATE 的 WHERE 里，审核通过之后要改请平台处理） | 企业登录（待审核 / 驳回也可） |
| POST | /e/enterprise/profile/resubmit | **✅ 爱心企业批** · 被驳回后重新提交（驳回 → 待审核） | 企业登录（驳回也可） |
| POST | /e/files/image | **✅ 爱心企业批** · 上传企业头像 / 照片（multipart `file`，限图片；注册接口不收文件、不开匿名上传，头像登录后补上） | 企业登录（待审核也可） |

> **企业端闸门**（api 的 `EnterpriseAccountGate`）：每个 `/e/**` 请求按账号**当前**状态放行——已删除 / 已暂停：踢出登录 + 403（暂停当场生效）；待审核 / 已驳回：只放行认证、我的企业、上传头像，其余 403「入驻审核通过后才能使用」；正常：放行。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/enterprise/enterprises | **✅ 爱心企业批** · 爱心企业列表（只列正常的，按编号；`?keyword=` 搜店名；编号 / 照片 / 店名 / 地址 / 电话 / 介绍前 60 字） | 需登录 |
| GET | /v/enterprise/enterprises/{id} | **✅ 爱心企业批** · 企业主页（不给信用代码、负责人与登录账号） | 需登录 |

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/enterprise/enterprises | **✅ 爱心企业批** · 企业列表（`?status=` 0 待审核（入驻审核队列，先提交的在前）/ 1 正常 / 2 已驳回 / 3 已暂停；`?keyword=` 企业名称 / 信用代码 / 登录账号片段，或负责人完整手机号；带负责人手机号） | 需登录（enterprise:manage 或 enterprise:audit） |
| GET | /a/enterprise/enterprises/{id} | **✅ 爱心企业批** · 企业详情 | 需登录（enterprise:manage 或 enterprise:audit） |
| GET | /a/enterprise/enterprises/export | **✅ 爱心企业批** · 批量导出 xlsx（同列表筛选，最多 20000 条，含负责人手机号） | 需登录（enterprise:export） |
| POST | /a/enterprise/enterprises | **✅ 爱心企业批** · 后台注册企业账号（直接为正常，审核人记本人；负责人手机号不走验证码；头像先经 `POST /a/files/upload?dir=enterprise`） | 需登录（enterprise:manage） |
| POST | /a/enterprise/enterprises/{id}/approve | **✅ 爱心企业批** · 入驻审核通过（只对待审核；输家报「当前：xx」） | 需登录（enterprise:audit） |
| POST | /a/enterprise/enterprises/{id}/reject | **✅ 爱心企业批** · 入驻审核驳回（body `reason` 必填） | 需登录（enterprise:audit） |
| POST | /a/enterprise/enterprises/{id}/pause | **✅ 爱心企业批** · 暂停（body `reason` 必填；只对正常；立刻踢掉这家企业的登录） | 需登录（enterprise:manage） |
| POST | /a/enterprise/enterprises/{id}/resume | **✅ 爱心企业批** · 恢复 | 需登录（enterprise:manage） |
| DELETE | /a/enterprise/enterprises/{id} | **✅ 爱心企业批** · 删除（逻辑删除，释放登录账号与信用代码；立刻踢掉登录） | 需登录（enterprise:manage） |

---

## 爱心企业赞助商品与积分 —— `/e/donate/**` · `/e/enterprise/points` · `/a/enterprise/enterprises/{id}/goods|points`（爱心企业批·商品段）

> Row 15「发布积分商品，积分商品则需要后台审核」「积分流转：志愿者用积分跟企业兑换的东西，企业将存下这个积分，后续用于兑换企业权益」「后台可以以企业的名义代替企业发布积分商品」；
> Row 8「点赞助企业进去后会显示企业的主页，主页下面有企业的赞助商品」、F 列「企业可以设置某一个志愿者为企业核销员」「商品隐藏功能」。迁移 V78（商品加「赞助方不可用」）/ V79（企业积分账本 + 1 个权限点）。
> 商品、审核、下单、核销仍是 V3 商城那一套；企业端只是多了「只能管自己的」入口。

### 企业端（均要审核通过）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /e/donate/goods | **✅ 爱心企业批** · 我赞助的商品（`?status=` 0草稿/1待审核/2已上架/3已停用/4已驳回；含驳回原因） | 企业登录 |
| GET | /e/donate/goods/{id} | **✅ 爱心企业批** · 商品详情（只看得到自己的） | 企业登录 |
| POST | /e/donate/goods | **✅ 爱心企业批** · 新增赞助商品（body 同 `POST /a/donate/goods`：`name` / `coverUrl` / `detail` / `specs[]`；**赞助方名称取企业名称快照，`sponsorName` 与 `requireCouponId` 不收**；落草稿） | 企业登录 |
| PUT | /e/donate/goods/{id} | **✅ 爱心企业批** · 修改（只能改自己的；**改已上架的退回待审核，审核中不许改**——条件都在 UPDATE 的 WHERE 里） | 企业登录 |
| POST | /e/donate/goods/{id}/submit | **✅ 爱心企业批** · 提交审核（草稿 / 驳回稿 → 待审核；审核在 `POST /a/donate/goods/{id}/approve`） | 企业登录 |
| PUT | /e/donate/goods/{id}/hidden | **✅ 爱心企业批** · 隐藏 / 显示（`?hidden=1/0`；不触发重审） | 企业登录 |
| DELETE | /e/donate/goods/{id} | **✅ 爱心企业批** · 删除（历史兑换单有快照不受影响） | 企业登录 |
| GET | /e/donate/orders | **✅ 爱心企业批** · 我赞助商品的兑换单（`?status=`；**不带取货码与收件信息，兑换人只留姓**，商品分不含抵扣快递费的部分） | 企业登录 |
| GET | /e/donate/verifiers | **✅ 爱心企业批** · 我的核销员（姓名只留姓） | 企业登录 |
| POST | /e/donate/verifiers | **✅ 爱心企业批** · 指派核销员（body `phone` 已实名志愿者的手机号 / `remark`）：**只能核销本企业赞助的商品**，别家的码报「取货码无效」；一个志愿者同时只能是一处的核销员 | 企业登录 |
| DELETE | /e/donate/verifiers/{id} | **✅ 爱心企业批** · 撤销核销员（只能撤自己的） | 企业登录 |
| GET | /e/enterprise/points | **✅ 爱心企业批** · 企业积分总览（余额 / 累计兑换入账 / 累计后台调整） | 企业登录 |
| GET | /e/enterprise/points/records | **✅ 爱心企业批** · 企业积分流水（`?sourceType=` 1兑换入账/2后台调整；新的在前） | 企业登录 |

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/enterprise/enterprises/{id}/goods | **✅ 爱心企业批** · 企业主页的赞助商品（已上架未隐藏；企业须正常） | 需登录 |

> 企业被**暂停或删除**时它赞助的商品一并不可见（`GET /v/donate/goods` 与详情），**直接拿规格 id 下单也兑换不了**（扣库存那条语句带着条件），恢复即复原。

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /a/enterprise/enterprises/{id}/goods | **✅ 爱心企业批** · 以企业名义代发布积分商品（企业须正常；落草稿，之后照常提交审核） | 需登录（donate:goods） |
| GET | /a/enterprise/enterprises/{id}/goods | **✅ 爱心企业批** · 某企业赞助的商品（含草稿 / 待审核） | 需登录（donate:goods 或 enterprise:manage） |
| GET | /a/enterprise/enterprises/{id}/points | **✅ 爱心企业批** · 企业积分总览 | 需登录（enterprise:points） |
| GET | /a/enterprise/enterprises/{id}/points/records | **✅ 爱心企业批** · 企业积分流水 | 需登录（enterprise:points） |
| POST | /a/enterprise/enterprises/{id}/points/adjust | **✅ 爱心企业批** · 调整企业积分（body `amount` 正加负扣、不能为 0 / `remark` / `requestId` 幂等键 8~64 位 ASCII 安全字符）：**重复提交同一个键只记一次，载荷不同报冲突**；扣减不许扣成负数 | 需登录（enterprise:points） |
| POST | /a/enterprise/points/reconcile | **✅ 爱心企业批** · 补记兑换入账（`?since=yyyy-MM-dd HH:mm:ss` 必填、不许是将来）：定时任务只扫回看窗口（默认 72 小时）之内领取的单，停机超过窗口漏掉的用它补；幂等 | 需登录（enterprise:points） |

> **兑换入账**：赞助商品的兑换单变成「已领取」（现场核销 / 本人确认收货 / 系统自动确认）之后，定时任务（默认每 10 分钟，`hengde.enterprise.points.*`）补记，每张单只记一次；金额＝实际扣分减去抵扣快递费的部分，0 分不记。

---

## 爱心企业发帖与赞助商评价 —— `/e/social/posts` · `/e/enterprise/reviews` · `/v/enterprise/**` · `/a/enterprise/reviews`（爱心企业批·社区段）

> Row 15 F「发帖：登录企业账号之后也可以进行发帖，和平台志愿者无异，只不过主页是爱心企业的主页」；Row 74「评价板块：……赞助商评价」+「删除、屏蔽功能」。
> 迁移 V80（`social_post` 作者类型加 3 + 企业名与头像快照）/ V81（`enterprise_review` + 1 个权限点）。
> **企业帖不另建表**：帖子流、先发后审、关键词风控、举报、点赞评论都沿用社区那一套；企业没有关注关系与「不让TA看」，所以**不收可见性**、一律公开。
> **作者名与头像存快照**——social 不依赖 enterprise（依赖方向是 enterprise → social），渲染帖子流时拿不到企业资料；企业改名之后老帖子显示的仍是发帖时的名字（与官方帖快照部门同一口径）。

### 企业端（均要审核通过）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /e/social/posts | **✅ 爱心企业批** · 我发过的帖子（含审核中与被驳回的，带 `reviewStatusLabel`） | 企业登录 |
| POST | /e/social/posts | **✅ 爱心企业批** · 以企业名义发帖（body `content` / `imageUrls` ≤9 或 `videoUrl` 1 个 / `allowComment` / `allowLike`；**不收 `visibility`**；图片先经 `POST /e/files/social-image`、视频经 `/e/files/social-video/presign`） | 企业登录 |
| PUT | /e/social/posts/{id} | **✅ 爱心企业批** · 改自己的帖子（**改完重回待审核并重新判关键词**；只能改自己的） | 企业登录 |
| DELETE | /e/social/posts/{id} | **✅ 爱心企业批** · 删自己的帖子 | 企业登录 |
| GET | /e/enterprise/reviews | **✅ 爱心企业批** · 收到的赞助商评价（含被后台屏蔽的，带屏蔽原因；评价人只留姓） | 企业登录 |
| POST | /e/files/social-image | **✅ 爱心企业批** · 上传帖子图片（限图片；落 `social/`，发帖时按这个目录核「是不是本系统传的」） | 企业登录 |
| POST | /e/files/social-video/presign | **✅ 爱心企业批** · 帖子视频直传签名（`?extension=mp4|mov&size=` 字节数） | 企业登录 |

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/enterprise/enterprises/{id}/posts | **✅ 爱心企业批** · 企业主页的帖子（只列看得到的：后台隐藏 / 被驳回 / 命中关键词还没审完的都不列） | 需登录 |
| GET | /v/enterprise/enterprises/{id}/reviews | **✅ 爱心企业批** · 企业主页的赞助商评价（只列正常的，不带屏蔽状态） | 需登录 |
| POST | /v/enterprise/enterprises/{id}/reviews | **✅ 爱心企业批** · 评价赞助商（body `orderId` / `rating` 1~5 / `content` ≤500）：**凭一张自己的、已领取的、这家企业赞助的兑换单，一单一评** | 需登录 |
| GET | /v/enterprise/reviews/mine | **✅ 爱心企业批** · 我写过的赞助商评价（含被屏蔽的，带屏蔽原因） | 需登录 |

> 企业帖照常出现在 `GET /v/social/posts`（最新 / 最热）与详情、点赞、评论、举报里——**「和平台志愿者无异」**；作者卡片的 `type=3`。
> ⚠️ 企业帖的 `author_id` 是**企业** id，与志愿者 id 是两套自增序列：凡是拿 `author_id` 去查志愿者的地方（作者卡片、发帖人设置、「这是我发的」、后台真实姓名、站内提示、互动记录）都必须先看作者类型，否则会借用「id 恰好相同的那个志愿者」的东西。

### 管理端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/enterprise/reviews | **✅ 爱心企业批** · 赞助商评价列表（`?enterpriseId=` / `?status=` 0正常 1已屏蔽） | 需登录（enterprise:review） |
| POST | /a/enterprise/reviews/{id}/hide | **✅ 爱心企业批** · 屏蔽（body `reason` ≤255；对外藏起来，作者与后台仍看得到、可恢复；CAS 只对「当前正常」的） | 需登录（enterprise:review） |
| POST | /a/enterprise/reviews/{id}/show | **✅ 爱心企业批** · 恢复显示 | 需登录（enterprise:review） |
| DELETE | /a/enterprise/reviews/{id} | **✅ 爱心企业批** · 删除（逻辑删除；删掉之后这张兑换单可以重新评价） | 需登录（enterprise:review） |

> 企业帖的**审核与治理走社区那一套**：待审队列 `GET /a/social/reviews`、通过 / 驳回、隐藏 / 置顶 / 删除、举报处理都在 `/a/social/**`，不另开一套入口；只有**驳回不写站内提示**（企业没有志愿者的那个收件箱）。

---

## 私信 —— `/v/social/chats/**` · `/a/social/chats|chat-reports/**` · `ws:/ws/social/chat`（私信批）

> Row 23「私信：完全参考抖音的私信界面」「私聊：用户双方可以通过主页搭建起聊天通道」+ F 列「所有聊天记录均保存在系统后台、查看权限仅为最高管理员，亦可由管理员下放」
> 「用户投诉私聊信息时，审核员能看到聊天内容，并进行审核」「关键词风控：……私聊如触发关键词，则自动进入后台插队审核」。迁移 V82。
> **先落库、后推送**（V4规划 D8）：发消息走 HTTP，实时推送走 WebSocket，推不出去只是对方晚一点看到。
> **清空聊天记录只对自己**（抬高自己那一侧的水位），后台保存的记录一条不少。

### 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/social/chats | **✅ 私信批** · 我的会话列表（对方昵称头像 + 最后一条 + 未读数，按最后一条时间倒序；自己清空之后没有新消息的不列） | 需登录（已验手机号） |
| GET | /v/social/chats/unread-count | **✅ 私信批** · 未读总数（角标） | 需登录 |
| GET | /v/social/chats/{peerId}/messages | **✅ 私信批** · 和某个人的消息（新的在前，`?beforeId=` 往前翻、`?size=` ≤100；自己清空之前的不返回） | 需登录（已验手机号） |
| POST | /v/social/chats/{peerId}/messages | **✅ 私信批** · 发私信（body `content` ≤1000 / `imageUrl` 1 张，先经 `POST /v/files/social-image`）：要已实名；对方要账号正常且已实名；**对方设了「禁止私信」或把我「不让TA看」报同一句话**；**对方没回之前最多 3 条**；禁言（能力域 7）只挡发不挡收 | 需登录（已实名） |
| POST | /v/social/chats/{peerId}/read | **✅ 私信批** · 标记已读（清我这一侧的未读） | 需登录 |
| DELETE | /v/social/chats/{peerId}/messages | **✅ 私信批** · 清空聊天记录（**只对我自己**；后台记录不受影响） | 需登录 |
| POST | /v/social/chats/{peerId}/reports | **✅ 私信批** · 投诉这段私聊（body `reason` ≤200；同一段对话只留一条待处理） | 需登录（已实名） |
| WS | /ws/social/chat?token= | **✅ 私信批** · 实时收新私信（**只收不发**，发消息走上面的 HTTP）：握手用登录 token 认人，推送体 `{"type":"message","data":{...}}`，客户端发什么都只回 `{"type":"pong"}` | 握手带 token |

> 「禁止私信」开关在 `PUT /v/social/settings` 的 `forbidChat`（与禁止关注 / 评论 / 点赞同一份设置）。
> ⚠️ WebSocket 的 token 在查询串里（浏览器与小程序的 WebSocket API 都不让加请求头），线上要把 `/ws/` 的 access_log 关掉或脱敏；nginx 还要放行 `Upgrade`/`Connection` 头（见《部署说明》）。

### 管理端（均需 `social:chat-view`，**默认不授任何人**，超管通配）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/social/chats | **✅ 私信批** · 会话列表（`?volunteerId=` 只看某个人的） | 需登录（social:chat-view） |
| GET | /a/social/chats/{id}/messages | **✅ 私信批** · 一条会话的全部消息（**含双方各自清空的、已删除的**，已删除的标出来） | 需登录（social:chat-view） |
| DELETE | /a/social/chat-messages/{id} | **✅ 私信批** · 删一条违规消息（双方都看不到；后台仍列得出来） | 需登录（social:chat-view） |
| GET | /a/social/chat-reports | **✅ 私信批** · 私聊工单队列（`?status=` 0待处理/1成立/2不成立；**关键词命中的插队在前**） | 需登录（social:chat-view） |
| POST | /a/social/chat-reports/{id}/handle | **✅ 私信批** · 处理工单（body `valid` 必填 / `note` / `deleteMessage`）：CAS，两个人同时点只成一个 | 需登录（social:chat-view） |

> **关键词命中不藏消息**：帖子是先藏后审，私聊藏起来等于单方面切断对话且双方都不知道；命中的消息照常送达，另生成一条来源为「关键词」的工单插队进队列。
> **禁言不在处理工单里做**：处置是奖惩域的动作，走 `POST /a/social/bans`（能力域 7「禁止私信」已并入，`SanctionScope.COMMUNITY` 与 `ALL` 蕴含它）。

---

## 系统治理 —— `/a/system/**` · `/v/system/files` · `/share/files/{token}`（系统治理批）

> Row 62 日志记录 / Row 71 文件功能（类似网盘）+ Row 19 通知公告的内置文件与定时开放 / Row 75 编号命名 / Row 77 功能排序 / Row 78 界面水印 / Row 63 到家定位。迁移 V83，**新建模块 `system`**。
> 口径：写操作与敏感读全记、页面访问由前端上报、保留 180 天（Q8）；编号只对 V4 新对象启用（Q12）。

### 操作日志与页面访问

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/system/logs | **✅ 系统治理批** · 操作日志（`?logType=` 1操作/2页面访问、`?actorType=`、`?actorId=`、`?keyword=` 动作或路径或姓名、`?from=`/`?to=`；新的在前） | 需登录（system:log） |
| POST | /a/system/page-views | **✅ 系统治理批** · 前端上报一次页面访问（body `page` 必填 / `path`）——后端看不见纯前端的路由切换 | 需登录 |

> **记什么**：`/a/**` 的全部写操作 + 显式登记的敏感读（各域导出、志愿者详情、聊天记录、到家名单、证书下载、分享链接被打开、以及「谁翻了日志」）。
> **动作名取 Swagger 的 `@Operation(summary)`**，不另维护一份动作名表；**不记请求体**（改密码那类请求体里就是密码），只记查询串。
> **失败也记**（`success=false` + 原因）：被拒绝的越权尝试恰恰是追责时要看的。**异步批量落库、失败只记 ERROR**；队列满了丢弃并 WARN——日志绝不能把业务拖住。
> **只追加**：没有删除入口，唯一的删除路径是按保留期（默认 180 天）的定时清理。

### 系统配置（水印 / 菜单 / 编号段）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/system/watermark | **✅ 系统治理批** · 界面水印设置 + **给当前账号算好的水印文字**（Row 78：姓名 · 部门 · 手机尾号 · 日期） | 需登录 |
| PUT | /a/system/watermark | **✅ 系统治理批** · 改水印（`enabled` / `showName` / `showDepartment` / `showPhoneTail` / `showDate` / `fontSize` / `opacityPercent` / `rotateDegree`；不传的保持原样） | 需登录（system:config） |
| GET | /a/system/menu-order | **✅ 系统治理批** · 后台菜单排序（按 `sort` 出；`visible=false` 表示在侧边栏里藏起来，权限仍以后端为准） | 需登录 |
| PUT | /a/system/menu-order | **✅ 系统治理批** · 改菜单排序（整份覆盖；菜单键重复直接拒） | 需登录（system:config） |
| GET | /a/system/serials | **✅ 系统治理批** · 编号段一览（Row 75：十位数字，前三位是功能段） | 需登录（system:config） |

> **水印文字由后端算**：前端自己拼的话，手机尾号要先把手机号发到前端——为了防泄露先泄露一次说不通。关掉水印时连文字都不下发。

### 文件网盘（Row 71 + Row 19）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/system/vault/folders | **✅ 系统治理批** · 文件夹树（只列我看得到的；`canWrite` 说明能不能往里放东西） | 需登录（system:file） |
| POST | /a/system/vault/folders | **✅ 系统治理批** · 新建文件夹（body `parentId` / `name` / `sort`；**根目录只有超管能建**） | 需登录（system:file） |
| PUT | /a/system/vault/folders/{id} | **✅ 系统治理批** · 改文件夹（名称 / 排序） | 需登录（system:file） |
| DELETE | /a/system/vault/folders/{id} | **✅ 系统治理批** · 删文件夹（**有下级或有文件就拒绝**，不连带删） | 需登录（system:file） |
| GET | /a/system/vault/folders/{id}/files | **✅ 系统治理批** · 文件夹里的文件（`?keyword=` 按文件名或编号） | 需登录（system:file） |
| POST | /a/system/vault/files | **✅ 系统治理批** · 登记一个文件（body `folderId` / `name` / `fileUrl` / `fileSize`；先经 `POST /a/files/upload?dir=vault`，**只收本系统上传的**；编号自动取十位号） | 需登录（system:file） |
| PUT | /a/system/vault/files/{id}/name | **✅ 系统治理批** · 重命名（`?name=`） | 需登录（system:file） |
| PUT | /a/system/vault/files/{id}/folder | **✅ 系统治理批** · 移动到别的文件夹（`?folderId=`；**两边都要有写权限**） | 需登录（system:file） |
| DELETE | /a/system/vault/files/{id} | **✅ 系统治理批** · 删除文件（逻辑删除） | 需登录（system:file） |
| PUT | /a/system/vault/files/{id}/publish | **✅ 系统治理批** · 公开到小程序 + 开放时间与下载开关（body `published` 必填 / `publishStart` / `publishEnd` / `allowDownload`；**撤销公开会把窗口一起清掉**） | 需登录（system:file） |
| GET | /a/system/vault/folders/{id}/grants | **✅ 系统治理批** · 文件夹授权列表 | 需登录（system:file） |
| POST | /a/system/vault/folders/{id}/grants | **✅ 系统治理批** · 授权（body `granteeType` 1后台账号/2部门 + `adminId` 或 `department` + `canWrite`；**仅超管**，部门必须当前有启用账号） | 需登录（system:file） |
| DELETE | /a/system/vault/grants/{id} | **✅ 系统治理批** · 撤销授权（**仅超管**） | 需登录（system:file） |
| GET | /a/system/vault/files/{id}/shares | **✅ 系统治理批** · 这个文件的分享链接 | 需登录（system:file） |
| POST | /a/system/vault/files/{id}/shares | **✅ 系统治理批** · 分享（body `requireLogin` 默认 true / `hours` 默认 168、0=不过期）：**令牌是随机串不是 id** | 需登录（system:file） |
| DELETE | /a/system/vault/shares/{id} | **✅ 系统治理批** · 撤销分享 | 需登录（system:file） |
| GET | /v/system/files | **✅ 系统治理批** · 志愿者端「内置文件」（Row 19）：**此刻在开放窗口里的**；关掉下载的只给名字不给地址 | 需登录 |
| GET | /share/files/{token} | **✅ 系统治理批** · 打开一条分享（**公开路径**，与 `/callback/**` 同一机制落在鉴权处理器之外）：要登录的分享没登录时报错；每打开一次记一次下载数；不存在 / 已撤销 / 已过期报同一句话 | 按分享设置 |

> **授权沿文件夹往下继承**（授在上级对下级也算数），超管不看授权表；**看不到的整枝在树里不出现**（露出名字就等于露出了协会的目录结构）。
> **开放窗口按时间现算**，不靠定时任务改状态位；与 `GET /v/publicity/files`（后台直传的公示文件）是两份来源，小程序「文件下载」板块合着显示。

### 到家详细地址（Row 63）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/activities/{id}/home-confirmations | **✅ 系统治理批** · 到家名单（列签过到的人；**详细地址与坐标只对持 `activity:home-address` 的账号下发**，其余只看得到「已到家」与时间） | 需登录（activity:manage） |

> 志愿者确认到家时可带 `address`（`POST /v/activity/activities/{id}/confirm-home` 的新字段，可空）。
> **可见性做在字段上**：没有那个权限点的账号拿到的响应里根本没有地址——前端打码挡不住看响应体的人。

---

## 数据汇总 —— `/a/data/summary`（数据汇总批）

> Row 79「数据汇总」九块：平台 / 社区 / 爱心企业 / 志愿小组 / 微心愿 / 结对 / 捐书 / 相册 / 举报。无迁移、无新权限点。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/data/summary | **✅ 数据汇总批** · Row 79 九块合成一份（平台含**用户人数（含游客）**；捐赠三块与 `/a/data/donation-summary` 同一口径） | 需登录（data:dashboard） |

> **每个数字的口径都收在 owner 域的只读 service**（social / enterprise / organization / activity / honor / auth / donate），data 只组装——与看板、捐赠汇总同一条纪律。
> **系统里没有的概念给 null 不给 0**：相册的视频总数与查看 / 下载人次、捐书的「修建书屋数」。出参省略 null 字段，前端拿到的是**没有这个键**；给 0 会被读成「一次都没有」。
> ⚠️ 几处口径与 Row 79 的字面不完全一样，出参字段名与说明都写明了：**「被封小组数」系统里对应的动作是解散**；「违规帖子数」＝被驳回 ∪ 被后台隐藏（命中关键词还在排队的不算）；「处罚数」＝已通过的奖惩处罚单 + 社区禁言单。
> `GET /a/data/dashboard` 保持「仅需登录」不变（志愿者端首页也在用那组数字），汇总这一份含举报、封号与金额，所以挂 `data:dashboard`。

---

## 权限点登记（V4 新增，实施时同步 `permission` 种子与 `OrganizationRbacTest` 总数断言）

| 权限点 | 含义 | 批次 |
|---|---|---|
| `org:form` | 问卷管理（建 / 改草稿 / 发布 / 停止 / 复制 / 删草稿） | 问卷引擎批 |
| `org:form-data` | **答卷查看与导出**——与 `org:form` 分开：答卷里有填写人的手机号，同 `user:list` / `user:export`、`donate:item` / `donate:item-export` 的先例 | 问卷引擎批 |
| `data:complaint` | 投诉建议处理（**只限当前在本部门的工单**：受理 / 流转 / 答复 / 备注） | 投诉建议批 |
| `data:complaint-all` | 投诉建议全部门（看全部工单并可代任何部门处理；Row 43「监察部可根据工作需要选择流转」，默认给监察部） | 投诉建议批 |
| `user:center-content` | 个人中心内容设置（我的保险 / 联系客服） | 个人中心补全批 |
| `org:structure` | 组织架构维护（节点增删改、放人 / 挪人 / 移出；Row 6「均有最高权限操作」，默认只给超管） | 组织架构维护批 |
| `social:official` | 官方帖（本部门发布 / 删除 / 以官方身份评论 / 删除本部门官方帖下的评论） | 社区核心批 |
| `social:official-all` | 官方帖全部门（删除其他部门的官方帖与评论；Row 23「宣传部可以删除其他部门发的帖子」，默认给宣传部） | 社区核心批 |
| `social:post-manage` | 社区帖子与评论管理（两个列表 / 隐藏 / 删除 / 置顶） | 社区治理批 |
| `social:review` | 社区帖子审核（还须被设为某一级审核员；超管不必） | 社区治理批 |
| `social:review-setting` | 审核设置（审核级数 / 审核员 / 风控关键词） | 社区治理批 |
| `social:report` | 社区举报处理 | 社区治理批 |
| `social:ban` | 社区禁言（禁止发帖 / 评论 / 点赞几天） | 社区治理批 |
| `social:real-name` | 查看社区发布人的真实姓名与学校（Row 23 F「最高权限」；默认不授任何人，超管通配） | 社区治理批 |
| `activity:album` | 活动相册管理（搜索 / 新增 / 后台上传 / 积分规则） | 活动相册批 |
| `activity:album-delete` | 删除相册（Row 11：理事会、宣传部、各部门部长） | 活动相册批 |
| `activity:album-photo-delete` | 删除照片（Row 11：理事会、宣传部、各部门部长、宣传部成员） | 活动相册批 |
| `activity:album-download` | 批量下载（Row 11：理事会、宣传部、各部门部长、宣传部成员） | 活动相册批 |
| `activity:album-points-audit` | 相册上传积分审核 | 活动相册批 |
| `org:exam` | 临时负责人考试试题管理（试卷 / 题目 / 开放停止 / 复制） | 临时负责人考试批 |
| `org:exam-grade` | 临时负责人考试阅卷（主观题人工判分；可看试卷与标准答案） | 临时负责人考试批 |
| `org:temp-leader` | 活动临时负责人管理（名单 / 撤销资格 / 批量导出；可看历史答卷） | 临时负责人考试批 |
| `enterprise:manage` | 爱心企业管理（查看 / 搜索 / 后台注册 / 暂停恢复 / 删除） | 爱心企业批 |
| `enterprise:audit` | 爱心企业入驻审核（可看列表与详情） | 爱心企业批 |
| `enterprise:export` | 爱心企业批量导出（含负责人手机号，与查看分开授权） | 爱心企业批 |
| `enterprise:points` | 爱心企业积分（查看账本 / 调整 / 补记兑换入账） | 爱心企业批·商品段 |
| `enterprise:review` | 赞助商评价管理（查看 / 屏蔽 / 恢复 / 删除） | 爱心企业批·社区段 |
| `social:chat-view` | 聊天记录查看与私聊投诉处理（**默认不授任何人**，由超管显式下放） | 私信批 |
| `system:log` | 操作日志查看（Row 62「最高权限才可查看」，**默认不授任何人**） | 系统治理批 |
| `system:config` | 系统配置（界面水印 / 后台菜单排序 / 编号段） | 系统治理批 |
| `system:file` | 文件网盘（文件夹 / 文件 / 授权 / 分享 / 公开到小程序） | 系统治理批 |
| `activity:home-address` | 查看到家详细地址（Row 63，**默认不授任何人**） | 系统治理批 |

> V3 全部落地后基线 **64**；问卷引擎批（V63）后为 **66**；投诉建议批（V65）后为 **68**；个人中心补全批（V66）后为 **69**；组织架构维护批（V69）后为 **70**；社区核心批（V71）后为 **72**；社区治理批（V72）后为 **78**；活动相册批（V74）后为 **83**；临时负责人考试批（V75）后为 **86**；爱心企业批账号段（V77）后为 **89**；商品段（V79）后为 **90**；社区段（V81）后为 **91**；私信批（V82）后为 **92**；系统治理批（V83）后为 **96**；数据汇总批不新增权限点（`data:dashboard` 从「仅菜单可见性」升级为真正受控的端点）。
