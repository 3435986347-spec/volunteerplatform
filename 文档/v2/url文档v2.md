# 接口 URL 约束文档 V2（全量）

> **本文件是全部已实现端点的唯一全量清单**，涵盖 V1 → V1.1 → V2 第 1~3 批。
> **`文档/v1/url文档v1.md` 已冻结为 V1/V1.1 的历史快照，不再随代码更新**；写 Controller、对前端讲权限，一律以本文件为准。
>
> **核对口径（2026-07-29 复核）**：与 `代码/` 下全部 44 个 `*Controller.java` 做**双向差集 + 鉴权逐条比对**。
> 实测**代码 205 个唯一端点、本文档 206 行**：代码中存在而文档缺失的为 **0**；文档多出的 1 条是 `/v/user/volunteer-card`（路径预留，表内已标「⬜ 未实现」）。
> `/v/home` **有 Controller 映射、算在 205 内**，只是聚合逻辑为 TODO，故标注为「控制器为占位」而非「未实现」。
>
> ⚠️ **抽取端点时勿只匹配带参数的映射注解**——本项目有 21 处无参数的 `@GetMapping` / `@PostMapping`（路径全在类级 `@RequestMapping` 上），
> 漏掉它们会得出「184 个端点」这类偏低的数字。同理，权限点多写作 `PermissionCode.XXX` 常量而非字面量，
> 比对鉴权时必须先解析常量，否则会把 `type = "admin"` 误当成权限值。
>
> **鉴权列即权限契约**：写「需登录（xxx:yyy）」表示该端点带 `@SaCheckPermission("xxx:yyy")`，
> 只写「需登录」表示代码中确无权限点注解。前端据此决定入口显隐，两者不一致会直接导致误放行或线上 403。
>
> **已规划、尚未开工的端点不在本文件内**：第 4 批（证书 / 纸质证书与微信支付 / i志愿证书）的完整端点表
> 定义在 [`V2规划.md` 第 4 批「接口」一节](V2规划.md)，**实现时须整表逐行转录到此处**（含鉴权列），
> 转录完 `tools/verify_url_contract.py` 才会绿。在此登记是为了让本文件的读者知道**这批路径已被占用**，
> 避免另起冲突的命名——而不是把未实现的东西混进「已实现清单」。
>
> **核对已脚本化，不要再手工比对**——上面那两条「勿只匹配带参数注解」「须先解析权限常量」的坑，都是手工比对踩出来的：
>
> ```bash
> python tools/verify_url_contract.py             # 端点差集 / 权限与 AND-OR / 重复行 / 未标注的文档独有项
> python tools/verify_url_contract.py --selftest  # 只跑解析器回归用例，不读仓库
> ```
>
> 任一类不一致即**非 0 退出**，可直接挂进 CI 或提交前钩子；改动 Controller 或本文件后请跑一次。
> 上面那组 205 / 206 就是它算出来的。

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
| GET | /v/home | 首页聚合（轮播图 + 数据看板 + 推荐活动）—— **⬜ 控制器为占位，聚合逻辑未实现**；小程序当前分别调 `/v/publicity/banners`、`/v/data/dashboard`、`/v/activity/activities` | 需登录 |
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
| POST | /v/auth/login/sms | **手机号+验证码登录**（`{phone, smsCode}`，scene=login）；陌生手机号自动建游客账号（之后再实名）；**注销拒登，禁用照发 token**（协会 2026-08-11 第 5 条「只给禁用账号开个小口子」，token 拿到后仅 `BannedAccountGate.EXEMPT_PATHS` 五条可用，其余 `/v/**` 一律 403） | 公开 |
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
| POST | /a/files/upload | 通用文件/图片上传（multipart `file` + **`dir` 必传**：banner/announcement/activity/summary/file/**medal**，未知 dir 拒绝），返回 `{url,name,size}`；业务表只存 url。**按 dir 双门槛**：权限(banner→pub:banner / announcement→pub:announcement / file→pub:file / activity→activity:publish或edit / summary→activity:manage / **medal→honor:medal**)+ 类型(图片目录仅图片、file 目录收文档) | 需登录 + 对应 dir 权限 |

---

## 用户 user

### 志愿者端 `/v/user`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/user/profile | 获取本人完整资料（`no` 编号[=志愿者 id 字符串，「我的」页顶部展示]/姓名/昵称/手机号/身份证完整号[本人查看自己]/政治面貌/学校/年级/地址/紧急联系方式 + 时长/积分/参与活动数/所在小组/归属分队）；游客也可取（实名字段为空、`registered:false`） | 需登录 |
| PATCH | /v/user/profile | 更新可修改项（头像/i志愿者码/昵称[全局唯一去重]/学校/年级/政治面貌/通讯地址/紧急联系方式，**部分更新**仅传非空字段）；**手机号走 `PUT /v/user/phone`**；姓名/身份证「不可修改」（实名字段仅后台超管 `PUT /a/user/volunteers/{id}` 可改） | 需登录 |
| PUT | /v/user/phone | 修改/换绑手机号（`{phone, smsCode}`，新号需 scene=change-phone 短信验证；查重不可撞其它账号；账号=手机号，同步改 phone 密文+phoneHash） | 需登录 |
| GET | /v/user/volunteer-card | 获取电子志愿者证（类身份证样式，含内嵌小程序码）—— **⬜ 未实现**（路径预留，见 `功能清单.md`「志愿者证」） | 需登录 |

### 管理端 `/a/user`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/user/volunteers | 志愿者列表（`?keyword=&gender=&squad=&political=&school=&grade=&managerFlag=&page=&size=`；keyword 纯数字按手机号 HMAC 精确，否则姓名/学校模糊；`managerFlag=1` 只出管理团队志愿者[负责人选人用]；仅返回已实名志愿者） | `user:list` |
| GET | /a/user/volunteers/{id} | 志愿者详情（含明文手机号、身份证尾号；仅已实名） | `user:list` |
| PUT | /a/user/volunteers/{id} | 修改志愿者全量信息（实名敏感字段，全量 PUT 可清空字段） | **仅超管**（`user:edit`，不入权限点表、不可分配，service 手写 `is_super_admin` 校验） |
| PATCH | /a/user/volunteers/{id}/status | 暂停/恢复志愿者账号（body: `{"status": 0/1}`，仅 0正常/1禁用）。⚠️ **禁用不再等于「登不进来」**：他仍能登录，但只能访问退出登录 / 奖惩记录 / 申诉 / 处置查看 / 站内提示这五条 | `user:status` |
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
| POST | /v/files/appeal-image | **申诉凭证图片上传**（multipart `file`，无 `dir` 参数，限图片），返回 `{url,name,size}`；URL 随 `POST /v/honor/reward-punishes/{id}/appeal` 的 `imageUrls` 一起提交（V45）。**单开一条路径而不是并进上面那个 `dir` 白名单**——它必须被 `DenyAllUseGate`（被判「拒绝使用本程序」的人）与 `BannedAccountGate`（被禁用账号）放行，而那两条清单按路径写：挂在 `/profile-image` 上，要么这两类人**申诉提得出去却举不了证**，要么为放行它把整个 `/profile-image` 开出去、顺带让被禁用账号能改头像换 i志愿者码 | 需登录（含被禁用 / 被判「拒绝使用」者） |

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
| GET | /v/activity/my-activities | 我的活动——**V30 起按场次列出**（一人报同一活动两场就返回两条，各带 `slotId`/`slotProjectName`/场次起止）。旧版按活动聚合，多场次时后一条会静默覆盖前一条 | 需登录 |
| GET | /v/activity/my-activities/{id} | 我的活动详情，**`?slotId=` 必传**（含该场次考勤 + 签到二维码数据 + 紧急上报电话 `emergencyPhone`）。**V30：考勤按场次**，不传 slotId 就无法确定是哪一场 | 需登录 |
| POST | /v/activity/activities/{id}/check-in | 自助签到（扫负责人签到码后调；body: **`slotId` 必填** + lat/lng/method；GPS 距活动 ≤ 签到半径 且在**该场次**时间窗口内）。**V30：考勤按场次**——一人报几场就分别签几次，且只能签自己报名的那一场 | 需登录 |
| POST | /v/activity/activities/{id}/check-out | 自助签退（扫负责人签退码后调；body: **`slotId` 必填** + lat/lng/method；GPS ≤ 签退半径 且**该场次**结束后 2h 内；算服务时长=签退−签到；签退坐标仅校验不留存）。**V30：按场次** | 需登录 |
| POST | /v/activity/activities/{id}/confirm-home | 确认到家（body: **`slotId` 必填** + lat/lng；**该场次**结束后可点；超时仅记录不拒绝）。**V30：按场次**——上午场的人不必等整个活动散场 | 需登录 |
| POST | /v/activity/activities/{id}/review | 评价活动与负责人（body: **`slotId` 必填** + 活动评分1~5/负责人评分1~5/评论；须该场次实际签到、**该场次**结束后；可覆盖）。**V30：按场次** | 需登录 |
| GET | /v/activity/service-records | 我的服务记录（活动名称/签到/签退/时长）。**V30 起一行=一个场次**，另带 `slotId`/`slotProjectName`/场次起止以区分同一活动的多条 | 需登录 |

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
| GET | /v/honor/medals | 我的勋章：返回**全部已启用勋章 ∪ 本人已获得的勋章** + `owned` 是否已获得 + `grantTime` + **获取进度**（`currentValue`/`progressPercent`，百分比封顶 100）。并集是为了让**样式停用后已生效的发放仍然可见**——停用的语义是「不再发新的」而非「收回已发的」；未获得者看不到已停用的勋章。进度对「有阈值」的条件即时计算，与排行榜同口径：时长取**已发布/已结束活动上、有签到、且秘书部已确认**的分钟数之和、次数取同范围的签到考勤条数（草稿/待审/已取消活动上的脏考勤与无签到行都不计）、积分取**累计获得**（非余额，花掉积分不该丢进度）；手动授予类无进度，相关字段为 null。**志愿者 id 取自登录态，不接受入参** | 需登录 |
| GET | /v/honor/role-models | 榜样列表（**仅已上架**）。**V43 起分页**：`page`/`size` + `keyword`（匹配标题/副标题/**简介**）+ `modelType`（1个人/2团队）+ `sort`（`default` 按运营排序，默认；`latest` 按发布时间倒序）。⚠️ **响应从裸数组改为 `{records,total,page,size,pages}`，是破坏性改动**。行上带 `modelType`/`modelTypeLabel`/`summary`/`publishTime`，以及 `linkType`（0不跳转/1小程序页面/2网页WebView/3外部链接仅复制）+ **`linkTypeName`**（`NONE`/`PAGE`/`WEB`/`EXTERNAL`，直接 switch 它，别在客户端自己维护「1 是什么」的映射）。**未知的 `sort` 直接报错**，不悄悄按默认排——拼错一个值得到另一种顺序且毫无提示，是最难查的那类问题 | 需登录 |

### 我的证书 — 志愿者端 `/v/honor`（V2 第 4 批·电子证书核心，V31）

> **没有「申请生成」这个动作**——xlsx Row 36 原文是「参加完活动后，**自动生成**一个盖章的电子证书」，
> 改成「申请后才有」属未经产品决策改动用户流程。故志愿者端只有列表与下载。
> 落地方式是**权益自动创建 + PDF 懒渲染**：秘书部确认考勤那一刻就写入证书行（志愿者当即看到条目），
> PDF 到首次预览/下载才渲染。用户视角是「参加完就有」，系统不为从不下载的人白跑渲染。
>
> **一场活动一个证书**（协会 2026-07-30），幂等键 `uk_slot_cert(type, volunteer_id, slot_id)`。
> **志愿者 id 一律取自登录态、不接受入参。**
> 🛑 纸质申请（`/v/honor/paper-applies*`）与 i志愿证书（`/v/honor/ivol-applies*`）**不在本批**，见 `V2规划.md` 范围声明。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/honor/certificates | 我的证书列表（软删的不返回）。**V43 起分页**（`page`/`size`，响应改为 `{records,total,…}`，破坏性改动）。每行带 `certNo`/活动/**场次(`slotId`/`slotProjectName`/岗位起止)**/`downloadCount`/`fileReady`。**不返回 `fileKey`**——前端只需知道能否下载，取文件走下面的接口 | 需登录 |
| GET | /v/honor/certificates/{id}/file | 预览/下载：校验归属 → **文件缺失时懒渲染并回填** → **计数即闸门**（`UPDATE … WHERE is_deleted=0` 影响 0 行即拒发，堵住「渲染期间证书被撤销」的竞态；被拒的请求不计数）→ 返回**短期签名 URL**（默认 120s，受 `hengde.oss.presign-max-ttl-seconds` 二次约束）。渲染按 certificate_id 加分布式锁 + `WHERE file_key IS NULL` 的 CAS 回填，并发下不会渲染两份也不会覆盖。**渲染把协会电子样本 PDF 当底图套印**——公章在样本上；**未配置样本、或样本文件读不出，一律拒绝生成**（不退化成一张无章的证书）。归属不符与不存在**返回同一句话**，避免枚举他人证书 | 需登录 |

