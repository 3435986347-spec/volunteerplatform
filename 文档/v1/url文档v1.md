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
| GET | /v/auth/agreement | 获取志愿者协议文本（注册前阅读） | 公开 |
| POST | /v/auth/register | 志愿者实名注册（身份证二要素 + 短信验证码 + 企业微信群校验 + **协议手写签名图 URL**） | 需登录（先微信登录拿游客 token） |
| GET | /v/auth/wechat/group-membership | 企业微信群成员资格校验 | 公开 |
| POST | /v/auth/logout | 退出登录 | 需登录 |

### 管理端 `/a/auth`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| POST | /a/auth/login | 账号密码登录 | 公开 |
| POST | /a/auth/sms/codes | 发送短信验证码（仅用于找回密码） | 公开 |
| PUT | /a/auth/password/reset | 凭短信验证码重置密码 | 公开 |
| PUT | /a/auth/password | 修改密码（已登录状态） | 需登录 |
| POST | /a/auth/logout | 退出登录 | 需登录 |

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
| GET | /v/activity/activities/{id} | 活动详情（含子时间段/子项目/报名须知；内部展示全字段：定位+经纬度、三类报名开放时间、报名限制等） | 需登录 |
| POST | /v/activity/activities/{id}/enroll | 报名（body 指定时间段） | 需登录 |
| DELETE | /v/activity/activities/{id}/enroll | 取消报名 | 需登录 |
| POST | /v/activity/activities/{id}/proxy-enrollments | 同小组成员代报名 | 需登录 |
| GET | /v/activity/my-enrollments | 我的报名列表 | 需登录 |
| GET | /v/activity/my-activities | 我的活动（名称/时间段/负责人/签到状态/是否违规/考勤） | 需登录 |
| GET | /v/activity/my-activities/{id} | 我的活动详情（含考勤 + 签到二维码数据） | 需登录 |
| POST | /v/activity/activities/{id}/check-in | 自助签到（body: lat/lng；GPS 距活动 ≤ 签到半径 且在签到时间窗口内） | 需登录 |
| POST | /v/activity/activities/{id}/confirm-home | 确认到家（body: lat/lng；活动结束 1h 内） | 需登录 |
| POST | /v/activity/activities/{id}/photos | 上传活动照片+评论到活动相册（默认发交流平台，社区暂缓） | 需登录 |
| POST | /v/activity/activities/{id}/review | 评价活动与负责人（body: 活动评分/负责人评分/评论） | 需登录 |
| GET | /v/activity/service-records | 我的服务记录（活动名称/签到/签退/时长） | 需登录 |

### 活动现场负责人 — 志愿者端 `/v/activity/managed-activities`

> 仅活动的**已指派负责人（志愿者）**可访问；管理团队负责人走 `/a/activity`（下表对应动作）。

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/managed-activities | 我负责的活动场次列表 | 需登录（活动负责人） |
| GET | /v/activity/managed-activities/{id} | 负责详情（志愿者名单含名字/电话/学校 + 签到/签退二维码） | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/start | 点击活动开始 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/finish | 点击活动结束 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/check-outs | 统一签退（全部或指定志愿者；活动结束后 2h 内） | 需登录（活动负责人） |
| PATCH | /v/activity/managed-activities/{id}/attendances/{volunteerId} | 标记到位状态（正常/请假/迟到/缺席）或确认签到 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/attendances/{volunteerId}/violations | 记录违规（玩手机/服装/早退/交头接耳） | 需登录（活动负责人） |
| PATCH | /v/activity/managed-activities/{id}/attendances/{volunteerId}/evaluation | 负责人评价志愿者 | 需登录（活动负责人） |
| POST | /v/activity/managed-activities/{id}/summary | 上传活动总结（文字+图片） | 需登录（活动负责人） |

