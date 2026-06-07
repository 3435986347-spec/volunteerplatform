# 后台管理系统设计 Prompt（恒德志愿者平台 · 已实现功能全量）

> 下面整段直接粘给 Claude（design / frontend）即可。内容覆盖**当前已实现的全部后台 `/a` 接口、按钮与可视化**，按真实 Controller 逐一核对，未实现的能力已显式标注，不要凭空补造。

---

## 一、角色与目标

为「雷州市恒德爱心公益协会志愿者管理平台」设计并实现一套 **PC Web 管理后台**。使用者是协会运营人员：

- **超级管理员**：拥有全部权限（后端以通配 `*` 放行），可见全部菜单与按钮。
- **部门子账号**（组织部 / 秘书部 / 宣传部 / 监察部等）：按被分配的**细粒度权限点**决定可见的菜单和可点的按钮。

设计目标关键词：**简洁迅速、可视化清晰、模块清晰、操作简洁明了**。后台是高频内部工具，优先信息密度与操作效率，不要营销风、不要花哨动效。

---

## 二、整体设计要求

- **布局**：经典管理后台三段式 —— 左侧**可折叠分组侧边栏**（按业务域分组）、顶部栏（当前账号 / 修改密码 / 退出登录 / 面包屑）、主内容区。
- **主内容区范式**：`筛选区（关键词 + 状态下拉 + 日期）` →`工具栏（新增 / 导入 / 导出 等主操作按钮，靠右）` → `数据表格（分页）` → `行内操作列`。新增/编辑/详情统一用**右侧抽屉 Drawer 或居中 Modal**，不跳页。
- **风格**：浅色、克制、留白合理；强调色一种为主（建议蓝），状态用语义色（见状态色板）。圆角中等、阴影轻、表格斑马纹可选。中文字体优先。
- **响应式**：以 1440px 为主，向下兼容到 1280px；表格横向可滚动。
- **组件库**：推荐 React + TypeScript + Ant Design v5（最贴合中文管理后台、表格/表单/抽屉开箱即用）；如用 Vue 则 Element Plus。允许替换，但须保持「数据表格 + 抽屉表单 + 审核流」这套范式。
- **通用态**：每个列表/详情都要有 **加载中 / 空数据 / 请求失败重试** 三态；所有**删除/解散/拒绝/发积分**等不可逆操作弹二次确认。
- **全局水印（xlsx R78）**：所有后台页面铺一层**半透明防泄密水印**，内容为「使用者姓名 · 手机尾号 · 部门」（取自登录态信息，纯前端渲染、无需后端接口）；水印淡、不挡操作、平铺重复。

---

## 三、全局技术约定（务必遵守）

- **接口前缀**：所有后台接口在 `/api` + `/a/...`（`context-path=/api`）。例：`POST /api/a/auth/login`。
- **鉴权**：管理端独立登录态。登录拿到 token 后，**每个请求头带** `Authorization: <token>`（管理端与志愿者端隔离，后台只调 `/a/**`）。
- **统一响应体**：`{ "code": 200, "message": "成功", "data": <T> }`。`code===200` 视为成功，业务数据在 `data`；非 200 用 `message` 弹错误提示。
- **分页**：请求参 `page`（从 1 开始）、`size`（默认 10，最大 100），可带 `keyword`、`status` 等筛选。分页响应 `data` 形如 `{ records: [...], total, page, size, pages }`，表格用 `records` + `total`。
- **权限驱动 UI（核心）**：**菜单项与操作按钮按当前账号权限点显隐**；无权限的菜单不渲染、无权限的按钮隐藏或禁用。超管拥有全部。每个接口下方标注了它要求的权限点（`@SaCheckPermission`），按此控制对应按钮。标「仅超管」的按钮只在超级管理员可见。
  - ⚠️ **前置缺口（务必读）**：当前后端**管理端登录 `POST /a/auth/login` 只返回一个 token 字符串，没有 `/a/auth/me` / 当前账号权限码 这类接口**——前端拿不到「当前管理员是谁、有哪些权限码、是否超管」。因此：**做设计稿/可运行 demo 时先 mock 一份权限码集合**（提供「超管」「某部门」两套 mock 切换）；**要真正接后端，须先补一个「当前管理员资料 + 权限码」接口**（如 `GET /a/auth/me` 返回 `{ adminId, name, dept, isSuperAdmin, permissionCodes:[] }`）。请把它作为**第 0 步前置任务**写明，不要假装这个接口已存在。
