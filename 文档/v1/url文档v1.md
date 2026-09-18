# 接口 URL 约束文档 V1

> ## ❄️ 本文件已冻结（2026-07-29）
>
> 本文件是**旧版接口副本，冻结于 2026-07-29**，**不再随代码更新**。
>
> 它**不是「V1 / V1.1 交付时点的快照」**——冻结之前它一直随代码更新，因此内容里已经含有积分账本、排行榜、勋章等 **V2 期**的接口。
> 称其为某个版本的交付快照，会让人误以为能据此回溯 V1 当时的接口面貌，实际不能；它只是「冻结当天的那一份副本」。
> 现行唯一接口契约是 **[`文档/v2/url文档v2.md`](../v2/url文档v2.md)** —— 写 Controller、对前端讲鉴权，一律以那份为准。
>
> 冻结时点起，本文件与实际代码的**已知偏差**（不在此修正，仅作说明）：
>
> | 偏差 | 说明 |
> |---|---|
> | 缺 `POST /a/activity/activities/{id}/cancel` | 取消活动，V2 期新增，仅见于 V2 文档 |
> | `GET /v/user/volunteer-card` 未标未实现 | 该路径仅预留，至今无实现；V2 文档已标「⬜ 未实现」 |
> | `GET /v/home` 未标占位 | 有 Controller 映射但聚合逻辑为 TODO；V2 文档已标注 |
> | 管理端**鉴权列大量只写「需登录」** | 实际带 `@SaCheckPermission` 细粒度权限点（`activity:menu`/`activity:publish`/`activity:edit`/`org:*`/`pub:*` 等）。**这是本文件最容易误导前端的一处**，准确的权限点见 V2 文档 |

## 设计规范

| 规范项 | 值 |
|---|---|
| context-path | `/api`（由 `server.servlet.context-path` 配置，Controller 代码中不写） |
| 完整 URL 格式 | `https://{host}/api/{role}/{domain}/{resource}` |
| 角色前缀 `/v` | 小程序志愿者端 |
| 角色前缀 `/a` | 管理后台 |
| 角色前缀 `/e` | 爱心企业端（V1 暂缓，路径已预留） |
| 资源路径 | **复数名词**，如 `/activities`、`/announcements` |
| 动作 | **HTTP Method 语义**：GET 查询、POST 创建、PUT 全量更新、PATCH 局部更新、DELETE 删除 |
| 动词性操作 | **子资源后缀**，避免 `/doXxx`，如 `/enroll`、`/approve`、`/reject`、`/copy` |
| 分页参数 | `page`（从 1 开始）、`size`（默认 10，最大 100） |
| 搜索参数 | `keyword=` 关键词；列表接口按需附加筛选参数 |
| 鉴权约定 | 公开接口无需 token；其余接口在请求头 `Authorization` 携带 Sa-Token |

---

## 聚合接口（api 层，无领域模块归属）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/home | 首页聚合（轮播图 + 数据看板 + 推荐活动） | 需登录 |
| GET | /v/search | 全局搜索，`?keyword=&page=&size=` | 需登录 |

> **全局搜索 `/v/search` 说明**：跨领域按标题/名称匹配，合并成**单一信息流**返回 `PageResult<SearchItemVO>`，
> 面向小程序下滑加载（`page` 从 1 递增、`size` 默认 10，前端追加，无翻页按钮）。
> - 覆盖范围（按固定顺序拼接）：**活动**（已发布，标题）→ **公告**（已发布，标题）→ **小组**（正常状态，名称/编号）→ **分队**（启用，名称）。志愿者本身不纳入检索（PII）。
> - `SearchItemVO` 字段：`type`（`activity`/`announcement`/`group`/`squad`）、`id`、`title`、`summary`（无则 null）、`imageUrl`（活动/公告取封面图，小组/分队为 null）。
> - `total` 为各领域真实命中数之和（精确，无截断）；按全局 `offset/limit` 跨领域块取窗口。

---

## 认证 auth

### 志愿者端 `/v/auth`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /v/auth/sms/codes | 发送短信验证码；body `{phone, scene}`，scene 白名单 `register`(默认)/`login`/`volunteer-password-reset`/`change-phone`，越界拒绝；复用发码限流+错满作废 | 公开 |
| POST | /v/auth/login/sms | **手机号+验证码登录**（`{phone, smsCode}`，scene=login）；陌生手机号自动建游客账号（之后再实名），禁用/注销拒登 | 公开 |
| POST | /v/auth/login/password | **手机号+密码登录**（`{phone, password}`，账号=手机号）；接防爆破（phoneHash/IP 计数），账号不存在/未设密码/密码错统一报错不泄露存在性 | 公开 |
| PUT | /v/auth/password | **设置/修改登录密码**（`{oldPassword?, newPassword}`）；首次设密码原密码可空，已有密码须校验原密码；账号须已绑手机号 | 需登录 |
| PUT | /v/auth/password/reset | **忘记密码**：手机号+验证码+新密码重置（`{phone, smsCode, newPassword}`，scene=volunteer-password-reset） | 公开 |
| POST | /v/auth/login/wechat | 微信小程序登录；未注册返回 `registered:false`，已注册返回 token（端点保留；小程序默认改走手机号体系） | 公开 |
| POST | /v/auth/login/dev | **开发登录**：跳过微信直接发 token 供前端联调（无 appid/secret 时用）。body 可选 `key`（测试身份，默认 tester）/`registered`（true 造已实名身份）。**仅 `hengde.auth.dev-login-enabled=true` 可用，生产被 `ProductionConfigGuard` fail-fast 拒绝** | 公开（dev 限定） |
| GET | /v/auth/agreement | 获取志愿者协议（注册前阅读）；返回 `{version, text}`，正文/版本经配置 | 公开 |
| POST | /v/auth/register | 志愿者实名注册（身份证二要素 + 短信验证码 + 企业微信群校验 + **协议手写签名图 URL**）；落库前显式查 phoneHash 防串号（已绑他号报错、登录手机号须与注册一致） | 需登录（先登录拿游客 token） |
| GET | /v/auth/wechat/group-membership | 企业微信群成员资格校验 | 公开 |
| POST | /v/auth/logout | 退出登录 | 需登录 |