### 活动现场负责人 — 志愿者端 `/v/activity/managed-activities`

> 仅活动的**已指派负责人（志愿者）**可访问；管理团队负责人走 `/a/activity`（下表对应动作）。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/managed-activities | 我负责的活动场次列表 | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id} | 负责详情（志愿者名单含名字/电话/学校 + 紧急上报电话 `emergencyPhone`；名单 roster 即「签到记录」数据：签到/签退时间、到位状态、违规数。二维码不在此，见下 check-in-qr/check-out-qr）。**V30：roster 一行 = 一个「志愿者 × 场次」**，同一人报两场出两行、各带 `slotId`/`slotProjectName`/场次起止，按场次开始时间排序；违规数也按场次计。旧版一人一行，多场次时静默只留一场 | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id}/check-in-qr | 活动签到二维码（后端 ZXing 生成，返回 PNG `data:image/png;base64,...`；负责人展示供志愿者扫码，内容 `hengde-activity-checkin:{id}`，志愿者扫码校验后再 GPS 签到） | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id}/check-out-qr | 活动签退二维码（后端 ZXing 生成 PNG data URL；负责人展示供志愿者扫码，内容 `hengde-activity-checkout:{id}`，志愿者扫码校验后再 GPS 签退） | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/start | 点击活动开始 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/finish | 点击活动结束 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/check-outs | 统一签退（body **必传**：`slotId` 必填 + `volunteerIds` 可选，缺省=该场次全部；**该场次**结束后 2h 内）。**V30：按场次**——请求体不再可省，省略会因缺 `slotId` 被拒 | 需登录（活动负责人） |
| PATCH | /v/activity/managed-activities/{id}/attendances/{volunteerId} | 标记到位状态（正常/请假/迟到/缺席）或确认签到（body: **`slotId` 必填**）。**V30：按场次**——同一人上下午两场各自标 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/attendances/{volunteerId}/violations | 记录违规（body: **`slotId` 必填**；`description`=记录明细，**必填 ≤512**；`violationType` 可选 **[0其他/1~4]**、缺省 0，超范围拒；缺席=5 系统自动不可手工记）。**V30：违规记在场次上** | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id}/violations | 违规记录明细（名字/记录人/记录明细/记录时间，按记录时间倒序；记录人姓名仅本活动志愿者负责人解析，管理端录入为 null 避免跨域同号错认）。**V30：每行带 `slotId`/`slotProjectName`/场次起止**，否则多场次活动里同名同类型的两条无法区分 | 需登录（活动负责人） |
| PATCH | /v/activity/managed-activities/{id}/attendances/{volunteerId}/evaluation | 负责人评价志愿者（body: **`slotId` 必填**）。**V30：按场次** | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/summary | 上传活动总结（文字+图片；须活动已结束） | 需登录（活动负责人） |