- **导出类**接口（Excel）走浏览器下载（返回文件流，不是 JSON），点击后触发下载。
- **上传约定（注意：目前后端无通用上传接口）**：当前**没有图片/文件上传 controller**——`POST /a/publicity/files` 不是 multipart，而是提交 `{ fileName, fileUrl, ... }` 的 **JSON（URL 要前端先有）**；唯一的 multipart 端点是小组批量导入 `POST /a/organization/groups/import`（`multipart/form-data` 传 `file`，Excel）。因此凡涉及传图（轮播图/公告封面与插图/活动封面/活动总结图）：**设计稿照画上传 + 裁剪交互**；**可运行代码须三选一**——①前端直传对象存储（OSS，需后端给 STS/签名，当前也未实现）②临时手填图片 URL ③**补一个 `POST /a/files/upload` 通用上传接口**。把它列为**前置任务**，别假设上传接口已存在。

### 权限驱动 UI —— 两种视角对照（务必按此设计，菜单过滤 + 按钮级权限是第一类设计要素）

**同一套后台，不同账号登录后界面不同**：菜单按权限点过滤、操作按钮按权限点显隐。请至少出**两个视角的对照稿**，让「不是所有账号都长一样」成为明确的设计前提，而不是只画一套全功能界面。

**视角 A · 超级管理员**（权限通配 `*`）
- 侧边栏：概览 / 活动管理（6 个子页全有）/ 组织管理（小组 + 分队 + 志愿者标记与授权 + 子账号与权限）/ 信息公示（轮播 + 公告 + 文件）全部可见。
- 每页所有按钮可见：发布、修改、删除、复制、分配权限、发积分、各类审核……

**视角 B · 部门子账号示例（「组织部」，分配 `activity:enroll-view`、`activity:enroll-audit`、`org:group-audit`、`org:squad-manage`、`org:squad-audit`）**
- 侧边栏**只剩**：报名管理（看名单 + 审核）、志愿小组—建组审批、归属分队（含加入审批）。
- **看不到的菜单**：子账号与权限、志愿者标记与授权、信息公示、活动发布/编辑/删除、服务记录与积分、考勤变更/补录审核。
- 进「报名管理」页：**能看报名名单**（靠 `enroll-view`，否则连列表都拉不到、拿不到报名 ID 也就无从审核）+ **「通过 / 拒绝」两个操作按钮**（`enroll-audit`）；没有「手动新增报名 / 导出名单 / 删除报名」（属 `enroll-add` / `enroll-export` / `enroll-delete`）。
- 进「志愿小组」页：建组申请列表（`org:group-audit` 直接拉**全局**待审建组 `GET /groups/applications`）+ 「批准 / 拒绝」；看不到「解散 / 转移组长 / 批量导入」（属 `org:group-manage`）。
- 进「归属分队」页：分队加入申请是**按分队**的（`GET /squads/{id}/applications` 需 `org:squad-audit`），但**要先列出分队才能拿到分队 ID**，而分队列表 `GET /squads` 需 `org:squad-manage`——所以审批分队加入**必须同时有 `squad-manage`**（本示例已含）。

> **配权口诀（务必体现在示例里）**：**「审核权」往往要搭一个「查看/列表权」才闭合**——只给 audit 不给 view/manage，会出现「有按钮、无数据入口」。典型：报名审核要配 `enroll-view`；分队加入审核要配 `squad-manage`（因为没有「全局待审分队申请」接口，只能先列分队再进具体分队看申请）。建组审核例外（`/groups/applications` 是全局接口，单 `group-audit` 即可）。

