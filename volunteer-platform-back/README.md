# 恒德志愿者平台 · 运营管理后台（前端原型）

雷州市恒德爱心公益协会 · 志愿者平台管理后台的高保真可交互原型。
基于 **Ant Design v5 视觉语言**手工复刻（浅色、蓝色强调、信息密度优先），React 18 + 原生 JSX（浏览器内 Babel 转译），无构建步骤。

> 这是**前端设计原型**，所有数据为内置 mock，不连接后端。用于评审界面、交互、权限驱动逻辑与接口对照。

---

## 一、快速运行

由于主文件通过 `<script src>` 引入 `assets/` 下的分文件，**直接双击 HTML 可能受浏览器同源策略限制**（`file://` 下加载本地脚本被拦）。推荐起一个本地静态服务：

```bash
# 在工程根目录执行任意一种：
python3 -m http.server 8080
# 或
npx serve .
```

然后浏览器打开：`http://localhost:8080/恒德志愿者后台.html`

> 首次打开需联网（React / Babel 走 CDN，带完整性校验）。之后浏览器有缓存。

---

## 二、目录结构

```
恒德志愿者后台.html              # 主入口，按顺序引入下列脚本
assets/
  styles.css                    # 设计令牌（CSS 变量）+ 全部组件样式
  data.js                       # ★ Mock 数据 + 权限码目录 + 4 套身份（plain JS）
  icons.jsx                     # 描边图标集（window.Icon / ICONS）
  ui-core.jsx                   # 按钮/标签/表单控件/状态映射/卡片等原语
  ui-overlay.jsx                # 表格/分页/抽屉/弹窗/确认/Toast/步骤/时间线/Auth 包裹
  ui-upload.jsx                 # 图片上传 + 预设比例裁剪 / 文件拖拽位
  tweaks-panel.jsx              # Tweaks 调节面板（身份/主色/密度/水印等）
  shell.jsx                     # 侧边栏(权限过滤)/顶栏(面包屑·身份切换·账号)/水印/导航配置
  page-overview.jsx             # 概览（待办计数卡 + 接口对齐说明）
  page-volunteers.jsx           # 志愿者管理（列表/详情/修改/禁用/删除/重置/导出）
  page-activities.jsx           # 活动列表/发布/编辑/复制/周期发布/历史发布/定位选点/留言
  page-activity-review.jsx      # 活动发布审核（小程序志愿者发布 → 后台审）
  page-enroll.jsx               # 报名管理（通过/拒绝/手动新增/导出/删除）
  page-audit.jsx                # 服务记录与积分 / 考勤变更审核 / 活动补录审核
  page-org.jsx                  # 志愿小组 / 归属分队（含全局待审加入）
  page-org2.jsx                 # 子账号与权限 / 志愿者标记与授权
  page-publicity.jsx            # 轮播图 / 公告 / 文件下载
  app.jsx                       # 路由 + 登录页 + Tweaks 接线 + 挂载
uploads/                        # 你上传的 prompt 与 url 文档（参考用）
```

脚本加载顺序（已在主 HTML 写好）：React/ReactDOM/Babel → `data.js` → hooks 全局别名 → 各 `*.jsx`（babel）→ `app.jsx`。

---

## 三、权限驱动的 UI（核心设计）

整套界面**由权限码驱动**菜单与按钮的显隐。联调时：登录 → 调 `GET /a/auth/me` 拿 `permissionCodes` → 据此渲染。

- `assets/data.js` 的 `PERM_CATALOG` 是权限点目录，`hasPerm(identity, code)` 是判定函数。
- 超管 `permissionCodes = ["*"]`，命中一切。
- 组件级用 `<Auth code="activity:publish">…</Auth>` 包裹按钮；菜单项在 `shell.jsx` 的 `NAV_GROUPS` 里带 `code` 字段过滤。

**顶栏「身份切换」**内置 4 套身份用于预览不同账号看到的界面（替代联调前缺失的真实登录态）：

| 身份 | 部门 | 权限范围（示例） |
|---|---|---|
| 陈国栋 | 理事会 | 超级管理员，`["*"]` |
| 林海燕 | 组织部 | 报名审核 / 活动发布审核 / 建组审批 / 分队管理与审核 |
| 吴敏 | 秘书部 | 服务时长确认 / 积分发放 / 考勤变更申请 |
| 黄梓萱 | 宣传部 | 轮播图 / 公告 / 文件下载 |

---

## 四、Tweaks 面板

工具栏开启 Tweaks 后，可实时调：身份视角、主强调色、表格密度（紧凑/标准）、斑马纹、侧边栏折叠、防泄密水印开关。默认值在 `app.jsx` 的 `TWEAK_DEFAULTS`。

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

## 六、对接后端的建议步骤

1. **先接 `GET /a/auth/me`**：登录后拿 `permissionCodes`，替换顶栏「身份切换」的 mock，驱动真实菜单/按钮。
2. **登录链路**：`POST /a/auth/login` 拿 Sa-Token → 请求头带 `Authorization` → 调 `me`。
3. **列表接入**：统一响应体判定（`code===200`）、分页 `page/size` 与 `PageResult{records,total}`，把各 `page-*.jsx` 的 `HD.*` mock 换成 `fetch`。
4. **上传**：裁剪完成后走 `POST /a/files/upload`，业务表只存返回的 `url`。

---

## 七、备注

- 现场管理（签到/签退/到位/违规/总结）已按需求归到**小程序端**，后台不实现。
- 字体使用 `FZDFS--GBK1-0`，带 PingFang / 系统字体兜底；未安装该字体的环境会优雅回退。
- 状态文案：志愿者/子账号账号态用「正常使用 / 已禁用」，上下架（轮播/文件）用「已上架 / 已下架」。
