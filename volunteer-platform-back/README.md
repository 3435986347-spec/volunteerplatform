# 恒德志愿者平台 · 运营管理后台（前端原型）

雷州市恒德爱心公益协会 · 志愿者平台管理后台的高保真可交互原型。
基于 **Ant Design v5 视觉语言**手工复刻（浅色、蓝色强调、信息密度优先），React 18 + 纯 `React.createElement`（无 JSX 语法、无运行期 Babel），本地 vendor React 生产版，无构建步骤。

> 起初是**前端设计原型**（数据为 `HD.*` 内置 mock）；现**全部业务页已接入真实 `/a` 接口**（M1–M4 完成，见 `联调指南.md`），mock 数据文件 `data.js` 已删除——仅保留 `preview-identities.js` 的 4 套预览身份供 Tweaks「身份视角」演示，真实登录后以 `GET /a/auth/me` 的权限码为准。`assets/api.js` 为统一请求封装，经 `window.__API_BASE__` 适配同源 / nginx 分离部署。

---

## 一、快速运行

由于主文件通过 `<script src>` 引入 `assets/` 下的分文件，**直接双击 HTML 可能受浏览器同源策略限制**（`file://` 下加载本地脚本被拦）。推荐起一个本地静态服务：

```bash
# 在工程根目录执行任意一种（避开后端的 8080）：
python3 -m http.server 5500
# 或
npx serve .
```

然后浏览器打开：`http://localhost:5500/index.html`（非 80/443 端口时 `api.js` 自动直连后端 `:8080`，见 `index.html` 的 `__API_BASE__`）

> React 生产版已本地化到 `assets/vendor/`，**离线/内网可直接打开**，无需联网。

---

## 二、目录结构

```
index.html                      # 唯一入口，按顺序引入下列脚本
assets/
  styles.css                    # 设计令牌（CSS 变量）+ 全部组件样式
  vendor/                       # ★ 本地 React/ReactDOM 18 生产版（离线/内网可用，无 CDN）
  authz.js                      # ★ 前端权限判定 hasPerm(identity, code)（plain JS）
  preview-identities.js         # ★ 4 套预览身份（Tweaks「身份视角」演示用；真实登录覆盖）
  api.js                        # ★ 统一请求封装（/api 前缀、Sa-Token、{code,message,data}、401、上传）
  icons.js                      # 描边图标集（window.Icon / ICONS）
  ui-core.js                    # 按钮/标签/表单控件/状态映射/卡片等原语
  ui-overlay.js                 # 表格/分页/抽屉/弹窗/确认/Toast/步骤/时间线/Auth 包裹
  ui-upload.js                  # 图片上传 + 预设比例裁剪 / 文件拖拽位
  tweaks-panel.js               # Tweaks 调节面板（身份/主色/密度/水印等）
  shell.js                      # 侧边栏(权限过滤+真实待办角标 TODO_SOURCES)/顶栏/水印/导航配置
  page-overview.js              # 概览（GET /a/data/dashboard 数据看板 + 权限受控待办计数卡）
  page-volunteers.js            # 志愿者管理（列表/详情/修改[仅超管]/停用·恢复/删除/导出）
  page-activities.js            # 活动列表/发布/编辑/复制/周期发布/历史发布/定位选点/留言
  page-activity-review.js       # 活动发布审核（小程序志愿者发布 → 后台审）
  page-enroll.js                # 报名管理（通过/拒绝/手动新增/导出/删除）
  page-audit.js                 # 服务记录与积分 / 考勤变更审核 / 活动补录审核
  page-org.js                   # 志愿小组 / 归属分队（含全局待审加入）
  page-org2.js                  # 子账号与权限 / 志愿者标记与授权
  page-publicity.js             # 轮播图 / 公告 / 文件下载
  app.js                        # 路由 + 登录页（真实 /a/auth/login + me）+ Tweaks 接线 + 挂载
uploads/                        # 你上传的 prompt 与 url 文档（参考用）
```