**关键示意点**：同一个「报名管理」页，**超管看到全部操作，组织部只看到「通过 / 拒绝」**；「子账号与权限」整个菜单超管有、组织部直接消失。落地用**按钮级权限包裹组件 `<Auth code="...">…</Auth>`**（无权限不渲染）+ **菜单按权限点过滤**两层实现。

---

## 四、侧边栏导航（按业务域分组；括号内为控制可见性的权限点）

```
■ 概览                         （登录即可）
■ 活动管理
    · 活动列表/发布            (activity:menu / activity:publish / edit / delete)
    · 报名管理                (activity:enroll-view ...)
    · 现场管理（负责人/签到）  (activity:leader-assign / activity:manage)
    · 服务记录与积分          (activity:service-confirm / activity:points-grant)
    · 考勤变更审核            (activity:attendance-edit / attendance-audit)
    · 活动补录审核            (activity:backfill / backfill-audit)
■ 组织管理
    · 志愿小组                (org:group-manage / org:group-audit)
    · 归属分队                (org:squad-manage / org:squad-audit)
    · 志愿者标记与授权        (org:manager-flag / 仅超管授权)
    · 子账号与权限            (org:sub-account / 仅超管分配)
■ 信息公示
    · 轮播图                  (pub:banner)
    · 公告                    (pub:announcement)
    · 文件下载                (pub:file)
■ 账号安全（修改密码/退出，顶栏入口，登录即可）
```

---

## 五、概览页（首页）

聚合**待办计数卡片**，点击卡片跳到对应列表（已带状态筛选）。后端**暂无专用统计接口**，这些数字**由各列表接口 `status=待审` 的 `total` 拼出**（设计成卡片即可，标注数据来源）：

- 待审建组申请（`GET /a/organization/groups/applications` 的 total）✅ 全局接口可直接计数
- 待审考勤变更（`GET /a/activity/attendance-changes?status=0`）✅
- 待补录审核（`GET /a/activity/backfills?status=0`）✅
- 待秘书部确认时长（`GET /a/activity/service-records/pending`）✅
- 待审分队加入 ——⚠️ **无全局接口**（加入申请是按分队的 `GET /squads/{id}/applications`），概览只能**先放占位**或标注「需后端补全局待审分队申请接口」，不要硬拼。
- 待审报名 ——⚠️ 报名审核是**按活动**的（`GET /a/activity/activities/{id}/enrollments?status=...`），无全局待审报名接口，概览**占位**或在活动详情内展示。

> 注：协会级数据看板（`data:dashboard`）尚未实现，**不要画营收/趋势大图**，概览只做「待办入口 + 简单计数」。

---

## 六、各模块页面规格（接口 = 真实已实现端点）

> 每个端点格式：`METHOD 路径` ——说明 `[所需权限点]`。所有路径前再加 `/api`。

### 1. 账号 / 认证（顶栏 + 登录页）
- 登录页：账号 + 密码登录。`POST /a/auth/login`（公开）
- 找回密码：发短信验证码 `POST /a/auth/sms/codes` → 凭码重置 `PUT /a/auth/password/reset`（均公开）
- 顶栏「修改密码」：`PUT /a/auth/password`
- 顶栏「退出登录」：`POST /a/auth/logout`

### 2. 子账号与权限（仅超管核心）
页面：子账号表格（列：账号、姓名、部门、状态、创建时间）。
- 列表（keyword 搜）：`GET /a/organization/sub-accounts` `[org:sub-account]`
- 详情（含已分配权限）：`GET /a/organization/sub-accounts/{id}` `[org:sub-account]`
- 新增：`POST /a/organization/sub-accounts` `[org:sub-account]`
- 编辑：`PUT /a/organization/sub-accounts/{id}` `[org:sub-account]`
- 删除：`DELETE /a/organization/sub-accounts/{id}` `[org:sub-account]`
- 重置密码：`POST /a/organization/sub-accounts/{id}/password/reset` `[org:sub-account]`
- **分配权限（仅超管）**：`PUT /a/organization/sub-accounts/{id}/permissions`，body `{ permissionIds: [] }`
- 权限点目录（勾选用）：`GET /a/organization/permissions` `[org:sub-account]`
可视化：权限分配用**按业务域分组的多选树/勾选清单**（module 分组：user / activity / organization / publicity / data）。子账号状态用色标签。

