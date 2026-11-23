<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# enterprise 模块的接口

53 个端点。模块说明见 [`知识库/模块/enterprise.md`](../../模块/enterprise.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/enterprise/enterprises` |  | 爱心企业列表（按编号；?keyword= 搜店名） | `EnterpriseController` |
| GET | `/v/enterprise/enterprises/{id}` |  | 爱心企业详情（企业主页） | `EnterpriseController` |
| GET | `/v/enterprise/enterprises/{id}/goods` |  | 企业主页的赞助商品（已上架未隐藏；企业须正常） | `EnterpriseController` |
| GET | `/v/enterprise/enterprises/{id}/posts` |  | 企业主页的帖子（企业发的帖，看得到的才列） | `EnterpriseController` |
| GET | `/v/enterprise/enterprises/{id}/reviews` |  | 企业主页的赞助商评价（只列正常的） | `EnterpriseController` |
| POST | `/v/enterprise/enterprises/{id}/reviews` |  | 评价赞助商（凭一张自己的、已领取的、这家企业赞助的兑换单；一单一评） | `EnterpriseController` |
| GET | `/v/enterprise/reviews/mine` |  | 我写过的赞助商评价（含被屏蔽的，标出来） | `VolunteerEnterpriseReviewController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/enterprise/enterprises` | `enterprise:audit` 或 `enterprise:manage` | 企业列表（?status=0 为入驻审核队列，先提交的在前；?keyword= 企业名称 / 信用代码 / 登录账号片段或负责人完整手机号） | `AdminEnterpriseController` |
| POST | `/a/enterprise/enterprises` | `enterprise:manage` | 后台注册企业账号（直接为正常状态） | `AdminEnterpriseController` |
| GET | `/a/enterprise/enterprises/export` | `enterprise:export` | 批量导出 xlsx（同列表筛选，最多 20000 条） | `AdminEnterpriseController` |
| DELETE | `/a/enterprise/enterprises/{id}` | `enterprise:manage` | 删除（逻辑删除，释放登录账号与信用代码；立刻踢掉登录） | `AdminEnterpriseController` |
| GET | `/a/enterprise/enterprises/{id}` | `enterprise:audit` 或 `enterprise:manage` | 企业详情（含负责人手机号） | `AdminEnterpriseController` |
| POST | `/a/enterprise/enterprises/{id}/approve` | `enterprise:audit` | 入驻审核通过 | `AdminEnterpriseController` |
| GET | `/a/enterprise/enterprises/{id}/goods` | `donate:goods` 或 `enterprise:manage` | 某企业赞助的商品（含草稿 / 待审核） | `AdminEnterpriseSponsorController` |
| POST | `/a/enterprise/enterprises/{id}/goods` | `donate:goods` | 以企业名义代发布积分商品（落草稿；企业须正常） | `AdminEnterpriseSponsorController` |
| POST | `/a/enterprise/enterprises/{id}/pause` | `enterprise:manage` | 暂停（须写原因；立刻踢掉这家企业的登录） | `AdminEnterpriseController` |
| GET | `/a/enterprise/enterprises/{id}/points` | `enterprise:points` | 企业积分总览 | `AdminEnterpriseSponsorController` |
| POST | `/a/enterprise/enterprises/{id}/points/adjust` | `enterprise:points` | 调整企业积分（兑换企业权益时扣减；带幂等键，扣减不许扣成负数） | `AdminEnterpriseSponsorController` |
| GET | `/a/enterprise/enterprises/{id}/points/records` | `enterprise:points` | 企业积分流水 | `AdminEnterpriseSponsorController` |
| POST | `/a/enterprise/enterprises/{id}/reject` | `enterprise:audit` | 入驻审核驳回（须写原因；企业改资料后可重新提交） | `AdminEnterpriseController` |
| POST | `/a/enterprise/enterprises/{id}/resume` | `enterprise:manage` | 恢复 | `AdminEnterpriseController` |
| POST | `/a/enterprise/points/reconcile` | `enterprise:points` | 补记兑换入账（定时任务回看窗口之外漏掉的；since 必填，不许是将来） | `AdminEnterpriseSponsorController` |
| GET | `/a/enterprise/reviews` | `enterprise:review` | 赞助商评价列表（?enterpriseId= / ?status= 0正常 1已屏蔽） | `AdminEnterpriseReviewController` |
| DELETE | `/a/enterprise/reviews/{id}` | `enterprise:review` | 删除（逻辑删除；删掉之后这张兑换单可以重新评价） | `AdminEnterpriseReviewController` |
| POST | `/a/enterprise/reviews/{id}/hide` | `enterprise:review` | 屏蔽（对外藏起来；作者与后台仍看得到，可恢复） | `AdminEnterpriseReviewController` |
| POST | `/a/enterprise/reviews/{id}/show` | `enterprise:review` | 恢复显示 | `AdminEnterpriseReviewController` |

## 爱心企业端 `/e`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| POST | `/e/auth/login` |  | 企业登录（暂停的登录不了；待审核 / 驳回能登录看状态） | `EnterpriseAuthController` |
| POST | `/e/auth/logout` |  | 退出登录 | `EnterpriseAuthController` |
| PUT | `/e/auth/password` |  | 修改密码（改完踢掉全部登录，含当前这个） | `EnterpriseAuthController` |
| PUT | `/e/auth/password/reset` |  | 凭负责人手机验证码重置密码（带登录账号；改完踢掉全部登录） | `EnterpriseAuthController` |
| POST | `/e/auth/register` |  | 入驻注册（落待审核；审核通过前登录后只能看改自己的资料） | `EnterpriseAuthController` |
| POST | `/e/auth/sms/codes` |  | 发短信验证码（enterprise-register 入驻注册 / enterprise-password-reset 找回密码） | `EnterpriseAuthController` |
| GET | `/e/donate/goods` |  | 我赞助的商品（含草稿 / 待审核 / 驳回原因；?status= 0草稿/1待审核/2已上架/3已停用/4已驳回） | `EnterpriseGoodsController` |
| POST | `/e/donate/goods` |  | 新增赞助商品（落草稿；赞助方名称取企业名称，不能设「必须持卷」） | `EnterpriseGoodsController` |
| DELETE | `/e/donate/goods/{id}` |  | 删除赞助商品（历史兑换单有快照不受影响） | `EnterpriseGoodsController` |
| GET | `/e/donate/goods/{id}` |  | 商品详情（只看得到自己的） | `EnterpriseGoodsController` |
| PUT | `/e/donate/goods/{id}` |  | 修改赞助商品（只能改自己的；改已过审的退回待审核，审核中不许改） | `EnterpriseGoodsController` |
| PUT | `/e/donate/goods/{id}/hidden` |  | 隐藏 / 显示（Row 8 F 商品隐藏；不触发重审） | `EnterpriseGoodsController` |
| POST | `/e/donate/goods/{id}/submit` |  | 提交审核（草稿 / 驳回稿 → 待审核，审核在后台） | `EnterpriseGoodsController` |
| GET | `/e/donate/orders` |  | 我赞助商品的兑换单（没有取货码与收件信息，兑换人只留姓） | `EnterpriseGoodsController` |
| GET | `/e/donate/verifiers` |  | 我的核销员（只能核销本企业赞助的商品） | `EnterpriseGoodsController` |
| POST | `/e/donate/verifiers` |  | 指派核销员（按已实名志愿者的手机号；一个志愿者同时只能是一处的核销员） | `EnterpriseGoodsController` |
| DELETE | `/e/donate/verifiers/{id}` |  | 撤销核销员 | `EnterpriseGoodsController` |
| GET | `/e/enterprise/points` |  | 企业积分总览（余额 / 累计兑换入账 / 累计后台调整） | `EnterpriseGoodsController` |
| GET | `/e/enterprise/points/records` |  | 企业积分流水（?sourceType= 1兑换入账/2后台调整） | `EnterpriseGoodsController` |
| GET | `/e/enterprise/profile` |  | 我的企业资料与入驻审核状态（含驳回 / 暂停原因） | `EnterpriseProfileController` |
| PUT | `/e/enterprise/profile` |  | 修改资料（头像 / 介绍 / 地址 / 对外电话随时可改；企业名称 / 信用代码 / 项目负责人只在待审核或驳回时可改） | `EnterpriseProfileController` |
| POST | `/e/enterprise/profile/resubmit` |  | 被驳回后重新提交入驻申请 | `EnterpriseProfileController` |
| GET | `/e/enterprise/reviews` |  | 收到的赞助商评价（含被后台屏蔽的，标出来） | `EnterprisePostController` |
| GET | `/e/social/posts` |  | 我发过的帖子（含审核中与被驳回的，带审核状态） | `EnterprisePostController` |
| POST | `/e/social/posts` |  | 以企业名义发帖（正文 / 图片 ≤9 或视频 1 个 / 评论点赞开关；作者名与头像存快照） | `EnterprisePostController` |
| DELETE | `/e/social/posts/{id}` |  | 删除自己的帖子 | `EnterprisePostController` |
| PUT | `/e/social/posts/{id}` |  | 改自己的帖子（改完重回待审核） | `EnterprisePostController` |
