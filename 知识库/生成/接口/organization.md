<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# organization 模块的接口

100 个端点。模块说明见 [`知识库/模块/organization.md`](../../模块/organization.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/organization/exams/attempts` |  | 我的考试历史 | `ExamController` |
| POST | `/v/organization/exams/attempts` |  | 交卷（没有主观题的当场出分，及格即获得资格） | `ExamController` |
| GET | `/v/organization/exams/attempts/{id}` |  | 我的一份答卷（自己的作答与总分，不含标准答案与逐题得分） | `ExamController` |
| GET | `/v/organization/exams/current` |  | 我的考试：当前开放的试卷（能考时带题目、不含答案）+ 我是不是临时负责人 + 能不能考 | `ExamController` |
| GET | `/v/organization/forms` |  | 可以填写的问卷（通用 / 评优评先，收集中且在时间窗内；带「我是否已提交」） | `FormController` |
| GET | `/v/organization/forms/scenes/{scene}/current` |  | 某个场景当前的问卷（2报名管理团队 / 3评优评先…；没有时 data 为空） | `FormController` |
| GET | `/v/organization/forms/{id}` |  | 问卷详情（含题目；已停止的对志愿者等于不存在） | `FormController` |
| POST | `/v/organization/forms/{id}/submissions` |  | 提交答卷（报名管理团队的问卷要随申请提交，不走这里） | `FormController` |
| GET | `/v/organization/forms/{id}/submissions/mine` |  | 我在这份问卷上的答卷 | `FormController` |
| GET | `/v/organization/groups` |  | 小组列表（每组带当前志愿者的 myStatus：空不在组里 / 0 待审 / 1 已加入） | `VolunteerGroupController` |
| POST | `/v/organization/groups` |  | 发起新小组 | `VolunteerGroupController` |
| GET | `/v/organization/groups/{id}` |  | 小组详情 | `VolunteerGroupController` |
| POST | `/v/organization/groups/{id}/join` |  | 申请加入小组 | `VolunteerGroupController` |
| GET | `/v/organization/groups/{id}/join-applications` |  | 待审核加入申请列表（仅组长/管理员可见，含申请人 memberId 供审批） | `VolunteerGroupController` |
| POST | `/v/organization/groups/{id}/leave` |  | 退出小组 | `VolunteerGroupController` |
| GET | `/v/organization/groups/{id}/members` |  | 小组成员列表 | `VolunteerGroupController` |
| DELETE | `/v/organization/groups/{id}/members/{memberId}` |  | 负责人/管理员移除成员 | `VolunteerGroupController` |
| DELETE | `/v/organization/groups/{id}/members/{memberId}/admin` |  | 组长取消管理员 | `VolunteerGroupController` |
| POST | `/v/organization/groups/{id}/members/{memberId}/admin` |  | 组长指定管理员（≤3 人） | `VolunteerGroupController` |
| POST | `/v/organization/groups/{id}/members/{memberId}/approve` |  | 负责人批准加入申请 | `VolunteerGroupController` |
| POST | `/v/organization/groups/{id}/members/{memberId}/reject` |  | 负责人拒绝加入申请 | `VolunteerGroupController` |
| POST | `/v/organization/manager-applications` |  | 提交报名管理团队申请 | `MyManagerApplicationController` |
| GET | `/v/organization/manager-applications/mine` |  | 我的报名管理团队申请（最近一条，状态回显；无则 null） | `MyManagerApplicationController` |
| GET | `/v/organization/my-permissions` |  | 我的权限码集合 | `MyPermissionController` |
| GET | `/v/organization/squads` |  | 分队列表（每个分队带当前志愿者的 belonged 已归属 / applying 待审核申请） | `SquadController` |
| POST | `/v/organization/squads/applications/{applicationId}/approve` |  | 分队负责人批准加入（只能审自己负责的分队，不能审自己的申请） | `SquadController` |
| POST | `/v/organization/squads/applications/{applicationId}/reject` |  | 分队负责人拒绝加入（可填原因，≤255 字） | `SquadController` |
| GET | `/v/organization/squads/led` |  | 我负责的分队（分队负责人用，只列启用中的） | `SquadController` |
| GET | `/v/organization/squads/{id}` |  | 分队详情 | `SquadController` |
| GET | `/v/organization/squads/{id}/applications` |  | 本分队的加入申请（仅分队负责人；status 不传只看待审，0待审/1已通过/2已拒绝） | `SquadController` |
| POST | `/v/organization/squads/{id}/applications` |  | 申请加入分队 | `SquadController` |
| GET | `/v/organization/structure` |  | 组织架构树（节点里的人：姓名 / 职位；已实名的人还能看到电话） | `StructureController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/auth/me` |  | 当前管理员资料 + 权限码（前端据此渲染菜单/按钮显隐） | `AdminMeController` |
| GET | `/a/organization/exam-attempts` | `org:exam-grade` 或 `org:temp-leader` | 答卷列表（status=1 为阅卷队列，先交的在前；可按试卷 / 志愿者 / 姓名手机号筛） | `AdminExamController` |
| GET | `/a/organization/exam-attempts/{id}` | `org:exam-grade` 或 `org:temp-leader` | 答卷详情（逐题作答、逐题得分、标准答案 / 参考答案） | `AdminExamController` |
| POST | `/a/organization/exam-attempts/{id}/grade` | `org:exam-grade` | 阅卷：给每道主观题打分并出分（及格即获得资格） | `AdminExamController` |
| GET | `/a/organization/exam-papers` | `org:exam` 或 `org:exam-grade` | 试卷列表（带题数与答卷数） | `AdminExamController` |
| POST | `/a/organization/exam-papers` | `org:exam` | 新建试卷（落草稿，题目整批提交） | `AdminExamController` |
| DELETE | `/a/organization/exam-papers/{id}` | `org:exam` | 删除试卷（仅草稿） | `AdminExamController` |
| GET | `/a/organization/exam-papers/{id}` | `org:exam` 或 `org:exam-grade` | 试卷详情（含题目、分值与标准答案） | `AdminExamController` |
| PUT | `/a/organization/exam-papers/{id}` | `org:exam` | 修改试卷（仅草稿，题目整批替换） | `AdminExamController` |
| POST | `/a/organization/exam-papers/{id}/close` | `org:exam` | 停止考试（已交的答卷照常阅卷） | `AdminExamController` |
| POST | `/a/organization/exam-papers/{id}/copy` | `org:exam` | 复制成新草稿（开放过的试卷要改题走这里） | `AdminExamController` |
| POST | `/a/organization/exam-papers/{id}/publish` | `org:exam` | 开放考试（同一时刻只能有一份开放中的试卷） | `AdminExamController` |
| GET | `/a/organization/forms` | `org:form` 或 `org:form-data` | 问卷列表（带题数与答卷数） | `AdminFormController` |
| POST | `/a/organization/forms` | `org:form` | 新建问卷（落草稿，题目整批提交） | `AdminFormController` |
| GET | `/a/organization/forms/submissions/{submissionId}` | `org:form-data` | 答卷详情 | `AdminFormController` |
| DELETE | `/a/organization/forms/{id}` | `org:form` | 删除问卷（仅草稿） | `AdminFormController` |
| GET | `/a/organization/forms/{id}` | `org:form` 或 `org:form-data` | 问卷详情（含题目） | `AdminFormController` |
| PUT | `/a/organization/forms/{id}` | `org:form` | 修改问卷（仅草稿；题目整批替换） | `AdminFormController` |
| POST | `/a/organization/forms/{id}/close` | `org:form` | 停止收集（已收到的答卷不受影响） | `AdminFormController` |
| POST | `/a/organization/forms/{id}/copy` | `org:form` | 复制成一份新草稿（发布后要改题走这里） | `AdminFormController` |
| POST | `/a/organization/forms/{id}/publish` | `org:form` | 发布（草稿 → 收集中；报名管理团队等场景同时只能有一份收集中） | `AdminFormController` |
| GET | `/a/organization/forms/{id}/submissions` | `org:form-data` | 答卷列表（逐题答案 + 填写人姓名电话） | `AdminFormController` |
| GET | `/a/organization/forms/{id}/submissions/export` | `org:form-data` | 导出答卷 Excel（每份答卷一行、每道题一列） | `AdminFormController` |
| GET | `/a/organization/groups` | `org:group-manage` | 全量小组列表 | `AdminGroupController` |
| GET | `/a/organization/groups/applications` | `org:group-audit` | 建组申请列表 | `AdminGroupController` |
| POST | `/a/organization/groups/applications/{id}/approve` | `org:group-audit` | 批准建组 | `AdminGroupController` |
| POST | `/a/organization/groups/applications/{id}/reject` | `org:group-audit` | 拒绝建组 | `AdminGroupController` |
| POST | `/a/organization/groups/import` | `org:group-manage` | 批量导入小组数据 | `AdminGroupController` |
| DELETE | `/a/organization/groups/{id}` | `org:group-manage` | 解散小组 | `AdminGroupController` |
| PUT | `/a/organization/groups/{id}/leader` | `org:group-manage` | 转移组长 | `AdminGroupController` |
| GET | `/a/organization/groups/{id}/leader-history` | `org:group-manage` | 组长变更历史 | `AdminGroupController` |
| GET | `/a/organization/groups/{id}/members` | `org:group-manage` | 小组成员列表（转移组长选人用） | `AdminGroupController` |
| GET | `/a/organization/manager-applications` | `org:manager-flag` | 报名管理团队申请列表（默认待审，可传 status 覆盖） | `AdminManagerApplicationController` |
| GET | `/a/organization/manager-applications/export` | `org:manager-flag` | 批量下载申请（Excel：固定三项 + 电话 + 问卷答卷；不传 status 导出全部） | `AdminManagerApplicationController` |
| GET | `/a/organization/manager-applications/{id}` | `org:manager-flag` | 申请详情（含电话与问卷答卷逐题答案） | `AdminManagerApplicationController` |
| POST | `/a/organization/manager-applications/{id}/approve` | `org:manager-flag` | 审核通过（置为管理团队 manager_flag=1） | `AdminManagerApplicationController` |
| POST | `/a/organization/manager-applications/{id}/reject` | `org:manager-flag` | 审核驳回 | `AdminManagerApplicationController` |
| GET | `/a/organization/permissions` | `org:sub-account` | 系统全量可分配权限列表 | `PermissionController` |
| GET | `/a/organization/permissions/volunteer-grantable` | `org:sub-account` | 可授权给志愿者的权限点目录 | `PermissionController` |
| GET | `/a/organization/squads` | `org:squad-manage` | 分队列表 | `AdminSquadController` |
| POST | `/a/organization/squads` | `org:squad-manage` | 创建分队 | `AdminSquadController` |
| GET | `/a/organization/squads/applications` | `org:squad-audit` | 全局待审分队加入申请（不按分队；默认仅待审，可传 status 覆盖。概览待办用） | `AdminSquadController` |
| POST | `/a/organization/squads/applications/{id}/approve` | `org:squad-audit` | 批准加入 | `AdminSquadController` |
| POST | `/a/organization/squads/applications/{id}/reject` | `org:squad-audit` | 拒绝加入 | `AdminSquadController` |
| DELETE | `/a/organization/squads/{id}` | `org:squad-manage` | 删除分队 | `AdminSquadController` |
| PUT | `/a/organization/squads/{id}` | `org:squad-manage` | 修改分队信息 | `AdminSquadController` |
| GET | `/a/organization/squads/{id}/applications` | `org:squad-audit` | 加入申请列表（指定分队） | `AdminSquadController` |
| GET | `/a/organization/structure` | `org:structure` | 架构树（含每个节点里的人） | `AdminStructureController` |
| DELETE | `/a/organization/structure/members/{memberId}` | `org:structure` | 把人移出架构 | `AdminStructureController` |
| PUT | `/a/organization/structure/members/{memberId}` | `org:structure` | 修改职位 / 排序 / 挪到另一个节点 | `AdminStructureController` |
| POST | `/a/organization/structure/nodes` | `org:structure` | 新增节点（必须挂在一个现存节点下面） | `AdminStructureController` |
| DELETE | `/a/organization/structure/nodes/{id}` | `org:structure` | 删除节点（根不能删；还有下级节点或人的不能删） | `AdminStructureController` |
| PUT | `/a/organization/structure/nodes/{id}` | `org:structure` | 修改节点（名称 / 说明 / 排序 / 上级；不能挂到自己的下级下面） | `AdminStructureController` |
| POST | `/a/organization/structure/nodes/{id}/members` | `org:structure` | 把志愿者放进节点（一个人在架构里只有一个位置） | `AdminStructureController` |
| GET | `/a/organization/sub-accounts` | `org:sub-account` | 子账号列表 | `SubAccountController` |
| POST | `/a/organization/sub-accounts` | `org:sub-account` | 创建子账号 | `SubAccountController` |
| DELETE | `/a/organization/sub-accounts/{id}` | `org:sub-account` | 删除子账号 | `SubAccountController` |
| GET | `/a/organization/sub-accounts/{id}` | `org:sub-account` | 子账号详情（含权限列表） | `SubAccountController` |
| PUT | `/a/organization/sub-accounts/{id}` | `org:sub-account` | 修改子账号基本信息 | `SubAccountController` |
| POST | `/a/organization/sub-accounts/{id}/password/reset` | `org:sub-account` | 重置子账号密码 | `SubAccountController` |
| PUT | `/a/organization/sub-accounts/{id}/permissions` |  | 全量替换子账号权限（仅超管） | `SubAccountController` |
| GET | `/a/organization/temp-leaders` | `org:temp-leader` | 资格名单（含手机号；状态按时间现算） | `AdminTempLeaderController` |
| GET | `/a/organization/temp-leaders/export` | `org:temp-leader` | 批量导出（xlsx，最多 20000 条） | `AdminTempLeaderController` |
| POST | `/a/organization/temp-leaders/{id}/revoke` | `org:temp-leader` | 撤销资格（评价过低等，须填原因） | `AdminTempLeaderController` |
| GET | `/a/organization/volunteers/{id}/flag-info` | `org:manager-flag` | 志愿者标记/授权页基础信息（姓名+管理团队标记+是否实名） | `OrganizationVolunteerController` |
| PUT | `/a/organization/volunteers/{id}/manager-flag` | `org:manager-flag` | 设置/取消志愿者管理团队标记 | `OrganizationVolunteerController` |
| GET | `/a/organization/volunteers/{id}/permissions` |  | 志愿者已分配的权限点（仅超管，与授权写入口同边界；service 层校验超管） | `OrganizationVolunteerController` |
| PUT | `/a/organization/volunteers/{id}/permissions` |  | 全量替换志愿者权限（仅超管） | `OrganizationVolunteerController` |