### 3. 志愿者标记与授权（管理团队志愿者下放权限）
> 说明：目前**没有「志愿者列表」后台接口**，本页以**志愿者 ID 输入/定位**为入口（预留搜索位，后续补 `/a` 志愿者检索接口再接上）。
- 设置/取消「管理团队」标记：`PUT /a/organization/volunteers/{id}/manager-flag`，body `{ flag: 0|1 }` `[org:manager-flag]`
- 查看该志愿者已授权限：`GET /a/organization/volunteers/{id}/permissions` `[org:manager-flag]`
- **授权（仅超管）**：`PUT /a/organization/volunteers/{id}/permissions`，body `{ permissionIds: [] }`
- 可授权给志愿者的权限点目录：`GET /a/organization/permissions/volunteer-grantable` `[org:sub-account]`
可视化：开关控件控制 manager-flag；权限勾选清单**只列「可授权给志愿者」的活动域权限点**（后端白名单兜底）。提示文案：「需先标记为管理团队，再授权」。

### 4. 志愿小组
列表列：小组名、组长、成员数、状态、创建/审批时间。
- 全量列表（keyword）：`GET /a/organization/groups` `[org:group-manage]`
- 解散小组（填原因）：`DELETE /a/organization/groups/{id}` `[org:group-manage]`
- 转移组长：`PUT /a/organization/groups/{id}/leader` `[org:group-manage]`
- 组长变更历史：`GET /a/organization/groups/{id}/leader-history` `[org:group-manage]`
- 批量导入（Excel）：`POST /a/organization/groups/import`（multipart `file`）`[org:group-manage]`
- **建组申请列表**：`GET /a/organization/groups/applications` `[org:group-audit]`
- 批准建组：`POST /a/organization/groups/applications/{id}/approve` `[org:group-audit]`
- 拒绝建组（填原因）：`POST /a/organization/groups/applications/{id}/reject` `[org:group-audit]`
可视化：组长历史用**时间线**；建组审批用**待办列表 + 通过/拒绝**双按钮；解散/拒绝弹原因输入框。

### 5. 归属分队
- 列表：`GET /a/organization/squads` `[org:squad-manage]`
- 创建：`POST /a/organization/squads` `[org:squad-manage]`
- 修改：`PUT /a/organization/squads/{id}` `[org:squad-manage]`
- 删除：`DELETE /a/organization/squads/{id}` `[org:squad-manage]`
- 加入申请列表：`GET /a/organization/squads/{id}/applications` `[org:squad-audit]`
- 批准加入：`POST /a/organization/squads/applications/{id}/approve` `[org:squad-audit]`
- 拒绝加入（填原因）：`POST /a/organization/squads/applications/{id}/reject` `[org:squad-audit]`
可视化：分队卡片/表格显示人数与上限；加入申请走通过/拒绝审核流。

