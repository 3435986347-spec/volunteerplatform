# 接口 URL 约束文档 V1

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
| POST | /v/auth/sms/codes | 发送短信验证码（注册/换绑手机号均调此接口） | 公开 |
| POST | /v/auth/login/wechat | 微信小程序登录；未注册返回 `registered:false`，已注册返回 token | 公开 |
| POST | /v/auth/login/dev | **开发登录**：跳过微信直接发 token 供前端联调（无 appid/secret 时用）。body 可选 `key`（测试身份，默认 tester）/`registered`（true 造已实名身份）。**仅 `hengde.auth.dev-login-enabled=true` 可用，生产被 `ProductionConfigGuard` fail-fast 拒绝** | 公开（dev 限定） |
| GET | /v/auth/agreement | 获取志愿者协议（注册前阅读）；返回 `{version, text}`，正文/版本经配置 | 公开 |
| POST | /v/auth/register | 志愿者实名注册（身份证二要素 + 短信验证码 + 企业微信群校验 + **协议手写签名图 URL**） | 需登录（先微信登录拿游客 token） |
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
| GET | /v/user/profile | 获取个人基本资料 | 需登录 |
| PATCH | /v/user/profile | 更新可修改项（头像/手机号/紧急联系方式/政治面貌/学校/年级/通讯地址） | 需登录 |
| GET | /v/user/volunteer-card | 获取电子志愿者证（类身份证样式，含内嵌小程序码） | 需登录 |

### 管理端 `/a/user`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/user/volunteers | 志愿者列表（`?keyword=&gender=&squad=&political=&school=&grade=&page=&size=`） | 需登录 |
| GET | /a/user/volunteers/{id} | 志愿者详情 | 需登录 |
| PUT | /a/user/volunteers/{id} | 修改志愿者全量信息 | 需登录 |
| PATCH | /a/user/volunteers/{id}/status | 暂停/恢复志愿者账号（body: `{"status": 0/1}`） | 需登录 |
| DELETE | /a/user/volunteers/{id} | 删除志愿者 | 需登录 |
| POST | /a/user/volunteers/{id}/password/reset | 重置志愿者密码 | 需登录 |
| GET | /a/user/volunteers/export | 批量导出志愿者（Excel，支持与列表相同的筛选参数） | 需登录 |

---

## 活动 activity

### 志愿者端 `/v/activity`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/activities | 活动列表/推荐（排序：有名额优先→最新活动时间；返回含 `enrolledCount` 报名人数、`hasQuota` 是否有名额） | 需登录 |
| POST | /v/activity/activities | 提交活动（「管理团队」志愿者；不带 `type=admin` 走默认 login 域鉴权，吃 V18 志愿者权限；**V19 起：落「待审核发布」status=4、不直接上线，须后台 `activity:publish-audit` 审核通过才可见**；操作人记志愿者） | 需登录（activity:publish） |
| GET | /v/activity/activities/{id} | 活动详情（含子时间段/子项目/报名须知；内部展示全字段：定位+经纬度、三类报名开放时间、报名限制等） | 需登录 |
| POST | /v/activity/activities/{id}/enroll | 报名（body 指定时间段） | 需登录 |
| DELETE | /v/activity/activities/{id}/enroll | 取消报名 | 需登录 |
| POST | /v/activity/activities/{id}/proxy-enrollments | 同小组成员代报名 | 需登录 |
| GET | /v/activity/my-enrollments | 我的报名列表 | 需登录 |
| GET | /v/activity/my-activities | 我的活动（名称/时间段/负责人/签到状态/是否违规/考勤） | 需登录 |
| GET | /v/activity/my-activities/{id} | 我的活动详情（含考勤 + 签到二维码数据 + 紧急上报电话 `emergencyPhone`） | 需登录 |
| POST | /v/activity/activities/{id}/check-in | 自助签到（body: lat/lng；GPS 距活动 ≤ 签到半径 且在签到时间窗口内） | 需登录 |
| POST | /v/activity/activities/{id}/confirm-home | 确认到家（body: lat/lng；活动结束后；超时仅记录不拒绝） | 需登录 |
| POST | /v/activity/activities/{id}/review | 评价活动与负责人（body: 活动评分1~5/负责人评分1~5/评论；须实际签到、活动结束后；可覆盖） | 需登录 |
| GET | /v/activity/service-records | 我的服务记录（活动名称/签到/签退/时长） | 需登录 |

### 活动现场负责人 — 志愿者端 `/v/activity/managed-activities`

