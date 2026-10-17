<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# honor 模块的接口

53 个端点。模块说明见 [`知识库/模块/honor.md`](../../模块/honor.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/honor/certificates` |  | 我的证书列表（分页；软删的不返回）。category 可选：activity 志愿活动 / pair 助学助困 / wish 微心愿 / | `CertificateController` |
| GET | `/v/honor/certificates/by-ref` |  | 按来源键取本人的捐赠证书（如捐物记录上的「证书」按钮传 cf-goods:运单id、心愿详情传 wish:认领id）； | `CertificateController` |
| GET | `/v/honor/certificates/{id}/file` |  | 下载：文件缺失时懒渲染并回填，返回短期签名 URL，并累加下载次数 | `CertificateController` |
| GET | `/v/honor/certificates/{id}/preview` |  | 预览：文件缺失时懒渲染并回填，返回短期签名 URL，不计下载次数 | `CertificateController` |
| GET | `/v/honor/medals` |  | 我的勋章（已启用勋章 ∪ 本人已获得的勋章 + 是否已获得 + 获取进度；只统计已生效的发放；样式按最后一次过审版本展示） | `MedalController` |
| GET | `/v/honor/rankings` |  | 排行榜（次数/时长/积分 × 月/年/总；往期读冻结快照） | `RankingController` |
| GET | `/v/honor/reward-punishes` |  | 我的奖惩记录（分页；只返回【终审】已通过的，待初审/待终审/已驳回一律不返回） | `RewardPunishController` |
| POST | `/v/honor/reward-punishes/{id}/appeal` |  | 对处罚提交申诉（自处罚生效起 7 天内；奖励无需申诉） | `RewardPunishController` |
| GET | `/v/honor/role-models` |  | 榜样列表（仅已上架；分页 + 关键词 + 个人/团队筛选 + 排序） | `MedalController` |
| GET | `/v/honor/sanctions` |  | 我当前生效中的处置（到期即自动消失，不依赖定时任务） | `RewardPunishController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/honor/certificate-templates` | `honor:certificate-template` | 电子样本列表 | `AdminCertificateTemplateController` |
| POST | `/a/honor/certificate-templates` | `honor:certificate-template` | 新增电子样本（传 activityId=按活动，不传=全局默认） | `AdminCertificateTemplateController` |
| POST | `/a/honor/certificate-templates/file` | `honor:certificate-template` | 上传样本 PDF（私有对象），返回可填进 fileKey 的对象 key | `AdminCertificateTemplateController` |
| DELETE | `/a/honor/certificate-templates/{id}` | `honor:certificate-template` | 删除电子样本 | `AdminCertificateTemplateController` |
| PUT | `/a/honor/certificate-templates/{id}` | `honor:certificate-template` | 修改电子样本（作用域不可改，要换活动请新建） | `AdminCertificateTemplateController` |
| GET | `/a/honor/certificates` | `honor:certificate` | 证书汇总查询（可按志愿者/活动筛选；可含已软删，供撤销删除） | `AdminCertificateController` |
| POST | `/a/honor/certificates/batch` | `honor:certificate` | 批量上传 PDF 证书（按文件名手机号匹配志愿者，逐条返回成败） | `AdminCertificateController` |
| POST | `/a/honor/certificates/donations/{kind}/{id}` | `honor:certificate` | 补发某次捐赠的捐赠证书（幂等）——出证事件丢失时的人工救济。kind：pair 结对登记 / wish 微心愿认领 / | `AdminCertificateController` |
| POST | `/a/honor/certificates/pairs/{pairRecordId}` | `honor:certificate` | 补发某条结对的捐赠证书（幂等）——出证事件丢失时的人工救济 | `AdminCertificateController` |
| POST | `/a/honor/certificates/reconcile` | `honor:certificate` | 补发缺失的证书（按确认时间起点/活动指定范围，至少给一个；单次封顶，看 hasMore 决定是否再点） | `AdminCertificateController` |
| DELETE | `/a/honor/certificates/{id}` | `honor:certificate-delete` | 软删指定某人证书（记删除人/时间/原因） | `AdminCertificateController` |
| GET | `/a/honor/certificates/{id}/file` | `honor:certificate` | 后台查看（同样只返回短期签名 URL；不计下载次数） | `AdminCertificateController` |
| POST | `/a/honor/certificates/{id}/restore` | `honor:certificate-delete` | 撤销软删 | `AdminCertificateController` |
| GET | `/a/honor/medal-grants` | `honor:medal-grant` 或 `honor:medal-grant-audit` | 发放记录列表（status 0待审核/1已生效/2已驳回；可按志愿者筛选） | `AdminMedalController` |
| POST | `/a/honor/medal-grants` | `honor:medal-grant` | 发起勋章发放（落待审核，须过发放审核才对志愿者生效） | `AdminMedalController` |
| POST | `/a/honor/medal-grants/{id}/approve` | `honor:medal-grant-audit` | 发放审核通过（生效，志愿者可见；附带积分同事务入账） | `AdminMedalController` |
| POST | `/a/honor/medal-grants/{id}/reject` | `honor:medal-grant-audit` | 发放审核驳回（不生效、不发分，留痕；之后可重新发起） | `AdminMedalController` |
| GET | `/a/honor/medals` | `honor:medal` 或 `honor:medal-audit` | 勋章列表（status 可筛选 0草稿/1待审核/2已启用/3已驳回/4已停用） | `AdminMedalController` |
| POST | `/a/honor/medals` | `honor:medal` | 新增勋章定义（落草稿，需提交审核后才可发放） | `AdminMedalController` |
| DELETE | `/a/honor/medals/{id}` | `honor:medal` | 删除勋章定义（已有待审/已生效发放记录的不可删，请改用停用） | `AdminMedalController` |
| PUT | `/a/honor/medals/{id}` | `honor:medal` | 修改勋章定义（改已启用的会退回待审核，须重新过样式审核） | `AdminMedalController` |
| POST | `/a/honor/medals/{id}/approve` | `honor:medal-audit` | 样式审核通过（待审核 → 已启用，此后方可发放） | `AdminMedalController` |
| POST | `/a/honor/medals/{id}/disable` | `honor:medal-audit` | 停用勋章（不可再发放；已生效的发放记录不受影响） | `AdminMedalController` |
| POST | `/a/honor/medals/{id}/enable` | `honor:medal-audit` | 重新启用勋章（该样式此前已过审，无需再审） | `AdminMedalController` |
| POST | `/a/honor/medals/{id}/reject` | `honor:medal-audit` | 样式审核驳回（待审核 → 已驳回，可修改后重新提交） | `AdminMedalController` |
| PUT | `/a/honor/medals/{id}/sort` | `honor:medal` | 调整勋章展示排序（纯展示，不影响审核状态） | `AdminMedalController` |
| POST | `/a/honor/medals/{id}/submit` | `honor:medal` | 提交样式审核（草稿/已驳回 → 待审核） | `AdminMedalController` |
| GET | `/a/honor/rankings` | `honor:ranking-view` | 排行榜（与志愿者端同一份数据） | `AdminRankingController` |
| POST | `/a/honor/rankings/snapshots` | `honor:ranking-snapshot` | 生成/补跑某周期快照（force=true 会覆盖已冻结的历史名次） | `AdminRankingController` |
| GET | `/a/honor/reward-punishes` | `honor:reward-punish` 或 `honor:reward-punish-appeal` 或 `honor:reward-punish-final` | 奖惩单列表（可按志愿者/类型/审核状态/申诉状态筛） | `AdminRewardPunishController` |
| POST | `/a/honor/reward-punishes` | `honor:reward-punish` | 开一张奖惩单（落待审核；处罚可关联已审核通过的现场违规） | `AdminRewardPunishController` |
| POST | `/a/honor/reward-punishes/{id}/appeal` | `honor:reward-punish-appeal` | 受理申诉（成立则撤销处置并冲正积分） | `AdminRewardPunishController` |
| POST | `/a/honor/reward-punishes/{id}/approve` | `honor:reward-punish` | 初审通过（组织部；仅推进到待终审，不产生任何效力） | `AdminRewardPunishController` |
| POST | `/a/honor/reward-punishes/{id}/final-approve` | `honor:reward-punish-final` | 终审通过（理事会；此刻起对志愿者可见、积分入账、处置生效、申诉期开始计时） | `AdminRewardPunishController` |
| POST | `/a/honor/reward-punishes/{id}/lift-sanction` | `honor:sanction` | 提前解除某张奖惩单产生的处置（须填原因；还要有这一档对应部门的处置权限点） | `AdminRewardPunishController` |
| POST | `/a/honor/reward-punishes/{id}/reject` | `honor:reward-punish` 或 `honor:reward-punish-final` | 审核驳回（初审、终审均可；须填原因；志愿者始终看不到这张单） | `AdminRewardPunishController` |
| GET | `/a/honor/role-models` | `honor:role-model` | 榜样列表（status 可筛选 0下架/1上架） | `AdminRoleModelController` |
| POST | `/a/honor/role-models` | `honor:role-model` | 新增榜样（落下架态，校对后再上架） | `AdminRoleModelController` |
| DELETE | `/a/honor/role-models/{id}` | `honor:role-model` | 删除榜样 | `AdminRoleModelController` |
| PUT | `/a/honor/role-models/{id}` | `honor:role-model` | 修改榜样 | `AdminRoleModelController` |
| PUT | `/a/honor/role-models/{id}/sort` | `honor:role-model` | 调整榜样排序 | `AdminRoleModelController` |
| PUT | `/a/honor/role-models/{id}/status` | `honor:role-model` | 上架 / 下架（status 0下架/1上架） | `AdminRoleModelController` |
| GET | `/a/honor/sanctions` | `honor:sanction` | 查某人当前生效中的处置 | `AdminRewardPunishController` |