### 6. 信息公示
**轮播图**（列：图、标题、跳转、排序、上/下架）：
- 列表 `GET /a/publicity/banners`、新增 `POST /a/publicity/banners`、修改 `PUT /a/publicity/banners/{id}`、删除 `DELETE /a/publicity/banners/{id}`、调整排序 `PATCH /a/publicity/banners/{id}/sort`（均 `[pub:banner]`）
- 新增/修改表单字段（对应 BannerDTO）：`title` 标题、`imageUrl` 图片地址、`linkType` 跳转类型、`linkUrl` 跳转地址、`sort` 排序、`status` 0下架/1上架。
- **图片上传必须带「预设尺寸 + 上传后裁剪」**：上传图片后**强制进入裁剪框**，按后台预设的轮播图宽高比裁剪（如 `750×320`、比例 `≈2.34:1`，做成可配置常量；只允许这一个比例，避免首页轮播变形）；裁剪后的成品图再传对象存储，拿回 URL 填入 `imageUrl`。**预设尺寸/裁剪是纯前端逻辑，后端只存最终图片 URL，无尺寸字段**——务必内置裁剪器（如 `react-image-crop` / `cropperjs`），不要让运营自己在外部裁好再传。
- **跳转配置**：`linkType` 用单选 ——`0 无跳转`（隐藏地址输入）/ `1 网页`（填 URL，**「跳转推文」即填公众号文章链接走这一类**）/ `2 小程序`（填小程序 path/appId）；按所选类型动态切换 `linkUrl` 输入的提示与校验。
**公告**：列表 `GET /a/publicity/announcements`、新增 `POST`、修改 `PUT /{id}`、删除 `DELETE /{id}`（均 `[pub:announcement]`）
- 新增/修改表单字段（对应 AnnouncementDTO）：`title` 标题、`summary` 摘要、`content` 正文（富文本）、`coverImageUrl` 封面/插图、`linkType` 跳转类型、`linkUrl` 跳转地址、`status` 0草稿/1发布。
- **与轮播图同款的「预设尺寸 + 上传后裁剪 + 跳转配置」要求（xlsx R03/R68 明确）**：封面图（及富文本正文里插入的图片）上传后**按预设尺寸进裁剪框**再传 OSS 回填 URL；`linkType` 单选 `0 无跳转 / 1 网页（推文填文章链接）/ 2 小程序`，按类型切换 `linkUrl` 输入。复用轮播图那套裁剪器与跳转选择组件。
- `status` 支持**草稿/发布**：可存草稿不公开、发布后志愿者端可见；列表用标签区分草稿/已发布。
**文件下载**：列表 `GET /a/publicity/files`、上传 `POST`、删除 `DELETE /{id}`、开/关下载 `PATCH /a/publicity/files/{id}/access`（均 `[pub:file]`）
可视化：轮播图按预设比例缩略图预览（统一尺寸、不变形）+ 上传即弹裁剪框 + 拖拽/数字排序；跳转类型用彩色 tag（无跳转/网页/小程序）并在列表显示跳转地址；公告富文本编辑（封面/插图同样预设裁剪、可配跳转）+ 草稿/已发布标签；文件「可下载」开关。

### 7. 活动管理（发布/编辑/删除/复制/周期/历史）
列表列：活动名、时间、地点、报名/人数、状态、运行状态。筛选：keyword + status。
- 列表：`GET /a/activity/activities`（keyword/status）`[activity:menu]`
- 详情：`GET /a/activity/activities/{id}` `[activity:menu]`
- 发布活动：`POST /a/activity/activities` `[activity:publish]`
- 固定日期/周期批量发布：`POST /a/activity/activities/recurring` `[activity:publish]`
- 历史活动发布（仅作补录载体，志愿者端不可见）：`POST /a/activity/activities/historical` `[activity:publish]`
- 修改：`PUT /a/activity/activities/{id}` `[activity:edit]`
- 删除：`DELETE /a/activity/activities/{id}` `[activity:delete]`
- 复制：`POST /a/activity/activities/{id}/copy` `[activity:publish]`
可视化：活动状态/运行状态用色标签；发布表单字段多（时间、坐标 + 签到半径、分角色报名开放时间、资格门槛、联系人等），用**分步/分组表单**；周期发布提供「按星期几 + 起止日期」或「显式多日期」两种输入并预览将生成的场次。