脚本加载顺序（已在 `index.html` 写好）：本地 React/ReactDOM（生产版，`assets/vendor/`）→ `authz.js` + `preview-identities.js` → `window.__API_BASE__` → `api.js` → hooks 全局别名 → 各 UI/页面 `*.js`（普通 `<script>`，全部纯 `React.createElement`）→ `app.js`。

---

## 三、权限驱动的 UI（核心设计）

整套界面**由权限码驱动**菜单与按钮的显隐。已接真实链路：登录 → `GET /a/auth/me` 拿 `permissionCodes` → 据此渲染。

- `assets/authz.js` 暴露判定函数 `hasPerm(identity, code)`（code 为 null=仅需登录；数组=任一命中）。
- 超管 `permissionCodes = ["*"]`，命中一切。
- 组件级用 `Auth` 组件（`code="activity:publish"`）包裹按钮；菜单项在 `shell.js` 的 `NAV_GROUPS` 里带 `code` 字段过滤；侧边栏待办角标与概览待办卡共用 `shell.js` 的 `TODO_SOURCES`（计数端点 + countPerm，仅对有权账号发请求）。
- 权限点目录以后端 `GET /a/organization/permissions` 为准（前端不再维护 mock 目录）。

**Tweaks「身份视角」**内置 4 套预览身份（`assets/preview-identities.js`），用于未登录时演示不同账号看到的界面；真实登录后以 `me` 返回的身份为准：

| 身份 | 部门 | 权限范围（示例） |
|---|---|---|
| 陈国栋 | 理事会 | 超级管理员，`["*"]` |
| 林海燕 | 组织部 | 报名审核 / 活动发布审核 / 建组审批 / 分队管理与审核 |
| 吴敏 | 秘书部 | 服务时长确认 / 积分发放 / 考勤变更申请 |
| 黄梓萱 | 宣传部 | 轮播图 / 公告 / 文件下载 |

---

## 四、Tweaks 面板

工具栏开启 Tweaks 后，可实时调：身份视角、主强调色、表格密度（紧凑/标准）、斑马纹、侧边栏折叠、防泄密水印开关。默认值在 `app.js` 的 `TWEAK_DEFAULTS`。

---

## 五、模块 ↔ 接口对照（对照 url 文档 V1）

> 所有接口前缀：管理端 `/a`，志愿者端（小程序）`/v`；context-path `/api`。
> 现场管理（签到/签退/到位/违规/总结）在**小程序端** `/v/activity/managed-activities`，后台不实现。

### 用户 user
| 页面 | 接口 |
|---|---|
| 志愿者管理-列表 | `GET /a/user/volunteers?keyword=&gender=&squad=&political=&school=&grade=&page=&size=` |
| 详情 / 修改 | `GET\|PUT /a/user/volunteers/{id}` |
| 禁用·启用 | `PATCH /a/user/volunteers/{id}/status` `{status:0/1}` |
| 删除 / 重置密码 | `DELETE /a/user/volunteers/{id}`、`POST …/{id}/password/reset` |
| 导出 | `GET /a/user/volunteers/export` |

### 活动 activity
| 页面 | 接口 |
|---|---|
| 活动列表（默认排除待审4/驳回5） | `GET /a/activity/activities`（status 可筛 1已发布/2已结束/3已取消） |
| 发布（后台直发 status=1） | `POST /a/activity/activities` |
| 周期发布 / 历史发布 | `POST …/recurring`、`POST …/historical` |
| 详情 / 修改 / 删除 / 复制 | `GET\|PUT\|DELETE /a/activity/activities/{id}`、`POST …/{id}/copy` |
| **活动发布审核**（小程序志愿者发布 status=4） | `GET …/pending-reviews`、`GET …/{id}/review-detail`、`POST …/{id}/publish-approve`(→1)、`POST …/{id}/publish-reject`(→5) |
| 报名管理 | `GET\|POST /a/activity/activities/{id}/enrollments`、`GET …/enrollments/export`、`POST /a/activity/enrollments/{id}/approve\|reject`、`DELETE /a/activity/enrollments/{id}` |
| 指派负责人 | `POST\|GET /a/activity/activities/{id}/leaders`、`DELETE …/leaders/{leaderId}` |
| 服务记录与积分 | `GET /a/activity/service-records[/pending]`、`POST /a/activity/attendances/{id}/confirm`、`POST …/{id}/points` |
| 考勤变更审核 | `POST /a/activity/attendances/{id}/changes`、`GET /a/activity/attendance-changes`、`POST …/{id}/approve\|reject` |
| 活动补录审核 | `POST /a/activity/activities/{id}/backfills`、`GET /a/activity/backfills`、`POST …/{id}/approve\|reject` |
| 活动留言（详情内下架） | `DELETE /a/activity/messages/{id}` |

