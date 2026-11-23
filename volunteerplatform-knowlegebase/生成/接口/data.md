<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# data 模块的接口

14 个端点。模块说明见 [`知识库/模块/data.md`](../../模块/data.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| POST | `/v/data/complaints` |  | 提交投诉建议（按类别进部门：活动类 → 组织部 / 协会内部类 → 秘书部 / 社区类 → 宣传部；24 小时内条数有限；协会发布了问卷时带答卷） | `ComplaintController` |
| GET | `/v/data/complaints/mine` |  | 我的投诉建议 | `ComplaintController` |
| GET | `/v/data/complaints/{id}` |  | 我的一条投诉建议详情（含处理进度与答复） | `ComplaintController` |
| GET | `/v/data/dashboard` |  | 首页数据看板（注册人数/场次/时长/参与人次/管理团队/分队数） | `VolunteerDashboardController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/data/complaints` | `data:complaint` 或 `data:complaint-all` | 工单列表（只有 data:complaint 的账号只看得到当前在本部门的；department 参数对它无效） | `AdminComplaintController` |
| GET | `/a/data/complaints/departments` | `data:complaint` 或 `data:complaint-all` | 可流转的部门（当前有启用后台账号的部门） | `AdminComplaintController` |
| GET | `/a/data/complaints/{id}` | `data:complaint` 或 `data:complaint-all` | 工单详情（全部进度含流转理由与内部备注、提交人电话、问卷答卷） | `AdminComplaintController` |
| POST | `/a/data/complaints/{id}/accept` | `data:complaint` 或 `data:complaint-all` | 受理（待受理 → 处理中） | `AdminComplaintController` |
| POST | `/a/data/complaints/{id}/notes` | `data:complaint` 或 `data:complaint-all` | 内部备注（仅后台可见） | `AdminComplaintController` |
| POST | `/a/data/complaints/{id}/reply` | `data:complaint` 或 `data:complaint-all` | 答复并办结（发站内提示 + 短信 COMPLAINT_REPLIED） | `AdminComplaintController` |
| POST | `/a/data/complaints/{id}/transfer` | `data:complaint` 或 `data:complaint-all` | 流转到其他部门（回到待受理；理由仅后台可见） | `AdminComplaintController` |
| GET | `/a/data/dashboard` |  | 后台数据概览（注册人数/场次/时长/参与人次/管理团队/分队数） | `AdminDashboardController` |
| GET | `/a/data/donation-summary` |  | 捐赠数据汇总（Row 79：微心愿 / 结对 / 捐书活动；仅管理端） | `AdminDashboardController` |
| GET | `/a/data/summary` | `data:dashboard` | 数据汇总（Row 79 九块：平台 / 社区 / 爱心企业 / 志愿小组 / 相册 / 举报 / 微心愿 / 结对 / 捐书） | `AdminDashboardController` |