### 管理端 `/a/activity`

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/activity/activities | 活动列表 | 需登录 |
| POST | /a/activity/activities | 发布活动（含子时间段/积分倍率/报名限制） | 需登录 |
| GET | /a/activity/activities/{id} | 活动详情 | 需登录 |
| PUT | /a/activity/activities/{id} | 修改活动 | 需登录 |
| DELETE | /a/activity/activities/{id} | 删除活动 | 需登录 |
| POST | /a/activity/activities/{id}/copy | 复制活动 | 需登录 |
| GET | /a/activity/activities/{id}/enrollments | 报名列表（优先展示管理团队/临时负责人） | 需登录 |
| POST | /a/activity/activities/{id}/enrollments | 手动新增报名 | 需登录 |
| GET | /a/activity/activities/{id}/enrollments/export | 导出报名名单（Excel） | 需登录 |
| POST | /a/activity/enrollments/{id}/approve | 审核通过 | 需登录 |
| POST | /a/activity/enrollments/{id}/reject | 审核拒绝（body 填拒绝原因） | 需登录 |
| DELETE | /a/activity/enrollments/{id} | 删除报名记录 | 需登录 |
| POST | /a/activity/activities/{id}/leaders | 指派活动负责人（志愿者或管理团队；不占人数） | 需登录（activity:leader-assign，组织部） |
| GET | /a/activity/activities/{id}/leaders | 负责人列表 | 需登录 |
| DELETE | /a/activity/activities/{id}/leaders/{leaderId} | 取消指派 | 需登录（activity:leader-assign） |
| POST | /a/activity/activities/{id}/start | 活动开始（管理团队负责人） | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/finish | 活动结束 | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/check-outs | 统一签退 | 需登录（activity:manage） |
| PATCH | /a/activity/activities/{id}/attendances/{volunteerId} | 标记到位状态/确认签到 | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/attendances/{volunteerId}/violations | 记录违规 | 需登录（activity:manage） |
| POST | /a/activity/activities/{id}/summary | 上传活动总结 | 需登录（activity:manage） |

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
| POST | /a/activity/attendances/{id}/changes | 组织部申请改签到/签退/积分（待审，不立即生效） | 需登录（activity:attendance-edit，组织部） |
| GET | /a/activity/attendance-changes | 变更申请列表（按状态筛选） | 需登录 |
| POST | /a/activity/attendance-changes/{id}/approve | 部长二次审核通过（应用变更） | 需登录（activity:attendance-audit，部长） |
| POST | /a/activity/attendance-changes/{id}/reject | 部长二次审核拒绝 | 需登录（activity:attendance-audit） |

### 活动发布增强 / 留言 / 补录（V1.1 第 3 批）

| Method | URL | 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/activity/activities/{id}/messages | 活动留言列表 | 需登录 |
| POST | /v/activity/activities/{id}/messages | 发表活动留言 | 需登录 |
| DELETE | /a/activity/messages/{id} | 删除活动留言 | 需登录（activity:manage） |
| POST | /a/activity/activities/recurring | 固定日期/人数/时间段批量发布多场活动 | 需登录（activity:publish） |
| POST | /a/activity/activities/historical | 发布历史活动（之前未发布过，专用补录入口） | 需登录（activity:publish） |
| POST | /a/activity/activities/{id}/backfills | 活动补录（搜手机号/姓名/身份证加指定时间段→得时长；已发布活动亦得积分、历史活动不得积分；待部长审核） | 需登录（activity:backfill） |
| GET | /a/activity/backfills | 补录申请列表（待部长审核） | 需登录 |
| POST | /a/activity/backfills/{id}/approve | 部长审核通过（生效） | 需登录（activity:backfill-audit） |
| POST | /a/activity/backfills/{id}/reject | 部长审核拒绝 | 需登录（activity:backfill-audit） |

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
| GET | /a/organization/squads/{id}/applications | 加入申请列表 | 需登录 |
| POST | /a/organization/squads/applications/{id}/approve | 批准加入 | 需登录 |
| POST | /a/organization/squads/applications/{id}/reject | 拒绝加入 | 需登录 |

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
| GET /v/donate/** | 积分兑换/众筹/捐书/微心愿/助学结对 | V1 暂缓 |
| GET /v/organization/exams/** | 活动临时负责人考试 | V1 暂缓 |
| GET /e/** | 爱心企业端全部接口 | V1 暂缓 |
