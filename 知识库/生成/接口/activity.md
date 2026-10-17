<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# activity 模块的接口

106 个端点。模块说明见 [`知识库/模块/activity.md`](../../模块/activity.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/activity/activities` |  | 活动列表/推荐（仅已发布；有名额优先排序，带报名人数/有名额标记） | `ActivityController` |
| POST | `/v/activity/activities` | `activity:publish` | 提交活动（管理团队志愿者，需 activity:publish；提交后待后台审核才上线） | `ActivityController` |
| GET | `/v/activity/activities/{activityId}/album` |  | 某个活动的相册（没有就自动建，标题「编号 活动名称」） | `AlbumController` |
| GET | `/v/activity/activities/{id}` |  | 活动详情（含时间段/报名须知，仅已发布） | `ActivityController` |
| POST | `/v/activity/activities/{id}/check-in` |  | 自助签到（GPS 距活动 ≤ 半径 + 时间窗口） | `AttendanceController` |
| POST | `/v/activity/activities/{id}/check-out` |  | 自助签退（扫签退码 + GPS 距活动 ≤ 半径 + 结束后2h内；算服务时长） | `AttendanceController` |
| POST | `/v/activity/activities/{id}/confirm-home` |  | 确认到家（活动结束后；超时仅记录） | `AttendanceController` |
| DELETE | `/v/activity/activities/{id}/enroll` |  | 取消报名（整活动取消） | `EnrollmentController` |
| POST | `/v/activity/activities/{id}/enroll` |  | 报名（body 指定时间段） | `EnrollmentController` |
| GET | `/v/activity/activities/{id}/messages` |  | 活动留言列表 | `ActivityMessageController` |
| POST | `/v/activity/activities/{id}/messages` |  | 发表活动留言 | `ActivityMessageController` |
| POST | `/v/activity/activities/{id}/proxy-enrollments` |  | 代报名：同小组成员之间互相帮报名 | `EnrollmentController` |
| POST | `/v/activity/activities/{id}/review` |  | 评价活动与负责人（活动评分/负责人评分/评论） | `AttendanceController` |
| GET | `/v/activity/albums` |  | 相册列表（?keyword= 搜标题；带张数、封面、我能不能传） | `AlbumController` |
| GET | `/v/activity/albums/{id}` |  | 相册详情 | `AlbumController` |
| GET | `/v/activity/albums/{id}/batches` |  | 上传记录（谁传的、传了多少张） | `AlbumController` |
| GET | `/v/activity/albums/{id}/photos` |  | 相册照片（全部预览，新的在前） | `AlbumController` |
| POST | `/v/activity/albums/{id}/photos` |  | 上传一批照片（本活动志愿者 / 负责人 / 管理团队；syncSocial 默认 true 同步到交流平台；上传积分需审核） | `AlbumController` |
| GET | `/v/activity/managed-activities` |  | 我负责的活动场次 | `ManagedActivityController` |
| GET | `/v/activity/managed-activities/{id}` |  | 负责的活动详情（志愿者名单+考勤） | `ManagedActivityController` |
| PATCH | `/v/activity/managed-activities/{id}/attendances/{volunteerId}` |  | 标记到位状态（正常/请假/迟到/缺席） | `ManagedActivityController` |
| PATCH | `/v/activity/managed-activities/{id}/attendances/{volunteerId}/evaluation` |  | 负责人评价志愿者 | `ManagedActivityController` |
| POST | `/v/activity/managed-activities/{id}/attendances/{volunteerId}/violations` |  | 记录违规 | `ManagedActivityController` |
| GET | `/v/activity/managed-activities/{id}/check-in-qr` |  | 活动签到二维码（PNG data URL，负责人展示供志愿者扫码） | `ManagedActivityController` |
| GET | `/v/activity/managed-activities/{id}/check-out-qr` |  | 活动签退二维码（PNG data URL，负责人展示供志愿者扫码签退） | `ManagedActivityController` |
| POST | `/v/activity/managed-activities/{id}/check-outs` |  | 统一签退（指定场次的全部或指定志愿者） | `ManagedActivityController` |
| POST | `/v/activity/managed-activities/{id}/finish` |  | 点击活动结束 | `ManagedActivityController` |
| POST | `/v/activity/managed-activities/{id}/start` |  | 点击活动开始 | `ManagedActivityController` |
| POST | `/v/activity/managed-activities/{id}/summary` |  | 上传活动总结（文字+图片） | `ManagedActivityController` |
| GET | `/v/activity/managed-activities/{id}/violations` |  | 违规记录明细（名字/记录人/记录明细/记录时间） | `ManagedActivityController` |
| GET | `/v/activity/my-activities` |  | 我的活动列表（名称/时间段/负责人/签到/是否违规/考勤） | `MyActivityController` |
| GET | `/v/activity/my-activities/{id}` |  | 我的活动详情（含考勤 + 签到二维码数据 + 确认到家 + 评价回显） | `MyActivityController` |
| GET | `/v/activity/my-enrollments` |  | 我的报名列表（可按状态筛选） | `EnrollmentController` |
| GET | `/v/activity/points` |  | 我的积分总览（总积分/已使用积分/当前余额） | `PointController` |
| GET | `/v/activity/points/records` |  | 我的积分明细（分页，可搜索说明并按来源类型/时间区间筛选） | `PointController` |
| GET | `/v/activity/rosters` |  | 正在公示的名单（组织部确认名单后到活动开始前；按活动开始时间） | `RosterController` |
| GET | `/v/activity/rosters/{activityId}` |  | 某个活动正在公示的名单 | `RosterController` |
| GET | `/v/activity/service-records` |  | 我的服务记录（活动名称/签到/签退/时长） | `AttendanceController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/activity/activities` | `activity:menu` | 活动列表（可按状态/关键词筛选） | `ActivityAdminController` |
| POST | `/a/activity/activities` | `activity:publish` | 发布活动 | `ActivityAdminController` |
| POST | `/a/activity/activities/historical` | `activity:publish` | 历史活动发布（补录之前未发布过的已发生活动；志愿者端不可见，仅作补录载体） | `ActivityAdminController` |
| GET | `/a/activity/activities/pending-reviews` | `activity:publish-audit` | 活动发布审核列表（带提交人；status 默认 4 待审核，可传 5 看已驳回） | `ActivityAdminController` |
| POST | `/a/activity/activities/recurring` | `activity:publish` | 固定日期周期发布（按显式日期/星期几规则批量发布多场） | `ActivityAdminController` |
| DELETE | `/a/activity/activities/{id}` | `activity:delete` | 删除活动 | `ActivityAdminController` |
| GET | `/a/activity/activities/{id}` | `activity:menu` | 活动详情 | `ActivityAdminController` |
| PUT | `/a/activity/activities/{id}` | `activity:edit` | 修改活动 | `ActivityAdminController` |
| GET | `/a/activity/activities/{id}/attendance-roster` | `activity:manage` | 现场考勤名单（已通过的报名 × 考勤：签到签退 / 到位 / 时长 / 违规数；没签到的人也在，才标得了缺席） | `ActivityManageAdminController` |
| PATCH | `/a/activity/activities/{id}/attendances/{volunteerId}` | `activity:manage` | 标记到位状态 | `ActivityManageAdminController` |
| POST | `/a/activity/activities/{id}/attendances/{volunteerId}/violations` | `activity:manage` | 记录违规 | `ActivityManageAdminController` |
| POST | `/a/activity/activities/{id}/backfills` | `activity:backfill` | 组织部申请补录（搜手机号/身份证 + 时间段；待审，不立即生效） | `ActivityBackfillAdminController` |
| POST | `/a/activity/activities/{id}/cancel` | `activity:delete` | 取消活动（已有报名记录时使用，保留报名与考勤数据） | `ActivityAdminController` |
| POST | `/a/activity/activities/{id}/check-outs` | `activity:manage` | 统一签退（指定场次的全部或指定志愿者） | `ActivityManageAdminController` |
| POST | `/a/activity/activities/{id}/copy` | `activity:publish` | 复制活动 | `ActivityAdminController` |
| GET | `/a/activity/activities/{id}/enrollment-slots` | `activity:enroll-view` | 活动时间段列表（报名域，供手动新增报名选时间段；按 enroll-view 鉴权，不需 activity:menu） | `EnrollmentAdminController` |
| GET | `/a/activity/activities/{id}/enrollments` | `activity:enroll-view` | 报名列表（可按状态筛选，按报名时间升序） | `EnrollmentAdminController` |
| POST | `/a/activity/activities/{id}/enrollments` | `activity:enroll-add` | 手动新增报名（管理员代加，越权补录） | `EnrollmentAdminController` |
| GET | `/a/activity/activities/{id}/enrollments/export` | `activity:enroll-export` | 导出报名名单（Excel） | `EnrollmentAdminController` |
| POST | `/a/activity/activities/{id}/finish` | `activity:manage` | 活动结束 | `ActivityManageAdminController` |
| GET | `/a/activity/activities/{id}/home-confirmations` | `activity:manage` | 到家名单（详细地址仅 activity:home-address 可见，其余只显示「已到家」） | `ActivityManageAdminController` |
| GET | `/a/activity/activities/{id}/leaders` | `activity:leader-assign` 或 `activity:manage` |  | `ActivityManageAdminController` |
| POST | `/a/activity/activities/{id}/leaders` | `activity:leader-assign` | 指派活动负责人（志愿者或管理团队） | `ActivityManageAdminController` |
| DELETE | `/a/activity/activities/{id}/leaders/{leaderId}` | `activity:leader-assign` | 取消指派 | `ActivityManageAdminController` |
| GET | `/a/activity/activities/{id}/messages` | `activity:menu` | 活动留言列表（管理端审阅，不限活动发布状态） | `ActivityMessageAdminController` |
| POST | `/a/activity/activities/{id}/publish-approve` | `activity:publish-audit` | 发布审核通过（小程序提交的活动上线） | `ActivityAdminController` |
| POST | `/a/activity/activities/{id}/publish-reject` | `activity:publish-audit` | 发布审核驳回（body 可填原因） | `ActivityAdminController` |
| GET | `/a/activity/activities/{id}/review-detail` | `activity:publish-audit` | 待审核/驳回活动详情（完整字段，供审核者决定通过/驳回，无需 activity:menu） | `ActivityAdminController` |
| GET | `/a/activity/activities/{id}/roster` | `activity:enroll-view` | 预览名单（不看公示窗口，电话全显示） | `RosterAdminController` |
| DELETE | `/a/activity/activities/{id}/roster/publish` | `activity:enroll-audit` | 撤回公示 | `RosterAdminController` |
| POST | `/a/activity/activities/{id}/roster/publish` | `activity:enroll-audit` | 确认名单、开始公示（已发布且还没开始的活动；已公示的再点不报错） | `RosterAdminController` |
| POST | `/a/activity/activities/{id}/start` | `activity:manage` | 活动开始 | `ActivityManageAdminController` |
| POST | `/a/activity/activities/{id}/summary` | `activity:manage` | 上传活动总结（文字+图片） | `ActivityManageAdminController` |
| GET | `/a/activity/album-batches` | `activity:album-points-audit` | 上传积分审核队列（?status= 0 待审核（默认，先传的在前）/ 1 已通过 / 2 已驳回） | `AdminAlbumController` |
| POST | `/a/activity/album-batches/{id}/approve` | `activity:album-points-audit` | 通过：按这一批现在还没删的张数与规则发积分（个人每相册有上限），返回实际发了几分 | `AdminAlbumController` |
| POST | `/a/activity/album-batches/{id}/reject` | `activity:album-points-audit` | 驳回（不发积分） | `AdminAlbumController` |
| DELETE | `/a/activity/album-photos/{photoId}` | `activity:album-photo-delete` | 删除照片 | `AdminAlbumController` |
| GET | `/a/activity/album-rule` | `activity:album` 或 `activity:album-points-audit` | 上传积分规则 | `AdminAlbumController` |
| PUT | `/a/activity/album-rule` | `activity:album` | 修改上传积分规则（每 N 张给 M 分，每人每相册上限；只影响之后审核的批次） | `AdminAlbumController` |
| GET | `/a/activity/albums` | `activity:album` 或 `activity:album-delete` 或 `activity:album-download` 或 `activity:album-photo-delete` 或 `activity:album-points-audit` | 相册列表（?keyword= 搜标题） | `AdminAlbumController` |
| POST | `/a/activity/albums` | `activity:album` | 新增相册（给 activityId 就是那个活动的相册、已有直接返回；否则按 title 建不挂活动的） | `AdminAlbumController` |
| DELETE | `/a/activity/albums/{id}` | `activity:album-delete` | 删除相册 | `AdminAlbumController` |
| GET | `/a/activity/albums/{id}/batches` | `activity:album` 或 `activity:album-points-audit` | 上传记录 | `AdminAlbumController` |
| GET | `/a/activity/albums/{id}/download` | `activity:album-download` | 批量下载：全部照片的地址与建议文件名 | `AdminAlbumController` |
| GET | `/a/activity/albums/{id}/photos` | `activity:album` 或 `activity:album-delete` 或 `activity:album-download` 或 `activity:album-photo-delete` 或 `activity:album-points-audit` | 相册照片 | `AdminAlbumController` |
| POST | `/a/activity/albums/{id}/photos` | `activity:album` | 后台上传（不参与积分、不同步社区） | `AdminAlbumController` |
| GET | `/a/activity/attendance-changes` |  | 变更申请列表（可按状态筛选 0待审/1通过/2拒绝） | `AttendanceChangeAdminController` |
| POST | `/a/activity/attendance-changes/{id}/approve` | `activity:attendance-audit` | 部长二次审核通过（应用变更） | `AttendanceChangeAdminController` |
| POST | `/a/activity/attendance-changes/{id}/reject` | `activity:attendance-audit` | 部长二次审核拒绝 | `AttendanceChangeAdminController` |
| POST | `/a/activity/attendances/{id}/changes` | `activity:attendance-edit` | 组织部申请改签到/签退/积分（待审，不立即生效） | `AttendanceChangeAdminController` |
| POST | `/a/activity/attendances/{id}/confirm` | `activity:service-confirm` | 秘书部确认时长 | `ServiceRecordAdminController` |
| POST | `/a/activity/attendances/{id}/points` | `activity:points-grant` | 发放积分（违规可减半/不发） | `ServiceRecordAdminController` |
| GET | `/a/activity/backfills` |  | 补录申请列表（可按状态筛选 0待审/1通过/2拒绝） | `ActivityBackfillAdminController` |
| POST | `/a/activity/backfills/{id}/approve` | `activity:backfill-audit` | 部长审核通过（落已确认考勤行；普通活动发积分/历史只记时长） | `ActivityBackfillAdminController` |
| POST | `/a/activity/backfills/{id}/reject` | `activity:backfill-audit` | 部长审核拒绝 | `ActivityBackfillAdminController` |
| GET | `/a/activity/enrollments` | `activity:enroll-view` | 全局报名列表（跨活动，可按状态筛选，按报名时间倒序；概览待审报名计数用 size=1 取 total） | `EnrollmentAdminController` |
| DELETE | `/a/activity/enrollments/{id}` | `activity:enroll-delete` | 删除报名记录 | `EnrollmentAdminController` |
| POST | `/a/activity/enrollments/{id}/approve` | `activity:enroll-audit` | 审核通过 | `EnrollmentAdminController` |
| POST | `/a/activity/enrollments/{id}/reject` | `activity:enroll-audit` | 审核拒绝（body 填拒绝原因） | `EnrollmentAdminController` |
| DELETE | `/a/activity/messages/{id}` | `activity:manage` | 下架活动留言（逻辑删除） | `ActivityMessageAdminController` |
| GET | `/a/activity/points` | `activity:points-view` | 查某志愿者积分总览（总积分/已使用积分/当前余额） | `PointAdminController` |
| POST | `/a/activity/points/adjust` | `activity:points-adjust` | 手工调整积分（可正可负，须填原因与幂等键 requestId，落操作人审计） | `PointAdminController` |
| GET | `/a/activity/points/records` | `activity:points-view` | 查某志愿者积分明细（分页，可搜索说明并按来源类型/时间区间筛选） | `PointAdminController` |
| GET | `/a/activity/service-records` |  | 服务记录大板块（全员，可筛选活动/志愿者/确认状态） | `ServiceRecordAdminController` |
| GET | `/a/activity/service-records/pending` | `activity:service-confirm` | 待秘书部确认列表（已签退未确认） | `ServiceRecordAdminController` |
| GET | `/a/activity/violations` | `activity:violation-review` | 违规审核队列（缺省只看待审核） | `ViolationReviewAdminController` |
| POST | `/a/activity/violations/{id}/approve` | `activity:violation-review` | 审核通过（此后该条才对志愿者可见，也才够格作为开处罚单的依据） | `ViolationReviewAdminController` |
| POST | `/a/activity/violations/{id}/reject` | `activity:violation-review` | 驳回（须填原因） | `ViolationReviewAdminController` |