### 8. 报名管理（进入某活动后）
- 报名列表（status 筛选，含代报名来源）：`GET /a/activity/activities/{id}/enrollments` `[activity:enroll-view]`
- 手动新增报名（越权补录）：`POST /a/activity/activities/{id}/enrollments` `[activity:enroll-add]`
- 导出名单（Excel）：`GET /a/activity/activities/{id}/enrollments/export` `[activity:enroll-export]`
- 审核通过：`POST /a/activity/enrollments/{id}/approve` `[activity:enroll-audit]`
- 审核拒绝（填原因）：`POST /a/activity/enrollments/{id}/reject` `[activity:enroll-audit]`
- 删除报名：`DELETE /a/activity/enrollments/{id}` `[activity:enroll-delete]`
可视化：报名状态色标签；通过/拒绝行内按钮；导出按钮在工具栏；「代报名来源」列显示代报人。

### 9. 现场管理（活动负责人 / 签到 / 签退 / 违规 / 总结）
进入某活动的现场管理面板：
- 指派负责人：`POST /a/activity/activities/{id}/leaders`（leaderType 1报名志愿者/2管理团队 + refId）`[activity:leader-assign]`
- 负责人列表：`GET /a/activity/activities/{id}/leaders` `[activity:manage]`
- 取消指派：`DELETE /a/activity/activities/{id}/leaders/{leaderId}` `[activity:leader-assign]`
- 活动开始：`POST /a/activity/activities/{id}/start` `[activity:manage]`
- 活动结束：`POST /a/activity/activities/{id}/finish` `[activity:manage]`
- 统一签退（全部或指定人）：`POST /a/activity/activities/{id}/check-outs` `[activity:manage]`
- 标记到位状态：`PATCH /a/activity/activities/{id}/attendances/{volunteerId}`（attendStatus 1正常/2请假/3迟到/4缺席）`[activity:manage]`
- 记录违规（**自由文本** description 必填 + 可选 type 0~4）：`POST /a/activity/activities/{id}/attendances/{volunteerId}/violations` `[activity:manage]`
- 上传活动总结（文字 + 图片，须活动已结束）：`POST /a/activity/activities/{id}/summary` `[activity:manage]`
- 下架活动留言：`DELETE /a/activity/messages/{id}` `[activity:manage]`
可视化：顶部**活动运行状态进度（未开始→进行中→已结束）**；签到名单表格（姓名/电话/学校/签到/签退/到位状态/违规数）；「开始/结束/统一签退」为面板主操作按钮（按 run_status 启用/禁用）；违规记录明细列表（名字 / 记录人 / 记录明细 / 记录时间）。

### 10. 服务记录与积分（秘书部）
- 服务记录大板块（筛选 activityId/volunteerId/secretaryStatus）：`GET /a/activity/service-records`（登录即可）
- 待确认列表（已签退未确认）：`GET /a/activity/service-records/pending` `[activity:service-confirm]`
- 秘书部确认时长：`POST /a/activity/attendances/{id}/confirm` `[activity:service-confirm]`
- 发放积分（可减半/不发）：`POST /a/activity/attendances/{id}/points` `[activity:points-grant]`
可视化：服务时长、积分、确认状态、发放状态用标签；确认 / 发放为行内按钮；发积分弹「正常/减半/不发」选择。

### 11. 考勤变更二次审核（组织部申请 → 部长审核）
- 申请改签到/签退/积分（待审，不立即生效）：`POST /a/activity/attendances/{id}/changes` `[activity:attendance-edit]`
- 变更申请列表（status 0待审/1通过/2拒绝）：`GET /a/activity/attendance-changes`（登录即可）
- 审核通过（应用变更）：`POST /a/activity/attendance-changes/{id}/approve` `[activity:attendance-audit]`
- 审核拒绝：`POST /a/activity/attendance-changes/{id}/reject` `[activity:attendance-audit]`
可视化：**变更对照（原值 → 新值）**；待审/通过/拒绝色标签；通过/拒绝双按钮（可填审核意见）。