### 管理端 `/a/auth`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /a/auth/login | 账号密码登录 | 公开 |
| POST | /a/auth/sms/codes | 发送短信验证码（仅用于找回密码） | 公开 |
| PUT | /a/auth/password/reset | 凭短信验证码重置密码 | 公开 |
| GET | /a/auth/me | 当前管理员资料+权限码（adminId/username/realName/department/superAdmin/permissionCodes，超管为 `["*"]`；前端据此渲染菜单/按钮） | 需登录 |
| PUT | /a/auth/password | 修改密码（已登录状态） | 需登录 |
| POST | /a/auth/logout | 退出登录 | 需登录 |

### 管理端 通用上传 `/a/files`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /a/files/upload | 通用文件/图片上传（multipart `file` + **`dir` 必传**：banner/announcement/activity/summary/file，未知 dir 拒绝），返回 `{url,name,size}`；业务表只存 url。**按 dir 双门槛**：权限(banner→pub:banner / announcement→pub:announcement / file→pub:file / activity→activity:publish或edit / summary→activity:manage)+ 类型(图片目录仅图片、file 目录收文档) | 需登录 + 对应 dir 权限 |

---

## 用户 user

### 志愿者端 `/v/user`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/user/profile | 获取本人完整资料（`no` 编号[=志愿者 id 字符串，「我的」页顶部展示]/姓名/昵称/手机号/身份证完整号[本人查看自己]/政治面貌/学校/年级/地址/紧急联系方式 + 时长/积分/参与活动数/所在小组/归属分队）；游客也可取（实名字段为空、`registered:false`） | 需登录 |
| PATCH | /v/user/profile | 更新可修改项（头像/i志愿者码/昵称[全局唯一去重]/学校/年级/政治面貌/通讯地址/紧急联系方式，**部分更新**仅传非空字段）；**手机号走 `PUT /v/user/phone`**；姓名/身份证「不可修改」（实名字段仅后台超管 `PUT /a/user/volunteers/{id}` 可改） | 需登录 |
| PUT | /v/user/phone | 修改/换绑手机号（`{phone, smsCode}`，新号需 scene=change-phone 短信验证；查重不可撞其它账号；账号=手机号，同步改 phone 密文+phoneHash） | 需登录 |
| GET | /v/user/volunteer-card | 获取电子志愿者证（类身份证样式，含内嵌小程序码） | 需登录 |

### 管理端 `/a/user`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/user/volunteers | 志愿者列表（`?keyword=&gender=&squad=&political=&school=&grade=&managerFlag=&page=&size=`；keyword 纯数字按手机号 HMAC 精确，否则姓名/学校模糊；`managerFlag=1` 只出管理团队志愿者[负责人选人用]；仅返回已实名志愿者） | `user:list` |
| GET | /a/user/volunteers/{id} | 志愿者详情（含明文手机号、身份证尾号；仅已实名） | `user:list` |
| PUT | /a/user/volunteers/{id} | 修改志愿者全量信息（实名敏感字段，全量 PUT 可清空字段） | **仅超管**（`user:edit`，不入权限点表、不可分配，service 手写 `is_super_admin` 校验） |
| PATCH | /a/user/volunteers/{id}/status | 暂停/恢复志愿者账号（body: `{"status": 0/1}`，仅 0正常/1禁用） | `user:status` |
| DELETE | /a/user/volunteers/{id} | 删除志愿者（逻辑删除） | `user:delete` |
| POST | /a/user/volunteers/{id}/password/reset | 重置志愿者密码=**清空** `password`（V20 起有密码列；管理员不设/不知明文，志愿者之后用手机号验证码登录再自设新密码） | `user:pwd-reset` |
| GET | /a/user/volunteers/export | 批量导出志愿者（Excel，支持与列表相同的筛选参数） | `user:export` |

---

## 活动 activity

### 志愿者端 通用上传 `/v/files`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /v/files/upload | 图片上传（multipart `file` + `dir`，**仅 `dir=activity`** 活动封面、限图片），返回 `{url,name,size}`。给「管理团队」志愿者在小程序发活动传封面用；与 `/a/files/upload` 分开（小程序持志愿者 token 过不了 `/a/**`） | 需登录（activity:publish） |
| POST | /v/files/profile-image | 图片上传（multipart `file` + `dir`，**仅 `dir=avatar`** 个人头像、限图片），返回 `{url,name,size}`。任意登录志愿者「我的资料」改头像用（无需 activity:publish） | 需登录 |