### 管理端 `/a/activity`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/activities | 活动列表（**默认排除待审核(4)/驳回(5)**——它们只在审核侧可见；status 可筛 1已发布/2已结束/3已取消） | 需登录（activity:menu） |
| POST | /a/activity/activities | 发布活动（**后台直发、直接上线 status=1，不进审核队列**；**多场次** `slots[]` 每段项目名称/起止/需求人数，活动整体起止须覆盖全部场次；**服务保障** `serviceGuarantees[]` 12 选 N[V22]；积分倍率/报名限制——`requireMinJoinCount` 已参加次数门槛、`requireMinJoinMinutes` 已参加服务时长门槛(分钟)；GPS 签到坐标 `lat`/`lng`/`checkInRadiusM` 默认500，经纬度须同填或同空） | 需登录（activity:publish） |
| GET | /a/activity/activities/pending-reviews | 活动发布审核列表（带提交人姓名；`status` 默认 4 待审核，传 5 看已驳回） | 需登录（activity:publish-audit） |
| GET | /a/activity/activities/{id}/review-detail | 待审/驳回活动完整详情（含驳回原因/审核人/时间；审核者看全字段无需 activity:menu；常规 `GET …/{id}` 已排除待审/驳回） | 需登录（activity:publish-audit） |
| POST | /a/activity/activities/{id}/publish-approve | 发布审核通过（活动上线 status→1） | 需登录（activity:publish-audit） |
| POST | /a/activity/activities/{id}/publish-reject | 发布审核驳回（status→5，body 可填 `reason`） | 需登录（activity:publish-audit） |
| GET | /a/activity/activities/{id} | 活动详情（回显 `lat`/`lng`/`checkInRadiusM` 等全字段） | 需登录（activity:menu） |
| PUT | /a/activity/activities/{id} | 修改活动（同发布入参，含 `slots[]` 多场次全量替换、`serviceGuarantees` null=保留原值/[]=清空、`requireMinJoinCount`/`requireMinJoinMinutes` 报名门槛、GPS 坐标 `lat`/`lng`/`checkInRadiusM`，经纬度须同填或同空；待审核/驳回活动不可改） | 需登录（activity:edit） |
| DELETE | /a/activity/activities/{id} | 删除活动（待审核/驳回活动不可删，属审核侧处置） | 需登录（activity:delete） |
| POST | /a/activity/activities/{id}/cancel | **取消活动**（已有报名记录时用；保留报名与考勤数据，与删除的区别在于留痕可查）。**可选查询参数 `reason`**：会进「活动已取消」短信正文，发给全部有效报名者（待审核+已通过，按人去重）；留空时正文回落到「详情请咨询活动联系人」——建议后台补一个必填的原因输入框 | 需登录（activity:delete） |
| POST | /a/activity/activities/{id}/copy | 复制活动（**待审核/驳回活动不可复制**，否则绕开审核直接发布同内容） | 需登录（activity:publish） |
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
| POST | /a/activity/activities/{id}/check-outs | 统一签退（body **必传**：`slotId` 必填 + `volunteerIds` 可选）。**V30：按场次**，请求体不再可省 | 需登录（activity:manage） |
| PATCH | /a/activity/activities/{id}/attendances/{volunteerId} | 标记到位状态/确认签到（body: **`slotId` 必填**）。**V30：按场次** | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/attendances/{volunteerId}/violations | 记录违规（body: **`slotId` 必填**）。**V30：按场次** | 需登录（activity:manage） |
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
| POST | /a/activity/points/adjust | 管理员手工调整积分（body: `volunteerId`/`changeAmount` 正负非零/`reason` 必填/**`requestId` 幂等键必填**——前端每次打开调整弹窗生成一个 UUID，**只收 `[A-Za-z0-9:._-]`、最长 64**（幂等键的「相等」在库里由排序规则决定、在 Java 里由码点决定，限死字符集才能让两者重合，见 V33），重放同一 UUID 只入账一次，**同一 UUID 若用于另一个人/另一金额/另一理由/另一操作人则报「积分入账冲突」**；目标须为已实名志愿者[停用/注销亦可调，用于纠正历史账目]；扣分不得把余额扣成负数） | 需登录（activity:points-adjust） |

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
> 四个权限点各管一段，分开授权，「录入的人」与「批准的人」才**可能**不是同一个。
> **注意这只是「可以分开」，不是「系统禁止自审」**——同一账号可被同时授予两个点，超管更是通配 `*` 全有；
> 真要强制双人复核需另加创建人字段并校验 `reviewBy != createBy`，本批未做，属协会管理制度范畴。
> 读列表放宽为「管理权 **或** 对应审核权」任一即可：只有审核权却读不到列表，就只能拿着 id 盲审。
> 图标上传走 `POST /a/files/upload?dir=medal`（同样要 `honor:medal`，仅收图片）。

```
样式：录入(草稿) → 提交 → [样式审核] → 已启用 ──停用──▶ 已停用
                             ↓ 驳回 → 可改后重交        （已生效的发放不受影响）
发放：发起(待审核) → [发放审核] → 已生效（志愿者可见，附带积分此刻入账）
                        ↓ 驳回（不生效、不发分，留痕；之后可重新发起）
```

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/honor/medals | 勋章列表（`status` 0草稿/1待审核/2已启用/3已驳回/4已停用） | honor:medal **或** honor:medal-audit（SaMode.OR） |
| POST | /a/honor/medals | 新增勋章定义（落草稿；`conditionType` 0手动/1累计时长/2累计次数/3累计积分，**有阈值的条件必须填 `conditionThreshold`**；`rewardPoints` 附带积分奖励，0=不发） | honor:medal |
| PUT | /a/honor/medals/{id} | 修改定义。**改「已启用/已停用」的勋章会退回待审核**——否则可先提交素净图标过审、通过后再改成别的，样式审核就形同虚设。**「待审核」状态一律拒绝修改**（否则审核人批准的就不是他看过的那一版），该限制写在 UPDATE 的 WHERE 里，并发下也不会被「改先完成、审核后完成」绕过 | honor:medal |
| PUT | /a/honor/medals/{id}/sort | 只改展示排序，**不触发重审**（排序是纯展示属性）。只发一条 `SET sort` 的更新，不整行写回——否则会把并发写入的重审状态覆盖掉 | honor:medal |
| DELETE | /a/honor/medals/{id} | 删除定义；**已有待审/已生效发放记录的不可删**（会让志愿者的「我的勋章」出现空白项），请改用停用。与「发起发放」互斥加锁，不会并发产生指向已删勋章的孤儿记录 | honor:medal |
| POST | /a/honor/medals/{id}/submit | 提交样式审核（草稿/已驳回 → 待审核） | honor:medal |
| POST | /a/honor/medals/{id}/approve | 样式审核通过（→ 已启用，此后方可发放） | honor:medal-audit |
| POST | /a/honor/medals/{id}/reject | 样式审核驳回（body: `reason`） | honor:medal-audit |
| POST | /a/honor/medals/{id}/disable | 停用（不可再发放；**已生效的发放记录不受影响**） | honor:medal-audit |
| POST | /a/honor/medals/{id}/enable | 重新启用（此前已过审，无需再审） | honor:medal-audit |
| GET | /a/honor/medal-grants | 发放记录列表（`status` 0待审核/1已生效/2已驳回、`volunteerId` 可筛选；带勋章名与志愿者姓名） | honor:medal-grant **或** honor:medal-grant-audit（SaMode.OR） |
| POST | /a/honor/medal-grants | 发起发放（body: `medalId`/`volunteerId`/`reason`）。**勋章须为已启用**、志愿者须已实名且账号正常；**同一勋章不重复授予同一人**（DB 生成列唯一键，驳回后可重新发起）；**发起时快照勋章当下的 `rewardPoints`** | honor:medal-grant |
| POST | /a/honor/medal-grants/{id}/approve | 发放审核通过 → 生效。**附带积分同事务入账**（`PointSourceType.MEDAL`，按发起时快照的分值，非审核时现读）；发起后勋章若被停用则拒绝通过（对勋章行**当前读加共享锁**，审核期间的并发停用也拦得住，不会读到过期的「已启用」） | honor:medal-grant-audit |
| POST | /a/honor/medal-grants/{id}/reject | 发放审核驳回（body: `reason`）；不生效、不发分 | honor:medal-grant-audit |

### 榜样 — 管理端 `/a/honor/role-models`（V2 第 3 批，V28）

> 与公示域轮播图/公告同构：**新增落下架态**（避免还没填完图片就出现在志愿者端），上下架是独立动作。
> 榜样**不走审核**——上下架本身就是发布闸门。图片可复用 `dir=banner` 上传。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/honor/role-models | 列表（`status` 0下架/1上架可筛选） | honor:role-model |
| POST | /a/honor/role-models | 新增（落下架态） | honor:role-model |
| PUT | /a/honor/role-models/{id} | 修改（副标题/图片/简介**可传 null 清空**）。**V43 起 `linkType` 与 `linkUrl` 一起校验**：选了跳转必须给链接；`linkType=2`(WEB) 必须是 https（微信业务域名不收 http，http 在体验版能开、正式版白屏）；**选了「不跳转」却又填链接直接报错、不静默清空**（静默清空是最糟的——填了地址、保存也提示成功，链接却没了）。⚠️ **「域名是否已备案」后端判不了**，备案清单不在代码里，最终裁判是小程序后台配的业务域名白名单 | honor:role-model |
| PUT | /a/honor/role-models/{id}/status | 上架 / 下架。**首次上架时写 `publish_time`**，下架不清、再次上架不覆盖——「发布时间」是这条内容第一次与志愿者见面的时刻，临时下架改个错别字再上架不该让它在小程序「最新」里跳到最前 | honor:role-model |
| PUT | /a/honor/role-models/{id}/sort | 调整排序 | honor:role-model |
| DELETE | /a/honor/role-models/{id} | 删除 | honor:role-model |

### 证书 — 管理端 `/a/honor`（V2 第 4 批·电子证书核心，V31）

> 对应 xlsx Row 36 F 列的后台能力。**权限点两分**：查询/上传 `honor:certificate`，
> 删除/恢复 `honor:certificate-delete`——删除会让志愿者手里的证书凭空消失，属高危操作，
> 沿用 V24「查看与调整分开」的口径。
>
> 🛑 F 列第 ④ 项「批量设置可申请纸质证书时间」随纸质路径整体冻结，本批无对应端点；
> 权限点 `honor:paper-apply` / `honor:paper-apply-pii` 同样不落地。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/honor/certificates | 证书汇总查询（分页，可按 `volunteerId`/`activityId` 筛选；带志愿者姓名与活动/场次）。**`includeDeleted=true` 可连已软删的一起返回**（行上带 `deleted`/`deletedReason`/`deletedTime`）——缺了它删完就再也找不回 id，下面的「撤销删除」将无从调用 | honor:certificate |
| POST | /a/honor/certificates/batch | **批量上传 PDF 证书**（Row 36 F ①）。`activityId` + `slotId` 指定本批归属（一场活动一个证书），`files` 多文件。**文件 ↔ 志愿者按文件名里的 11 位手机号匹配**（`13800138000.pdf` 或 `13800138000_张三.pdf`），**不按姓名——重名必错配**；手机号换 id 走 auth 窄接口，honor 不接触 PII 密文。**该志愿者必须在这一场次有「秘书部已确认」的参加记录**，否则该行失败——手机号写成另一个真实用户时，绝不能凭空给他发一张能下载的证书。**逐条返回成败明细，单条匹配不上不整批失败**。已存在的证书会被复用并挂上文件（落 `certificate/upload/` 前缀，**与系统渲染件不同 key，互不覆盖**），来源改为「后台上传」，此后不再参与懒渲染 | honor:certificate |
| GET | /a/honor/certificates/{id}/file | 后台下载（同样只返回短期签名 URL） | honor:certificate |
| DELETE | /a/honor/certificates/{id} | **软删**指定某人证书（Row 36 F ②），body `reason` **必填**，记删除人/时间/原因 | honor:certificate-delete |
| POST | /a/honor/certificates/{id}/restore | 撤销软删。与「重跑时恢复原记录」口径配套的显式入口：软删行**仍占用** `uk_slot_cert`，重新触发自动创建会**复活原行并保留原编号与文件**，而不是另发新编号 | honor:certificate-delete |
| POST | /a/honor/certificates/reconcile | **人工补发缺失的证书**，回 `{created, failed, hasMore}`。⚠️ **「真的补完了」的判据是 `hasMore=false` 且 `failed=0`**，不是只看前者：补发逐条 catch（单条坏数据不能挡住后面所有人），只报 `created` 的话失败就只剩一行没人读的日志，而接口等于告诉管理员「补完了」。`since`（只补该时刻之后被秘书部确认的考勤）/ `activityId`（只补该活动），**两者至少给一个，都不给直接报错**。<br>⚠️ `since` **须用 ISO 的 `T` 分隔**（`2026-07-01T00:00:00`）——而**所有响应里的时间是空格格式**，从响应复制粘过来会 400。这是项目既有惯例（同 `/a/activity/points`），不是本接口的特例。<br>**为什么必须有**：自动补偿扫描只覆盖回看窗口（默认 72h），窗口外是有洞的——「事件丢失 + 应用停机超过窗口」叠加时证书永久缺失且无人发现，本接口是唯一救济。<br>**为什么不接受无边界全量**：那等于让一次调用替协会答复「改造前的历史活动是否补发」（见 `协会待确认清单.md` 1-追）。⚠️ 但要看清这道守卫的作用范围——**它防的是误触、不是规模**：`since=1970-01-01T00:00:00` 同样通过、效果与全量一致，这是刻意允许的（人显式敲下 1970 是他的决定，cron 不声不响地发不是）。<br>**单次封顶**（`reconcile-max-creates-per-run`，默认 1000）：本接口同步执行而网关 `proxy_read_timeout` 是 60s，一次几千张会在网关 504、服务端却还在跑，管理员看到失败又点一次就两轮重叠白烧。补发幂等，故正确用法是**重复调用直到 `hasMore=false`**；不引入异步任务与任务 id，那买不到比「再点一次」更多的东西 | honor:certificate |

### 证书电子样本 — 管理端 `/a/honor/certificate-templates`（V2 第 4 批，V31）

> Row 36 F 列第 ③ 项「**设置某个活动的电子样本**」——模板绑**活动**，不是全局一套。
> 作用域用显式键 `scope_key`：按活动为 `activity:{id}`、全局默认为固定串 `global`，`uk_template_scope(scope_key)`。
> **不用可空的 `activity_id` 表达「全局」**——MySQL 唯一索引视多个 NULL 互不相同，会放进任意多条全局默认。
> 取样本时**先找本活动的，没有再退回全局**；两级都没有则拒绝生成证书。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/honor/certificate-templates | 电子样本列表（带从 `scopeKey` 解析出的 `activityId`） | honor:certificate-template |
| POST | /a/honor/certificate-templates/file | **上传样本 PDF，返回可填进 `fileKey` 的私有对象 key**（≤20MB）。**必须单开这个入口**——既有的 `/a/files/upload` 会按 `public-read` 打公共读 ACL 并返回 URL，产不出这里要的私有 key；没有它管理员根本造不出合法 `fileKey`，样本管理与证书生成整条链路都走不通 | honor:certificate-template |
| POST | /a/honor/certificate-templates | 新增（传 `activityId`=按活动，不传=全局默认）。**不收前端直接传的 `scopeKey`**，由服务端组装，避免造出永远匹配不上的作用域。同一作用域重复新增被拒——覆盖会让此前按该样本发的证书与当前配置对不上且无痕迹。**`fileKey` 必须是上一行那个上传接口返回的 key（以 `certificate-template/` 开头）**，否则报错：与不收 `scopeKey` 同一理由，随手写的字符串要等到那个活动第一次出证才报「文件读取失败」，而那时是志愿者在点下载 | honor:certificate-template |
| PUT | /a/honor/certificate-templates/{id} | 修改（**作用域不可改**：传了与现存不一致的 `activityId` 会**明确报错**而非静默忽略——静默忽略会让管理员以为「已经把样本挪到另一个活动了」，而错要等到那个活动发不出证书才暴露）。`fileKey` 同样只收上传接口产出的 key。**`layout` 传 null 会真的清空**（不是「不变」）；样本在修改期间被并发删除时返回「已被删除，本次修改未生效」而**不报成功** | honor:certificate-template |
| DELETE | /a/honor/certificate-templates/{id} | 删除 | honor:certificate-template |

### 活动违规审核 — 管理端 `/a/activity/violations`（V2 第 5 批，V32）

> 出处：xlsx **Row 59** 后台首页待办里单列的「**活动违规审核**」；**Row 41 F**「各类违规记录和奖励均需**组织部同学审核才可显示**」。
> **为什么违规记录要单独过一道审**：现场记录是负责人的**工作底稿**——他在活动现场凭观察点几下就落库了。未经核实直接呈现给被记的那个人，等于把一面之词当成定论。
> 通过之后它才对志愿者可见（`MyActivityVO.violationCount` 只计已通过的），也才够格作为开一张处罚单的依据。
> ⚠️ **存量违规一律是「待审核」**：V32 刻意不把历史数据伪造成「已通过」（那样 `reviewed_by` 只能是 NULL，库里会出现「已通过但没有审核人」的自相矛盾行）。代价是存量违规在组织部逐条处理前不再对志愿者显示，而它们会全部出现在下面这个队列里。
> **Row 59 那个待办数字**用本队列 `reviewStatus=0&size=1` 的 `total`，**不另开看板字段**——同一个数字两个出处迟早对不上（`DashboardVO` 的既有决定也是这条）。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/violations | 违规审核队列（分页）。`reviewStatus` 缺省 **0 待审核**（待办队列的默认视图就是还没处理的），可传 1/2 查已通过/已驳回；`activityId` 可选。行上带活动名与场次，否则审的人不知道是哪一场 | activity:violation-review |
| POST | /a/activity/violations/{id}/approve | 审核通过。**CAS：只有仍待审核的行可被裁决**——两人同时点，后一个必须落空而不是覆盖前一个的结论与审核人 | activity:violation-review |
| POST | /a/activity/violations/{id}/reject | 驳回，body `reason` **必填**——驳回等于否定负责人的现场判断，不写理由他既无从改正也无从申辩 | activity:violation-review |

### 奖惩中心 — 志愿者端 `/v/honor`（V2 第 5 批，V32）

> xlsx **Row 41** C「各类违规记录和奖励」、F「各类违规记录和奖励均需组织部同学审核才可显示，审核之后，志愿者会收到提示，并有 **7 天申诉期**」；原型 **P109**「奖惩记录」给出卡片与详情形态。
> **本组接口刻意不受「拒绝使用本程序」处置的拦截**：申诉就在这里提交，若最重的那条处置把这里也挡掉，被罚得最重的人恰恰成了唯一无法申诉的人。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/honor/reward-punishes | 我的奖惩记录，**只返回【终审】已通过的**（Row 41 F）——待初审(0)/待终审(3)/已驳回(2) 一律不返回：初审通过不产生任何效力，让志愿者提前看到一张还可能被驳回的处罚，等于把「审核之后才可显示」作废。**V43 起分页**（`page`/`size`，响应改为 `{records,total,…}`，破坏性改动）。行上带 `rpNo`（P109「处罚编号」）/类别/标题/说明/`pointsDelta`/处置（`sanctionScope` + 中文名 + `sanctionDays`）/申诉状态与截止时刻，以及 **`appealable`**——「是处罚 + 已生效 + 未申诉 + 未过期」四条由**服务端**算，散到前端拼迟早两端算出不同结果（按钮在但点了报错，或反过来） | 需登录 |
| GET | /v/honor/sanctions | 我当前生效中的处置。**到期即自动消失，不依赖任何定时任务**——判定恒为 `status=1 AND effective_time<=NOW() AND (expire_time IS NULL OR expire_time>NOW())`；靠 cron 改状态位的话，任务漏跑一次处罚就会超期继续生效，而「到期即恢复」是对志愿者的承诺 | 需登录 |
| POST | /v/honor/reward-punishes/{id}/appeal | 对**处罚**提交申诉，body `reason` 必填，**`imageUrls` 可选**（申诉凭证，最多 6 张，先经 `POST /v/files/appeal-image` 拿 URL；张数与逗号在服务端当场校验，**不靠列宽兜底**——截断会让受理人看到半截 URL 而提交却是成功的）。出参 `appealImageUrls` 为**空数组而非 null**。**奖励不能申诉**（P109 的申诉按钮只画在处罚卡片与处罚详情上，奖励卡片只有「查看详情」）；超过 `appealDeadline` 拒绝；**重复提交拒绝**而不是覆盖第一次的理由与时间。非本人的单返回「奖惩记录不存在」，不区分「不存在」与「不是你的」 | 需登录 |

### 站内提示 — 志愿者端 `/v/notifications`（V2 第 5 批收口，V37）

> xlsx **Row 41** F「各类违规记录和奖励均需组织部同学审核才可显示，**审核之后，志愿者会收到提示**，并有 7 天申诉期」。
> V32 只做了后半句（7 天窗口落库），提示本身一直缺着——V37 补上。
>
> ⚠️ **只是站内提示，没有推送**：志愿者要打开小程序才看得到。外部渠道都卡在协会侧的外部前置上——
> 火山短信的**通知类**模板要以协会名义单独报备（与验证码类流程不同），微信订阅消息要协会小程序的模板 id。
> 接上任一渠道时，写入点仍是 `NotificationService.notify` 那一处，不必改调用方。
>
> **本组接口刻意不受「拒绝使用本程序」处置的拦截**：告知处罚成立与申诉期限的那条提示就在这里，
> 挡掉等于罚了人却不告诉他。与奖惩记录、申诉入口同一条口径。
>
> 不挂权限点：每个志愿者看自己的东西，只需登录。收件人恒取当前登录态，**不从入参取**。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/notifications | 我的提示（分页，`page`/`size`），按 id 倒序。行上带 `type`（1奖惩审核通过/2申诉受理结果）、`title`/`content`、`bizType`+`bizId`（前端据此跳奖惩详情）、`isRead`/`readTime` | 需登录 |
| GET | /v/notifications/unread-count | 未读条数，给角标用 | 需登录 |
| POST | /v/notifications/{id}/read | 标记一条为已读，回 `true` 表示本次确实由未读变已读。**重复调用不报错**（前端多半「打开详情就顺手标一次」）；`read_time` 只记**第一次**看到的时刻，不被后续访问覆盖。别人的提示标不动（SQL 带 `volunteer_id` 条件） | 需登录 |

### 奖惩中心 — 管理端 `/a/honor`（V2 第 5 批，V32）

> **三条效力全部挂在「审核通过」那一刻**：对志愿者可见、积分加减入账、处置开始生效。待审核期间它只是一张草稿——这既是 Row 41 F 的意思，也免掉了「先罚后审、审不过再退回去」那种要冲正三处状态的麻烦。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/honor/reward-punishes | 奖惩单列表（分页），可按 `volunteerId`/`type`/`reviewStatus`/`appealStatus` 筛。**三权其一**：`honor:reward-punish` 与 `honor:reward-punish-final` 见全部（终审方读不到队列就只能盲审）；**只有 `honor:reward-punish-appeal` 的受理人只看得到已进入申诉流程的单**（看不到待审核草稿与别人尚未批的处罚）——放宽是为了不盲审，收窄是因为「不盲审」要的是看到自己要判的那张，不是看到全部 | honor:reward-punish **或** honor:reward-punish-appeal **或** honor:reward-punish-final |
| POST | /a/honor/reward-punishes | 开单，落**待审核**。`type` 1奖励/2处罚；`category` 是**开放集合**（P113 的违规类型列表结尾写着「........」，P109 又出现了不属于活动现场枚举的「信息泄露」，故用文本而非枚举码）。**奖励的 `pointsDelta` 不能为负、处罚不能为正**（符号写反会让「处罚」给人加分而列表仍显示为处罚）；**奖励不得附带处置或关联违规**。传 `violationId` 时该违规**必须已通过组织部审核**，且归属（志愿者/活动/场次）**以违规记录为准、不采信入参**——否则可以拿甲的违规去罚乙；同一条违规同时只能有一张**未被驳回**的单（V35：驳回后可以就同一条违规重新开单——`reject` 强制填写的那条原因本就是给开单人据以改正的，而系统没有修改/重提入口）。⚠️ **`sanctionScope=3`（拒绝其使用本程序）需额外持有 `honor:sanction-all`**（V36，Row 73「监察部拥有全部限制能力」）；1/2 两档仍在开单权之内。这一档是**按请求体字段条件校验**的（`AdminRewardPunishController.assertScopeAllowed`），不是方法级注解——挂成注解会把「限制参加活动」这类日常处罚一并锁死，故右列只列注解上的那个点 | honor:reward-punish |
|  |  | ⚠️ **开单人持 `honor:reward-punish-final` 时走「开即通过」快捷通道**（协会：「理事会的同学开单的则自动完成」「紧急情况下由理事会的同学直接进行处罚的，则自动审核完成」），效力当场全部落地。**判据是开单人的权限，不是请求体里的开关**——做成开关的话任何有开单权的人都能给自己开免审通道 |  |
| POST | /a/honor/reward-punishes/{id}/approve | **初审通过**（组织部）：待初审 → **待终审**。**不产生任何效力**——积分不入账、处置不施加、不发提示、申诉期也还没开始计时。协会 2026-09-02：「从下往上反馈的，由组织部的同学审核了，则到理事会审核」，且「理事会没审完，志愿者不会看到处罚」。⚠️ **奖励不经这一步**（开单即落待终审） | honor:reward-punish |
| POST | /a/honor/reward-punishes/{id}/final-approve | **终审通过**（理事会）：待终审 → 已通过。**五件效力在这一刻一次落地**：对志愿者可见 + 积分入账（`source_type=6`，`source_id`=单据 id，`uk_source` 保幂等）+ 处置生效 + **申诉截止时刻落库定死**（= 此刻 + 7 天）+ 站内提示与短信。**截止时刻不现算**：现算意味着哪天把 7 改成 3，在途的申诉权会被追溯性缩短甚至当场作废。⚠️ CAS 条件必须是「== 待终审」而不是「≠ 已通过」，否则一张没初审的处罚会被一步批掉。⚠️ **审核那一刻会当前读复核账号状态**：行已不存在 / 已注销一律拒绝并提示驳回；**禁用照常审核**（协会 2026-08-11 第 5 条——给禁用账号开了只能看奖惩、提申诉的小口子，通过审核正是那条口子的起点） | honor:reward-punish-final |
| POST | /a/honor/reward-punishes/{id}/reject | 驳回，`reason` 必填。**初审、终审两档都可以驳**——只让初审驳的话，单子到了理事会手上就只剩「批」这一条路。志愿者始终看不到这张单，不入账、不生效 | honor:reward-punish **或** honor:reward-punish-final |
| POST | /a/honor/reward-punishes/{id}/appeal | **受理**申诉。`upheld=true` 成立 → 撤销处置 **+ 冲正积分**；`false` 驳回 → 维持原处罚。两种都必须填 `result`（驳回不写理由，志愿者只会看到「申诉失败」四个字）。<br>**冲正走反向流水而不是改原始流水**（账本追加型，原始那笔是「当时确实按这张单扣了分」的事实）；且反向流水**不能复用 `source_id`**——`uk_source(6, id)` 已被审核通过那笔占住，再写必然撞键，故走 `source_id=null` + `requestId=sys:rp-revert:{id}`，由 `uk_request_id` 兜幂等。<br>**单独一个权限点**：需求只写了审核方是组织部，**没写申诉由谁受理**；做成可授权的点，谁受理由后台配置决定，而不是在代码里替协会挑一个部门 | honor:reward-punish-appeal |
| GET | /a/honor/sanctions | 查某人当前生效中的处置（`volunteerId` 必填） | honor:sanction |
| POST | /a/honor/reward-punishes/{id}/lift-sanction | 提前解除某张单产生的处置，`reason` 必填，回实际解除条数。申诉成立时由系统自动解除，不走这个接口 | honor:sanction |

### 考勤/积分变更二次审核 — 管理端 `/a/activity`

> 组织部修改签到/签退/积分 → **部长二次审核**通过后才生效。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /a/activity/attendances/{id}/changes | 组织部申请改签到/签退/积分（body: `changeType` 1签到时间/2签退时间/3积分、`newValue` 时间ISO或整数、`reason`；待审，不立即生效。**`changeType=3` 仅允许积分已发放（`points_status=1`）的记录**——未发放前改积分会被随后的发放重算覆盖，故组织部端在未发放时不应展示「改积分」入口） | 需登录（activity:attendance-edit，组织部） |
| GET | /a/activity/attendance-changes | 变更申请列表（`status` 0待审/1通过/2拒绝筛选；带活动/志愿者上下文）。**V30 每行加带 `slotId`/`slotProjectName`/`slotStartTime`/`slotEndTime`**——同一人同活动两场都申请改签到时，两行的「活动+姓名+变更项」完全相同；且部长审的是「签到时间改成 X」，需要岗位起止作为「X 是否合理」的参照 | 需登录 |
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
| GET | /a/organization/sub-accounts | 子账号列表 | 需登录（org:sub-account） |
| POST | /a/organization/sub-accounts | 创建子账号 | 需登录（org:sub-account） |
| GET | /a/organization/sub-accounts/{id} | 子账号详情（含权限列表） | 需登录（org:sub-account） |
| PUT | /a/organization/sub-accounts/{id} | 修改子账号基本信息 | 需登录（org:sub-account） |
| DELETE | /a/organization/sub-accounts/{id} | 删除子账号 | 需登录（org:sub-account） |
| PUT | /a/organization/sub-accounts/{id}/permissions | 全量替换权限集合 | 需登录 |
| POST | /a/organization/sub-accounts/{id}/password/reset | 重置子账号密码 | 需登录（org:sub-account） |
| GET | /a/organization/permissions | 系统全量可分配权限列表 | 需登录（org:sub-account） |
| GET | /a/organization/permissions/volunteer-grantable | 可授权给志愿者的权限点目录（活动域子集，除 activity:menu） | 需登录（org:sub-account） |

### 志愿小组 — 管理端 `/a/organization/groups`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/groups | 全量小组列表 | 需登录（org:group-manage） |
| DELETE | /a/organization/groups/{id} | 解散小组（带原因，记录 dissolve_*） | 需登录（org:group-manage） |
| PUT | /a/organization/groups/{id}/leader | 转移组长（写入组长变更历史） | 需登录（org:group-manage） |
| GET | /a/organization/groups/{id}/leader-history | 组长变更历史 | 需登录（org:group-manage） |
| GET | /a/organization/groups/{id}/members | 小组成员列表（转移组长选人用） | 需登录（org:group-manage） |
| POST | /a/organization/groups/import | 批量导入小组数据（Excel） | 需登录（org:group-manage） |
| GET | /a/organization/groups/applications | 建组申请列表 | 需登录（org:group-audit） |
| POST | /a/organization/groups/applications/{id}/approve | 批准建组 | 需登录（org:group-audit） |
| POST | /a/organization/groups/applications/{id}/reject | 拒绝建组 | 需登录（org:group-audit） |

### 归属分队 — 管理端 `/a/organization/squads`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/organization/squads | 分队列表 | 需登录（org:squad-manage） |
| POST | /a/organization/squads | 创建分队（含类型/负责人/人数上限） | 需登录（org:squad-manage） |
| PUT | /a/organization/squads/{id} | 修改分队信息 | 需登录（org:squad-manage） |
| DELETE | /a/organization/squads/{id} | 删除分队 | 需登录（org:squad-manage） |
| GET | /a/organization/squads/applications | **全局**待审加入申请（不按分队，默认 status=0 可传覆盖，每行带 squadName；概览/统一审批用） | 需登录（org:squad-audit） |
| GET | /a/organization/squads/{id}/applications | 某分队加入申请列表 | 需登录（org:squad-audit） |
| POST | /a/organization/squads/applications/{id}/approve | 批准加入 | 需登录（org:squad-audit） |
| POST | /a/organization/squads/applications/{id}/reject | 拒绝加入 | 需登录（org:squad-audit） |

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
| GET | /a/publicity/banners | 轮播图列表 | 需登录（pub:banner） |
| POST | /a/publicity/banners | 新增轮播图（含图片裁剪/跳转链接） | 需登录（pub:banner） |
| PUT | /a/publicity/banners/{id} | 修改轮播图 | 需登录（pub:banner） |
| DELETE | /a/publicity/banners/{id} | 删除轮播图 | 需登录（pub:banner） |
| PATCH | /a/publicity/banners/{id}/sort | 调整排序权重（body: `{"sort": 1}`） | 需登录（pub:banner） |
| GET | /a/publicity/announcements | 公告列表 | 需登录（pub:announcement） |
| POST | /a/publicity/announcements | 新增公告（支持插图/跳转推文/小程序） | 需登录（pub:announcement） |
| PUT | /a/publicity/announcements/{id} | 修改公告 | 需登录（pub:announcement） |
| DELETE | /a/publicity/announcements/{id} | 删除公告 | 需登录（pub:announcement） |
| GET | /a/publicity/files | 全量文件列表 | 需登录（pub:file） |
| POST | /a/publicity/files | 上传文件 | 需登录（pub:file） |
| DELETE | /a/publicity/files/{id} | 删除文件 | 需登录（pub:file） |
| PATCH | /a/publicity/files/{id}/access | 开放/关闭志愿者端下载（body: `{"downloadable": true}`） | 需登录（pub:file） |

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
| GET /v/organization/exams/** | 活动临时负责人考试（达分获资格/主观题人工审核/历史考试/评价过低组织部审核取消） | V1 暂缓 |
| GET /e/** | 爱心企业端全部接口 | V1 暂缓 |