### 12. 活动补录审核（历史活动补登考勤）
- 申请补录（搜手机号/身份证 + 时间段，待审）：`POST /a/activity/activities/{id}/backfills` `[activity:backfill]`
- 补录申请列表（status 0/1/2）：`GET /a/activity/backfills`（登录即可）
- 审核通过（落已确认考勤行）：`POST /a/activity/backfills/{id}/approve` `[activity:backfill-audit]`
- 审核拒绝：`POST /a/activity/backfills/{id}/reject` `[activity:backfill-audit]`
可视化：补录申请显示「定位到的志愿者 + 活动 + 时间段 + 拟发积分/时长」；审核流同上。

---

## 七、状态与枚举色板（用于标签着色）

- **审核状态**（报名/建组/分队加入/考勤变更/补录通用）：`待审`=橙、`通过`=绿、`拒绝`=红。变更/补录状态码：`0待审 / 1通过 / 2拒绝`。
- **活动运行状态** run_status：`0 未开始`=灰、`1 进行中`=蓝、`2 已结束`=绿。
- **到位状态** attendStatus：`1 正常`=绿、`2 请假`=橙、`3 迟到`=黄、`4 缺席`=红（缺席系统自动记违规）。
- **秘书确认/积分发放**：`未确认/未发放`=灰、`已确认/已发放`=绿。
- **负责人类型** leaderType：`1 报名志愿者` / `2 管理团队`，用不同 tag。
- **管理团队标记** manager_flag：`1` 显示「管理团队」徽标。
- 活动状态、报名状态等具体 int 取值**以接口返回为准**，前端做值→文案+颜色的映射表，未知值兜底为灰色原值。

---

## 八、通用组件（请抽象复用）

1. **审核流卡片/行**：待办列表 + 「通过 / 拒绝（带原因输入）」双按钮 + 状态标签 —— 报名、建组、分队加入、考勤变更、补录五处共用一套。
2. **数据表格**：分页（page/size）、筛选区、空/加载/错误三态、行操作下拉。
3. **抽屉表单**：新增/编辑/详情统一右侧抽屉，必填校验，提交 loading。
4. **权限包裹组件**：`<Auth code="activity:publish">…</Auth>`，无权限不渲染；菜单同理过滤。
5. **二次确认**：删除/解散/拒绝/发积分/取消指派等统一 confirm。
6. **Excel 导出按钮**：触发浏览器下载。
7. **文件/图片上传**：拖拽上传 + 预览（轮播图、活动总结图、批量导入 Excel）。

---

## 九、交付物

1. 完整**导航树 + 各页面**（列表/抽屉表单/详情/审核流），覆盖上面**全部端点与按钮**。
2. 一套**可视化语言**：状态色板、标签、运行状态进度、原值→新值对照、时间线。
3. **权限驱动**的菜单与按钮显隐（给出超管 / 某部门子账号两种视角的示意）。
4. 响应式、空/加载/错误态、二次确认齐全。
5. 先给**整体设计稿/线框 + 关键页面高保真**（概览、活动列表+发布、现场管理、一个审核流页、子账号权限分配），再落地为可运行前端代码。

> 约束：**只实现上面列出的已落地接口与按钮**；志愿者列表 CRUD、协会数据看板、组织架构后台接口等**尚未实现**，如要预留位请明确标注「待接口」，不要伪造数据接口。

### 接后端前的 0 号前置任务（这几个接口当前后端没有，可运行实现前必须先 mock 或补齐）

1. **当前管理员资料 + 权限码**：如 `GET /a/auth/me` → `{ adminId, name, dept, isSuperAdmin, permissionCodes:[] }`。**整套权限驱动 UI 依赖它**；没有它就先 mock（超管 / 部门两套）。
2. **通用上传**：如 `POST /a/files/upload`（或后端给 OSS STS 直传）。所有传图/裁剪交互依赖它；没有它就手填 URL 或前端直传。
3. **全局待审分队加入申请**（可选）：补一个不按分队 ID 的全局列表，否则概览「待审分队加入」卡片做不出、分队审批必须先列分队（需 `squad-manage`）。