### 志愿者端 `/v/activity`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/activities | 活动列表/推荐（排序：有名额优先→最新活动时间；返回含 `enrolledCount` 报名人数、`hasQuota` 是否有名额、`displayStatus` 实时展示状态[0未开放/1报名中/2报名截止/3活动中/4已结束，按开放/截止/run_status 派生，非持久化 status]） | 需登录 |
| POST | /v/activity/activities | 提交活动（「管理团队」志愿者；不带 `type=admin` 走默认 login 域鉴权，吃 V18 志愿者权限；**V19 起：落「待审核发布」status=4、不直接上线，须后台 `activity:publish-audit` 审核通过才可见**；操作人记志愿者） | 需登录（activity:publish） |
| GET | /v/activity/activities/{id} | 活动详情（含子时间段/子项目/报名须知；内部展示全字段：定位+经纬度、三类报名开放时间、报名限制等） | 需登录 |
| POST | /v/activity/activities/{id}/enroll | 报名（body 指定时间段） | 需登录 |
| DELETE | /v/activity/activities/{id}/enroll | 取消报名 | 需登录 |
| POST | /v/activity/activities/{id}/proxy-enrollments | 同小组成员代报名 | 需登录 |
| GET | /v/activity/my-enrollments | 我的报名列表 | 需登录 |
| GET | /v/activity/my-activities | 我的活动（名称/时间段/负责人/签到状态/是否违规/考勤） | 需登录 |
| GET | /v/activity/my-activities/{id} | 我的活动详情（含考勤 + 签到二维码数据 + 紧急上报电话 `emergencyPhone`） | 需登录 |
| POST | /v/activity/activities/{id}/check-in | 自助签到（扫负责人签到码后调；body: lat/lng/method；GPS 距活动 ≤ 签到半径 且在签到时间窗口内） | 需登录 |
| POST | /v/activity/activities/{id}/check-out | 自助签退（扫负责人签退码后调；body: lat/lng/method；GPS ≤ 签退半径 且活动结束后 2h 内；算服务时长=签退−签到；签退坐标仅校验不留存） | 需登录 |
| POST | /v/activity/activities/{id}/confirm-home | 确认到家（body: lat/lng；活动结束后；超时仅记录不拒绝） | 需登录 |
| POST | /v/activity/activities/{id}/review | 评价活动与负责人（body: 活动评分1~5/负责人评分1~5/评论；须实际签到、活动结束后；可覆盖） | 需登录 |
| GET | /v/activity/service-records | 我的服务记录（活动名称/签到/签退/时长） | 需登录 |

### 积分中心 — 志愿者端 `/v/activity`（V2 第 1 批，V24）

> 积分以 `point_record` 账本为唯一事实来源。**志愿者 id 一律取自登录态、不接受入参**，避免越权查他人积分。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/points | 我的积分总览（总积分 `totalEarned` / 已使用 `totalSpent` / 当前余额 `balance`；**「已使用」按来源判定**——仅兑换类消费计入，积分修正/手工扣减/奖惩产生的负数冲减「总积分」而非算作消费，故 V3 兑换上线前 `totalSpent` 恒为 0；恒等式 `balance = totalEarned − totalSpent`） | 需登录 |
| GET | /v/activity/points/records | 我的积分明细（分页；`keyword` **对流水说明 `remark` 做模糊匹配**——活动积分的说明含活动名、积分修正的说明含申请理由，故可按活动名或改动原因检索；注意 `remark` 列宽 512 字符，极端超长的理由会被截断，尾部内容搜不到，完整原文以 `activity_attendance_change.reason` 为准。另可按 `sourceType` 来源、`startTime`/`endTime` 时间区间筛选；按发生时间倒序，每行带来源中文名 `sourceTypeName`） | 需登录 |

### 排行榜 — 志愿者端 `/v/honor`（V2 第 2 批，V25+V26）

> **当期实时聚合，往期读冻结快照**。历史月份的名次必须冻结，否则事后的活动补录/考勤修正/积分调整
> 会让「2026 年 7 月排行」今天看和下月看不一样，需求里的「历史月份下拉框」就失去意义。
> **总榜（periodType=3）恒为当期**，不存在「历史的总榜」，故永不快照。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/honor/rankings | 排行榜（`rankType` 1活动次数/2活动时长/3积分；`periodType` 1月/2年/3总；`periodKey` 月 `2026-07` / 年 `2026`，总榜可不传；`limit` 默认 50 上限 100）。出参带 `fromSnapshot`——false 表示该周期尚无快照、正按当前数据实时聚合（名次仍会漂移）。**`rankType=4` 微心愿排行返回「尚未开放」而非空榜单**（数据源属 donate，V3 才建） | 需登录 |

### 勋章与榜样 — 志愿者端 `/v/honor`（V2 第 3 批，V28）

> 志愿者端**只负责展示**：勋章的录入、审核、发放全在后台。
> **只看得到「已生效」的发放**——待审/驳回的记录对志愿者不可见，否则发放审核形同虚设。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/honor/medals | 我的勋章：返回**已启用勋章 ∪ 本人已获得的勋章**（停用不收回已发的，故已获得的即使样式已停用仍在列） + `owned` 是否已获得 + `grantTime` + **获取进度**（`currentValue`/`progressPercent`，百分比封顶 100）。**样式按「最后一次通过审核」的版本展示（V29 快照）**——管理员改了已发出去的勋章会退回重审，未过审的名称/图标不会外泄给已获得者。进度对「有阈值」的条件即时计算：时长/次数取 `ActivityRankingQueryService`（**只认已发布/已结束活动上的真实签到**，与排行榜同口径，不是服务记录那份不筛活动状态的粗口径）、积分取**累计获得**（非余额，花掉积分不该丢进度）；手动授予类无进度，相关字段为 null。**志愿者 id 取自登录态，不接受入参** | 需登录 |
| GET | /v/honor/role-models | 榜样列表（**仅已上架**，按 sort 正序） | 需登录 |

### 活动现场负责人 — 志愿者端 `/v/activity/managed-activities`