报名门槛字段：`requireMinJoinCount`（已参加次数）、`requireMinJoinMinutes`（已参加时长·分钟）；GPS：`lat`/`lng`/`checkInRadiusM`（默认 500）。

### 组织 organization
| 页面 | 接口 |
|---|---|
| 子账号与权限 | `GET\|POST /a/organization/sub-accounts`、`GET\|PUT\|DELETE …/{id}`、`PUT …/{id}/permissions`、`POST …/{id}/password/reset` |
| 权限目录 | `GET /a/organization/permissions[/volunteer-grantable]` |
| 志愿小组 | `GET /a/organization/groups`、`DELETE …/{id}`、`PUT …/{id}/leader`、`GET …/{id}/leader-history`、`POST …/import`、建组申请 `GET …/applications`、`POST …/applications/{id}/approve\|reject` |
| 归属分队 | `GET\|POST /a/organization/squads`、`PUT\|DELETE …/{id}`、**全局待审** `GET …/squads/applications`(需 org:squad-audit)、单分队 `GET …/{id}/applications`、`POST …/applications/{id}/approve\|reject` |
| 志愿者标记与授权 | `PUT /a/organization/volunteers/{id}/manager-flag`、`GET\|PUT …/{id}/permissions`（授权仅超管，目标须 manager_flag=1） |

### 公示 publicity
| 页面 | 接口 |
|---|---|
| 轮播图 | `GET\|POST /a/publicity/banners`、`PUT\|DELETE …/{id}`、`PATCH …/{id}/sort` |
| 公告 | `GET\|POST /a/publicity/announcements`、`PUT\|DELETE …/{id}` |
| 文件下载 | `GET\|POST /a/publicity/files`、`DELETE …/{id}`、`PATCH …/{id}/access` |

### 通用 / 认证
| 用途 | 接口 |
|---|---|
| 登录 / 当前账号 / 改密 | `POST /a/auth/login`、`GET /a/auth/me`、`PUT /a/auth/password`、找回 `PUT /a/auth/password/reset` |
| 通用上传（裁剪后回填 url） | `POST /a/files/upload`（multipart `file` + `dir`，按 dir 双门槛鉴权） |
| 后台数据看板 | `GET /a/data/dashboard` |

---

## 六、对接状态

**全部业务页已接真实接口（M1–M4 完成）**：登录/`me` 权限驱动 → 公示 → 活动（含发布审核/报名/审核流/组织/授权）→ 志愿者管理 → 概览数据看板与待办计数。约定：响应体 `code===200` 判定、分页 `page/size` + `PageResult{records,total}`、`Long`→字符串、日期时间 `yyyy-MM-dd HH:mm:ss`、上传走 `POST /a/files/upload` 业务表只存 `url`。详见 `联调指南.md`。

---

## 七、备注

- 现场管理（签到/签退/到位/违规/总结）已按需求归到**小程序端**，后台不实现。
- 字体使用 `FZDFS--GBK1-0`，带 PingFang / 系统字体兜底；未安装该字体的环境会优雅回退。
- 状态文案：志愿者/子账号账号态用「正常使用 / 已禁用」，上下架（轮播/文件）用「已上架 / 已下架」。