> 仅活动的**已指派负责人（志愿者）**可访问；管理团队负责人走 `/a/activity`（下表对应动作）。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/managed-activities | 我负责的活动场次列表 | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id} | 负责详情（志愿者名单含名字/电话/学校 + 签到/签退二维码 + 紧急上报电话 `emergencyPhone`；名单 roster 即「签到记录」数据：签到/签退时间、到位状态、违规数） | 需登录（活动负责人） |
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
| POST | /a/activity/activities | 发布活动（**后台直发、直接上线 status=1，不进审核队列**；含子时间段/积分倍率/报名限制——`requireMinJoinCount` 已参加次数门槛、`requireMinJoinMinutes` 已参加服务时长门槛(分钟)；GPS 签到坐标 `lat`/`lng`/`checkInRadiusM` 默认500，经纬度须同填或同空） | 需登录 |
| GET | /a/activity/activities/pending-reviews | 活动发布审核列表（带提交人姓名；`status` 默认 4 待审核，传 5 看已驳回） | 需登录（activity:publish-audit） |
| GET | /a/activity/activities/{id}/review-detail | 待审/驳回活动完整详情（含驳回原因/审核人/时间；审核者看全字段无需 activity:menu；常规 `GET …/{id}` 已排除待审/驳回） | 需登录（activity:publish-audit） |
| POST | /a/activity/activities/{id}/publish-approve | 发布审核通过（活动上线 status→1） | 需登录（activity:publish-audit） |
| POST | /a/activity/activities/{id}/publish-reject | 发布审核驳回（status→5，body 可填 `reason`） | 需登录（activity:publish-audit） |
| GET | /a/activity/activities/{id} | 活动详情（回显 `lat`/`lng`/`checkInRadiusM` 等全字段） | 需登录 |
| PUT | /a/activity/activities/{id} | 修改活动（同发布入参，含 `requireMinJoinCount`/`requireMinJoinMinutes` 报名门槛、GPS 坐标 `lat`/`lng`/`checkInRadiusM`，经纬度须同填或同空；待审核/驳回活动不可改） | 需登录 |
| DELETE | /a/activity/activities/{id} | 删除活动（待审核/驳回活动不可删，属审核侧处置） | 需登录 |
| POST | /a/activity/activities/{id}/copy | 复制活动（**待审核/驳回活动不可复制**，否则绕开审核直接发布同内容） | 需登录 |
| GET | /a/activity/activities/{id}/enrollments | 报名列表（优先展示管理团队/临时负责人） | 需登录（activity:enroll-view） |
| GET | /a/activity/activities/{id}/enrollment-slots | 活动时间段列表（报名域，供手动新增报名选时间段，避免要 activity:menu；仅已发布活动，与手动新增口径一致，否则报「活动不存在」） | 需登录（activity:enroll-view） |
| POST | /a/activity/activities/{id}/enrollments | 手动新增报名（body: `volunteerId`+`slotIds`） | 需登录（activity:enroll-add） |
| GET | /a/activity/activities/{id}/enrollments/export | 导出报名名单（Excel） | 需登录（activity:enroll-export） |
| POST | /a/activity/enrollments/{id}/approve | 审核通过 | 需登录（activity:enroll-audit） |
| POST | /a/activity/enrollments/{id}/reject | 审核拒绝（body 填拒绝原因） | 需登录（activity:enroll-audit） |
| DELETE | /a/activity/enrollments/{id} | 删除报名记录 | 需登录（activity:enroll-delete） |
| POST | /a/activity/activities/{id}/leaders | 指派活动负责人（志愿者或管理团队；不占人数；待审核/驳回活动不可指派） | 需登录（activity:leader-assign，组织部） |
| GET | /a/activity/activities/{id}/leaders | 负责人列表 | 需登录 |
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
| POST | /a/activity/attendances/{id}/points | 发放积分（完成基数×倍率；违规减半/不发） | 需登录（activity:points-grant） |

### 考勤/积分变更二次审核 — 管理端 `/a/activity`

> 组织部修改签到/签退/积分 → **部长二次审核**通过后才生效。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /a/activity/attendances/{id}/changes | 组织部申请改签到/签退/积分（body: `changeType` 1签到时间/2签退时间/3积分、`newValue` 时间ISO或整数、`reason`；待审，不立即生效。**`changeType=3` 仅允许积分已发放（`points_status=1`）的记录**——未发放前改积分会被随后的发放重算覆盖，故组织部端在未发放时不应展示「改积分」入口） | 需登录（activity:attendance-edit，组织部） |
| GET | /a/activity/attendance-changes | 变更申请列表（`status` 0待审/1通过/2拒绝筛选；带活动/志愿者上下文） | 需登录 |
| POST | /a/activity/attendance-changes/{id}/approve | 部长二次审核通过（应用变更；改签到/签退按 签退−签到 重算时长，改积分覆盖） | 需登录（activity:attendance-audit，部长） |
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
| GET | /a/organization/groups/{id}/leader-history | 组长变更历史 | 需登录 |
| POST | /a/organization/groups/import | 批量导入小组数据（Excel） | 需登录 |
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
| PUT | /a/organization/volunteers/{id}/manager-flag | 设置/取消志愿者「管理团队」标记（body `flag` 0取消/1设为；设为 1 仅限已实名、取消 0 不限；积分 ×1.2 倍率通道；记录操作人/时间） | 需登录（org:manager-flag） |
| GET | /a/organization/volunteers/{id}/permissions | 志愿者已分配的权限点 | 需登录（org:manager-flag） |
| PUT | /a/organization/volunteers/{id}/permissions | 全量替换志愿者权限（body `permissionIds`；**仅超管**；**目标须已标记管理团队 `manager_flag=1`**[防误授普通/游客态志愿者]；只接受活动域子集白名单，非白名单点拒；传空 `permissionIds`=清空，不要求 manager_flag[便于降级后清理 stale 授权]） | 需登录（仅超管） |

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
| GET /v/honor/** | 排行榜/榜样/勋章/奖惩 | V1 暂缓 |
| GET /v/social/** | 社区（帖子/私信/互动） | V1 暂缓 |
| POST /v/activity/activities/{id}/photos | 活动相册（上传照片+评论，默认发交流平台） | 依赖社区(social)，推迟到 social 落地一起做 |
| GET /v/donate/** | 积分兑换/众筹/捐书/微心愿/助学结对 | V1 暂缓 |
| /a/organization/manager-applications/** | 报名管理团队（问卷式申请+批量下载，回写 volunteer.manager_flag；标记本身已可经上方 manager-flag 接口手动开关） | V1.1 预留，本期不建 |
| GET /v/organization/exams/** | 活动临时负责人考试（达分获资格/主观题人工审核/历史考试/评价过低组织部审核取消） | V1 暂缓 |
| GET /e/** | 爱心企业端全部接口 | V1 暂缓 |