> 仅活动的**已指派负责人（志愿者）**可访问；管理团队负责人走 `/a/activity`（下表对应动作）。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/managed-activities | 我负责的活动场次列表 | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id} | 负责详情（志愿者名单含名字/电话/学校 + 紧急上报电话 `emergencyPhone`；名单 roster 即「签到记录」数据：签到/签退时间、到位状态、违规数。二维码不在此，见下 check-in-qr/check-out-qr） | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id}/check-in-qr | 活动签到二维码（后端 ZXing 生成，返回 PNG `data:image/png;base64,...`；负责人展示供志愿者扫码，内容 `hengde-activity-checkin:{id}`，志愿者扫码校验后再 GPS 签到） | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id}/check-out-qr | 活动签退二维码（后端 ZXing 生成 PNG data URL；负责人展示供志愿者扫码，内容 `hengde-activity-checkout:{id}`，志愿者扫码校验后再 GPS 签退） | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/start | 点击活动开始 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/finish | 点击活动结束 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/check-outs | 统一签退（全部或指定志愿者；活动结束后 2h 内） | 需登录（活动负责人） |
| PATCH | /v/activity/managed-activities/{id}/attendances/{volunteerId} | 标记到位状态（正常/请假/迟到/缺席）或确认签到 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/attendances/{volunteerId}/violations | 记录违规（`description`=记录明细，**必填 ≤512**；`violationType` 可选 **[0其他/1~4]**、缺省 0，超范围拒；缺席=5 系统自动不可手工记） | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id}/violations | 违规记录明细（名字/记录人/记录明细/记录时间，按记录时间倒序；记录人姓名仅本活动志愿者负责人解析，管理端录入为 null 避免跨域同号错认） | 需登录（活动负责人） |
| PATCH | /v/activity/managed-activities/{id}/attendances/{volunteerId}/evaluation | 负责人评价志愿者 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/summary | 上传活动总结（文字+图片；须活动已结束） | 需登录（活动负责人） |

### 管理端 `/a/activity`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/activities | 活动列表（**默认排除待审核(4)/驳回(5)**——它们只在审核侧可见；status 可筛 1已发布/2已结束/3已取消） | 需登录 |
| POST | /a/activity/activities | 发布活动（**后台直发、直接上线 status=1，不进审核队列**；**多场次** `slots[]` 每段项目名称/起止/需求人数，活动整体起止须覆盖全部场次；**服务保障** `serviceGuarantees[]` 12 选 N[V22]；积分倍率/报名限制——`requireMinJoinCount` 已参加次数门槛、`requireMinJoinMinutes` 已参加服务时长门槛(分钟)；GPS 签到坐标 `lat`/`lng`/`checkInRadiusM` 默认500，经纬度须同填或同空） | 需登录 |
| GET | /a/activity/activities/pending-reviews | 活动发布审核列表（带提交人姓名；`status` 默认 4 待审核，传 5 看已驳回） | 需登录（activity:publish-audit） |
| GET | /a/activity/activities/{id}/review-detail | 待审/驳回活动完整详情（含驳回原因/审核人/时间；审核者看全字段无需 activity:menu；常规 `GET …/{id}` 已排除待审/驳回） | 需登录（activity:publish-audit） |
| POST | /a/activity/activities/{id}/publish-approve | 发布审核通过（活动上线 status→1） | 需登录（activity:publish-audit） |
| POST | /a/activity/activities/{id}/publish-reject | 发布审核驳回（status→5，body 可填 `reason`） | 需登录（activity:publish-audit） |
| GET | /a/activity/activities/{id} | 活动详情（回显 `lat`/`lng`/`checkInRadiusM` 等全字段） | 需登录 |
| PUT | /a/activity/activities/{id} | 修改活动（同发布入参，含 `slots[]` 多场次全量替换、`serviceGuarantees` null=保留原值/[]=清空、`requireMinJoinCount`/`requireMinJoinMinutes` 报名门槛、GPS 坐标 `lat`/`lng`/`checkInRadiusM`，经纬度须同填或同空；待审核/驳回活动不可改） | 需登录 |
| DELETE | /a/activity/activities/{id} | 删除活动（待审核/驳回活动不可删，属审核侧处置） | 需登录 |
| POST | /a/activity/activities/{id}/copy | 复制活动（**待审核/驳回活动不可复制**，否则绕开审核直接发布同内容） | 需登录 |
| GET | /a/activity/activities/{id}/enrollments | 报名列表（优先展示管理团队/临时负责人） | 需登录（activity:enroll-view） |
| GET | /a/activity/enrollments | 全局报名列表（跨活动，可 `?status=` 筛选，按报名时间倒序，每行带 `activityTitle`；概览「待审报名」计数用 `size=1` 取 `total`） | 需登录（activity:enroll-view） |
| GET | /a/activity/activities/{id}/enrollment-slots | 活动时间段列表（报名域，供手动新增报名选时间段，避免要 activity:menu；仅已发布活动，与手动新增口径一致，否则报「活动不存在」） | 需登录（activity:enroll-view） |
| POST | /a/activity/activities/{id}/enrollments | 手动新增报名（body: `volunteerId`+`slotIds`） | 需登录（activity:enroll-add） |
| GET | /a/activity/activities/{id}/enrollments/export | 导出报名名单（Excel） | 需登录（activity:enroll-export） |
| POST | /a/activity/enrollments/{id}/approve | 审核通过 | 需登录（activity:enroll-audit） |
| POST | /a/activity/enrollments/{id}/reject | 审核拒绝（body 填拒绝原因） | 需登录（activity:enroll-audit） |
| DELETE | /a/activity/enrollments/{id} | 删除报名记录 | 需登录（activity:enroll-delete） |
| POST | /a/activity/activities/{id}/leaders | 指派活动负责人（leaderType 1=志愿者负责人[本活动报名志愿者**或**管理团队志愿者，refId=volunteer.id]/2=后台账号[refId=admin_user.id]；不占人数；待审核/驳回活动不可指派） | 需登录（activity:leader-assign，组织部） |
| GET | /a/activity/activities/{id}/leaders | 负责人列表 | 需登录（activity:manage **或** activity:leader-assign，SaMode.OR） |
| DELETE | /a/activity/activities/{id}/leaders/{leaderId} | 取消指派 | 需登录（activity:leader-assign） |
| POST | /a/activity/activities/{id}/start | 活动开始（管理团队负责人） | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/finish | 活动结束 | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/check-outs | 统一签退 | 需登录（activity:manage） |
| PATCH | /a/activity/activities/{id}/attendances/{volunteerId} | 标记到位状态/确认签到 | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/attendances/{volunteerId}/violations | 记录违规 | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/summary | 上传活动总结（须活动已结束） | 需登录（activity:manage） |

### 服务记录 / 秘书部确认 / 积分 — 管理端 `/a/activity`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/service-records | 服务记录大板块（全员，可按活动/志愿者/状态筛选） | 需登录 |
| GET | /a/activity/service-records/pending | 待秘书部确认列表 | 需登录（activity:service-confirm，秘书部） |
| POST | /a/activity/attendances/{id}/confirm | 秘书部确认时长（确认后汇入服务记录大板块） | 需登录（activity:service-confirm） |
| POST | /a/activity/attendances/{id}/points | 发放积分（完成基数×倍率；违规减半/不发；**同事务写积分账本**） | 需登录（activity:points-grant） |

### 积分账本 — 管理端 `/a/activity`（V2 第 1 批，V24）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/points?volunteerId= | 查某志愿者积分总览（口径同 `/v/activity/points`） | 需登录（activity:points-view） |
| GET | /a/activity/points/records?volunteerId= | 查某志愿者积分明细（分页；`keyword` 搜索说明 + `sourceType`/`startTime`/`endTime` 筛选） | 需登录（activity:points-view） |
| POST | /a/activity/points/adjust | 管理员手工调整积分（body: `volunteerId`/`changeAmount` 正负非零/`reason` 必填/**`requestId` 幂等键必填**——前端每次打开调整弹窗生成一个 UUID，重放同一 UUID 只入账一次，**同一 UUID 若用于另一个人/另一金额/另一理由/另一操作人则报「积分入账冲突」**；目标须为已实名志愿者[停用/注销亦可调，用于纠正历史账目]；扣分不得把余额扣成负数） | 需登录（activity:points-adjust） |

### 排行榜 — 管理端 `/a/honor`（V2 第 2 批，V25+V26）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/honor/rankings | 排行榜（参数与出参同 `/v/honor/rankings`，同一份数据） | 需登录（honor:ranking-view） |
| POST | /a/honor/rankings/snapshots | 生成/补跑某周期快照（`periodType` 1月/2年，总榜不支持；`periodKey`；`force` 默认 false）。非强制时**已冻结的板块直接跳过**并在 `skipped` 中回报，本次新冻结的在 `frozen` 中回报（**判断「这次干了活没有」要看 `frozen` 而不是 `written`**——某周期无人上榜时写入 0 行，但同样是一次有效冻结）；`force=true` 会**改写已公示的历史名次**，故与查看分属两个权限点。周期未结束会被拒绝 | 需登录（honor:ranking-snapshot） |

> **定时冻结**：由 `RankingSnapshotJob` 每天跑一次（cron `hengde.honor.ranking.snapshot-cron`，默认 `0 30 0 * * ?`），
> 把**已过冷静期**的上月/上年快照补齐。冷静期 `hengde.honor.ranking.freeze-delay-days` 默认 7 天——
> 时长要等秘书部确认、积分要等发放，周期一结束就冻会把没结算完的数据定死。
> 幂等（已冻结即跳过），故漏跑一天次日自动补上。

### 勋章 — 管理端 `/a/honor`（V2 第 3 批，V28）

> **双重审核**：①**样式**审核通过后勋章才可用于发放；②每次**发放**审核通过后才对志愿者生效。
> 四个权限点各管一段，分开授权，「录入的人」与「批准的人」才可能不是同一个。
> 图标上传走 `POST /a/files/upload?dir=medal`（同样要 `honor:medal`，仅收图片）。

```
样式：录入(草稿) → 提交 → [样式审核] → 已启用 ──停用──▶ 已停用
                             ↓ 驳回 → 可改后重交        （已生效的发放不受影响）
发放：发起(待审核) → [发放审核] → 已生效（志愿者可见，附带积分此刻入账）
                        ↓ 驳回（不生效、不发分，留痕；之后可重新发起）
```

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/honor/medals | 勋章列表（`status` 0草稿/1待审核/2已启用/3已驳回/4已停用） | honor:medal |
| POST | /a/honor/medals | 新增勋章定义（落草稿；`conditionType` 0手动/1累计时长/2累计次数/3累计积分，**有阈值的条件必须填 `conditionThreshold`**；`rewardPoints` 附带积分奖励，0=不发） | honor:medal |
| PUT | /a/honor/medals/{id} | 修改定义。**改「已启用/已停用」的勋章会退回待审核**——否则可先提交素净图标过审、通过后再改成别的，样式审核就形同虚设 | honor:medal |
| PUT | /a/honor/medals/{id}/sort | 只改展示排序，**不触发重审**（排序是纯展示属性） | honor:medal |
| DELETE | /a/honor/medals/{id} | 删除定义；**已有待审/已生效发放记录的不可删**（会让志愿者的「我的勋章」出现空白项），请改用停用 | honor:medal |
| POST | /a/honor/medals/{id}/submit | 提交样式审核（草稿/已驳回 → 待审核） | honor:medal |
| POST | /a/honor/medals/{id}/approve | 样式审核通过（→ 已启用，此后方可发放） | honor:medal-audit |
| POST | /a/honor/medals/{id}/reject | 样式审核驳回（body: `reason`） | honor:medal-audit |
| POST | /a/honor/medals/{id}/disable | 停用（不可再发放；**已生效的发放记录不受影响**） | honor:medal-audit |
| POST | /a/honor/medals/{id}/enable | 重新启用（此前已过审，无需再审） | honor:medal-audit |
| GET | /a/honor/medal-grants | 发放记录列表（`status` 0待审核/1已生效/2已驳回、`volunteerId` 可筛选；带勋章名与志愿者姓名） | honor:medal-grant |
| POST | /a/honor/medal-grants | 发起发放（body: `medalId`/`volunteerId`/`reason`）。**勋章须为已启用**、志愿者须已实名且账号正常；**同一勋章不重复授予同一人**（DB 生成列唯一键，驳回后可重新发起）；**发起时快照勋章当下的 `rewardPoints`** | honor:medal-grant |
| POST | /a/honor/medal-grants/{id}/approve | 发放审核通过 → 生效。**附带积分同事务入账**（`PointSourceType.MEDAL`，按发起时快照的分值，非审核时现读）；发起后勋章若被停用则拒绝通过 | honor:medal-grant-audit |
| POST | /a/honor/medal-grants/{id}/reject | 发放审核驳回（body: `reason`）；不生效、不发分 | honor:medal-grant-audit |

### 榜样 — 管理端 `/a/honor/role-models`（V2 第 3 批，V28）

> 与公示域轮播图/公告同构：**新增落下架态**（避免还没填完图片就出现在志愿者端），上下架是独立动作。
> 榜样**不走审核**——上下架本身就是发布闸门。图片可复用 `dir=banner` 上传。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/honor/role-models | 列表（`status` 0下架/1上架可筛选） | honor:role-model |
| POST | /a/honor/role-models | 新增（落下架态） | honor:role-model |
| PUT | /a/honor/role-models/{id} | 修改（副标题/图片/链接**可传 null 清空**） | honor:role-model |
| PUT | /a/honor/role-models/{id}/status | 上架 / 下架 | honor:role-model |
| PUT | /a/honor/role-models/{id}/sort | 调整排序 | honor:role-model |
| DELETE | /a/honor/role-models/{id} | 删除 | honor:role-model |

### 考勤/积分变更二次审核 — 管理端 `/a/activity`

> 组织部修改签到/签退/积分 → **部长二次审核**通过后才生效。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /a/activity/attendances/{id}/changes | 组织部申请改签到/签退/积分（body: `changeType` 1签到时间/2签退时间/3积分、`newValue` 时间ISO或整数、`reason`；待审，不立即生效。**`changeType=3` 仅允许积分已发放（`points_status=1`）的记录**——未发放前改积分会被随后的发放重算覆盖，故组织部端在未发放时不应展示「改积分」入口） | 需登录（activity:attendance-edit，组织部） |
| GET | /a/activity/attendance-changes | 变更申请列表（`status` 0待审/1通过/2拒绝筛选；带活动/志愿者上下文） | 需登录 |
| POST | /a/activity/attendance-changes/{id}/approve | 部长二次审核通过（应用变更；改签到/签退按 签退−签到 重算时长；**改积分覆盖考勤快照并按「新值−旧值」的差额补写积分账本**，取考勤时加行锁串行化，防同一考勤的多张待审申请被并发审核而账实分离） | 需登录（activity:attendance-audit，部长） |
| POST | /a/activity/attendance-changes/{id}/reject | 部长二次审核拒绝 | 需登录（activity:attendance-audit） |

### 活动发布增强 / 留言 / 补录（V1.1 第 3 批）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/activities/{id}/messages | 活动留言列表 | 需登录 |
| POST | /v/activity/activities/{id}/messages | 发表活动留言 | 需登录 |
| GET | /a/activity/activities/{id}/messages | 管理端活动留言列表（后台详情抽屉审阅；不限发布状态含已结束/历史/草稿，但排除审核域 4/5，与 activity:menu 可见边界一致） | 需登录（activity:menu） |
| DELETE | /a/activity/messages/{id} | 删除活动留言 | 需登录（activity:manage） |
| POST | /a/activity/activities/recurring | 固定日期周期批量发布多场活动（body: `template` 活动模板 + `dates` 显式日期列表 ∪ `recurStart`/`recurEnd`/`weekdays`(1周一…7周日)周期规则；模板时刻按目标日整体平移，并集去重、上限 60 场、整批单事务） | 需登录（activity:publish） |
| POST | /a/activity/activities/historical | 发布历史活动（之前未发布过的已发生活动；置 `is_historical=1`、已结束态，志愿者端不可见，仅作补录载体） | 需登录（activity:publish） |
| POST | /a/activity/activities/{id}/backfills | 活动补录（body: `idCard`/`phone` 至少一项精确匹配志愿者 + `name` 可选交叉校验 + `slotId` 指定时间段算时长 + `reason`；普通活动得积分、历史活动只记时长；待部长审核，不立即生效；**待审核/驳回活动不可补录**——申请入口与落账前都拒） | 需登录（activity:backfill） |
| GET | /a/activity/backfills | 补录申请列表（`status` 0待审/1通过/2拒绝筛选；带活动/志愿者上下文） | 需登录 |
| POST | /a/activity/backfills/{id}/approve | 部长审核通过——**通过即终态**：同事务落一条已确认（跳秘书部确认）考勤行，普通活动按倍率发积分、历史活动只记时长 | 需登录（activity:backfill-audit） |
| POST | /a/activity/backfills/{id}/reject | 部长审核拒绝（不落账） | 需登录（activity:backfill-audit） |

---

## 组织 organization

### 志愿小组 — 志愿者端 `/v/organization/groups`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/organization/groups | 小组列表（`?keyword=` 按名称/编号搜索） | 需登录 |
| GET | /v/organization/groups/{id} | 小组详情 | 需登录 |
| POST | /v/organization/groups | 发起新小组（提交后台审核） | 需登录 |
| POST | /v/organization/groups/{id}/join | 申请加入小组 | 需登录 |
| POST | /v/organization/groups/{id}/leave | 退出小组 | 需登录 |
| GET | /v/organization/groups/{id}/members | 小组成员列表（同组内仅显示姓名/学校/电话） | 需登录 |
| GET | /v/organization/groups/{id}/join-applications | 待审核加入申请列表（仅组长/管理员可见，回 memberId 供审批） | 需登录 |
| POST | /v/organization/groups/{id}/members/{memberId}/approve | 负责人批准加入申请 | 需登录 |
| POST | /v/organization/groups/{id}/members/{memberId}/reject | 负责人拒绝加入申请 | 需登录 |
| DELETE | /v/organization/groups/{id}/members/{memberId} | 组长/管理员移除成员 | 需登录 |
| POST | /v/organization/groups/{id}/members/{memberId}/admin | 组长指定管理员（≤3 人） | 需登录 |
| DELETE | /v/organization/groups/{id}/members/{memberId}/admin | 组长取消管理员 | 需登录 |

### 归属分队 — 志愿者端 `/v/organization/squads`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/organization/squads | 分队列表 | 需登录 |
| GET | /v/organization/squads/{id} | 分队详情（未归属只看负责人信息；已归属看同分队成员） | 需登录 |
| POST | /v/organization/squads/{id}/applications | 申请加入分队 | 需登录 |

### 组织架构 — 志愿者端

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/organization/structure | 组织架构树（部门/职位/成员树形结构） | 需登录 |

### 我的权限 — 志愿者端

> **V18 打通志愿者端 RBAC**：后台给志愿者授权 → 志愿者 token 携带权限码 → `@SaCheckPermission`（默认 `login` 域）在 `/v` 端生效。**已落地的消费方**：`POST /v/activity/activities` 发布活动（PR2，需 `activity:publish`）。现场负责人管理仍走 `/v/activity/managed-activities` 的逐活动 `requireVolunteerLeader`（按「被指派」校验，与权限点并行）。前端进本接口拿权限码、据此显示/隐藏入口；**仅 UX**，动作接口由 `@SaCheckPermission` 后端兜底。权限码仅对**活跃且 `manager_flag=1`** 的志愿者返回——取消管理团队标记（降级）即时失效，不留 stale 授权（读、写口径一致）。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/organization/my-permissions | 我的权限码集合（如 `["activity:publish","activity:manage"]`） | 需登录 |

### 报名管理团队 — 志愿者端 `/v/organization/manager-applications`

> **V23**：志愿者在小程序提交「报名管理团队」问卷/简历（理由/经历/期望部门），后台审核通过即置 `volunteer.manager_flag=1`（仅标记、不自动授权限点，功能权限仍由超管在授权页单独给）。仅**正常且已实名**账号可申请；已有待审申请或已是管理团队则拒。按 volunteerId 上分布式锁防双击重复提交。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /v/organization/manager-applications | 提交报名管理团队申请（body `reason` 必填 + `experience`/`expectDepartment` 可空；游客/禁用/注销拒，已有待审或已是管理团队拒） | 需登录 |
| GET | /v/organization/manager-applications/mine | 本人最近一条申请（含状态 0待审/1通过/2驳回 + 驳回原因，供小程序回显） | 需登录 |

### 子账号与权限 — 管理端 `/a/organization/sub-accounts`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/sub-accounts | 子账号列表 | 需登录 |
| POST | /a/organization/sub-accounts | 创建子账号 | 需登录 |
| GET | /a/organization/sub-accounts/{id} | 子账号详情（含权限列表） | 需登录 |
| PUT | /a/organization/sub-accounts/{id} | 修改子账号基本信息 | 需登录 |
| DELETE | /a/organization/sub-accounts/{id} | 删除子账号 | 需登录 |
| PUT | /a/organization/sub-accounts/{id}/permissions | 全量替换权限集合 | 需登录 |
| POST | /a/organization/sub-accounts/{id}/password/reset | 重置子账号密码 | 需登录 |
| GET | /a/organization/permissions | 系统全量可分配权限列表 | 需登录 |
| GET | /a/organization/permissions/volunteer-grantable | 可授权给志愿者的权限点目录（活动域子集，除 activity:menu） | 需登录 |

### 志愿小组 — 管理端 `/a/organization/groups`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/groups | 全量小组列表 | 需登录 |
| DELETE | /a/organization/groups/{id} | 解散小组（带原因，记录 dissolve_*） | 需登录 |
| PUT | /a/organization/groups/{id}/leader | 转移组长（写入组长变更历史） | 需登录 |
| GET | /a/organization/groups/{id}/leader-history | 组长变更历史 | 需登录（org:group-manage） |
| GET | /a/organization/groups/{id}/members | 小组成员列表（转移组长选人用） | 需登录（org:group-manage） |
| POST | /a/organization/groups/import | 批量导入小组数据（Excel） | 需登录（org:group-manage） |
| GET | /a/organization/groups/applications | 建组申请列表 | 需登录 |
| POST | /a/organization/groups/applications/{id}/approve | 批准建组 | 需登录 |
| POST | /a/organization/groups/applications/{id}/reject | 拒绝建组 | 需登录 |

### 归属分队 — 管理端 `/a/organization/squads`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/squads | 分队列表 | 需登录 |
| POST | /a/organization/squads | 创建分队（含类型/负责人/人数上限） | 需登录 |
| PUT | /a/organization/squads/{id} | 修改分队信息 | 需登录 |
| DELETE | /a/organization/squads/{id} | 删除分队 | 需登录 |
| GET | /a/organization/squads/applications | **全局**待审加入申请（不按分队，默认 status=0 可传覆盖，每行带 squadName；概览/统一审批用） | 需登录 + org:squad-audit |
| GET | /a/organization/squads/{id}/applications | 某分队加入申请列表 | 需登录 |
| POST | /a/organization/squads/applications/{id}/approve | 批准加入 | 需登录 |
| POST | /a/organization/squads/applications/{id}/reject | 拒绝加入 | 需登录 |

### 志愿者管理团队标记与权限 — 管理端 `/a/organization/volunteers`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/volunteers/{id}/flag-info | 标记/授权页基础信息（姓名+管理团队标记+是否实名；不含 PII） | 需登录（org:manager-flag） |
| PUT | /a/organization/volunteers/{id}/manager-flag | 设置/取消志愿者「管理团队」标记（body `flag` 0取消/1设为；设为 1 仅限已实名、取消 0 不限；积分 ×1.2 倍率通道；记录操作人/时间） | 需登录（org:manager-flag） |
| GET | /a/organization/volunteers/{id}/permissions | 志愿者已分配的权限点（与授权写入口同超管边界） | 需登录（仅超管） |
| PUT | /a/organization/volunteers/{id}/permissions | 全量替换志愿者权限（body `permissionIds`；**仅超管**；**目标须已标记管理团队 `manager_flag=1`**[防误授普通/游客态志愿者]；只接受活动域子集白名单，非白名单点拒；传空 `permissionIds`=清空，不要求 manager_flag[便于降级后清理 stale 授权]） | 需登录（仅超管） |

### 报名管理团队审核 — 管理端 `/a/organization/manager-applications`

> **V23**：审核志愿者在小程序提交的「报名管理团队」申请。**通过即置 `volunteer.manager_flag=1`**（顺序钉死：校验仍 active+registered → setManagerFlag → CAS 待审→通过，affected≠1 整事务回滚含标记；**仅标记、不自动授权限点**，功能权限仍由超管在「子账号/权限」授权页单独给）；驳回回退并记原因。复用既有 `org:manager-flag` 权限点（与手动开关同域，不新增）。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/manager-applications | 报名管理团队申请列表（`status` 默认 0待审，可传 1已通过/2已驳回；带申请人姓名） | 需登录（org:manager-flag） |
| POST | /a/organization/manager-applications/{id}/approve | 通过申请（置 `manager_flag=1` 标记为管理团队） | 需登录（org:manager-flag） |
| POST | /a/organization/manager-applications/{id}/reject | 驳回申请（body 可填 `reason` ≤512，回退给申请人） | 需登录（org:manager-flag） |

---

## 公示 publicity

### 志愿者端 `/v/publicity`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/publicity/banners | 轮播图列表 | 需登录 |
| GET | /v/publicity/announcements | 公告列表 | 需登录 |
| GET | /v/publicity/announcements/{id} | 公告详情 | 需登录 |
| GET | /v/publicity/files | 文件下载列表（已开放下载的） | 需登录 |

### 管理端 `/a/publicity`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/publicity/banners | 轮播图列表 | 需登录 |
| POST | /a/publicity/banners | 新增轮播图（含图片裁剪/跳转链接） | 需登录 |
| PUT | /a/publicity/banners/{id} | 修改轮播图 | 需登录 |
| DELETE | /a/publicity/banners/{id} | 删除轮播图 | 需登录 |
| PATCH | /a/publicity/banners/{id}/sort | 调整排序权重（body: `{"sort": 1}`） | 需登录 |
| GET | /a/publicity/announcements | 公告列表 | 需登录 |
| POST | /a/publicity/announcements | 新增公告（支持插图/跳转推文/小程序） | 需登录 |
| PUT | /a/publicity/announcements/{id} | 修改公告 | 需登录 |
| DELETE | /a/publicity/announcements/{id} | 删除公告 | 需登录 |
| GET | /a/publicity/files | 全量文件列表 | 需登录 |
| POST | /a/publicity/files | 上传文件 | 需登录 |
| DELETE | /a/publicity/files/{id} | 删除文件 | 需登录 |
| PATCH | /a/publicity/files/{id}/access | 开放/关闭志愿者端下载（body: `{"downloadable": true}`） | 需登录 |

---

## 数据看板 data

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/data/dashboard | 首页数据看板（注册志愿者人数/活动场次/时长/参与人次/管理团队人数/分队数量） | 需登录 |
| GET | /a/data/dashboard | 后台数据概览看板 | 需登录 |

---

## V1 暂缓接口（路径已预留，本版本不实现）

| URL 示例 | 对应功能 | 暂缓原因 |
|---|---|---|
| GET /v/activity/activities/{id}/roster | 名单公示 | 签到/时长/积分闭环已纳入 V1.1，但「名单公示」展示页仍暂缓 |
| GET /v/honor/** 其余 | 奖惩中心 | V1 暂缓；**排行榜（V2 第 2 批）、勋章与榜样（V2 第 3 批）已实现，见上方 `/v/honor/*`** |
| GET /v/social/** | 社区（帖子/私信/互动） | V1 暂缓 |
| POST /v/activity/activities/{id}/photos | 活动相册（上传照片+评论，默认发交流平台） | 依赖社区(social)，推迟到 social 落地一起做 |
| GET /v/donate/** | 积分兑换/众筹/捐书/微心愿/助学结对 | V1 暂缓 |
| /a/organization/manager-applications/** | 报名管理团队**批量下载/导出** + **动态问卷构建器**（单选/多选/文件上传等自定义题型） | V1.1 预留；**固定表单的申请+审核已实现（V23，见上方组织域）**，仅导出与动态问卷未建 |
| GET /v/organization/exams/** | 活动临时负责人考试（达分获资格/主观题人工审核/历史考试/评价过低组织部审核取消） | 已于 **V4 临时负责人考试批**落地，见 [`文档/v4/url文档v4.md`](../v4/url文档v4.md) |
| GET /e/** | 爱心企业端全部接口 | V1 暂缓 |
