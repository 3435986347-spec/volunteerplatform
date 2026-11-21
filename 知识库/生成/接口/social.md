<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# social 模块的接口

70 个端点。模块说明见 [`知识库/模块/social.md`](../../模块/social.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/social/blocks` |  | 我设置了「不让TA看」的人 | `SocialUserController` |
| DELETE | `/v/social/blocks/{userId}` |  | 取消「不让TA看」 | `SocialUserController` |
| POST | `/v/social/blocks/{userId}` |  | 不让TA看（TA 看不到我的帖子） | `SocialUserController` |
| GET | `/v/social/chats` |  | 我的会话列表（最后一条 + 未读数，按最后一条时间倒序） | `SocialChatController` |
| GET | `/v/social/chats/unread-count` |  | 未读总数（角标） | `SocialChatController` |
| DELETE | `/v/social/chats/{peerId}/messages` |  | 清空聊天记录（只对我自己；后台保存的记录不受影响） | `SocialChatController` |
| GET | `/v/social/chats/{peerId}/messages` |  | 和某个人的消息（新的在前；beforeId 往前翻） | `SocialChatController` |
| POST | `/v/social/chats/{peerId}/messages` |  | 发私信（文字与图片至少有一样；图片先经 POST /v/files/social-image 上传） | `SocialChatController` |
| POST | `/v/social/chats/{peerId}/read` |  | 标记已读（清掉我这一侧的未读） | `SocialChatController` |
| POST | `/v/social/chats/{peerId}/reports` |  | 投诉这段私聊（审核员会看到聊天内容） | `SocialChatController` |
| DELETE | `/v/social/comments/{commentId}` |  | 删除评论（自己的评论，或自己帖子下的评论） | `SocialPostController` |
| GET | `/v/social/interactions` |  | 我的互动（谁赞了 / 评论了 / 回复了 / 关注了我，新的在前） | `SocialGovController` |
| POST | `/v/social/interactions/read` |  | 全部标为已读 | `SocialGovController` |
| GET | `/v/social/interactions/unread-count` |  | 未读互动数 | `SocialGovController` |
| DELETE | `/v/social/interactions/{id}` |  | 删除一条互动（长按删除） | `SocialGovController` |
| GET | `/v/social/posts` |  | 帖子流：tab=latest 最新（默认）/ hot 最热 / following 关注 / official 官方；keyword 搜索 | `SocialPostController` |
| POST | `/v/social/posts` |  | 发帖（文字 / 图片 / 视频 + 可见性与评论点赞开关） | `SocialPostController` |
| DELETE | `/v/social/posts/{id}` |  | 删除自己的帖子 | `SocialPostController` |
| GET | `/v/social/posts/{id}` |  | 帖子详情（查看量 +1） | `SocialPostController` |
| PUT | `/v/social/posts/{id}` |  | 修改自己的帖子（改完重回待审核） | `SocialPostController` |
| GET | `/v/social/posts/{id}/comments` |  | 帖子的评论（按时间先后） | `SocialPostController` |
| POST | `/v/social/posts/{id}/comments` |  | 评论 / 回复（parentId 为回复的评论） | `SocialPostController` |
| DELETE | `/v/social/posts/{id}/like` |  | 取消点赞（本来没赞不报错，data=false） | `SocialPostController` |
| POST | `/v/social/posts/{id}/like` |  | 点赞（已赞过不报错，data=false） | `SocialPostController` |
| POST | `/v/social/posts/{id}/share` |  | 分享（分享量 +1） | `SocialPostController` |
| POST | `/v/social/reports` |  | 举报帖子或评论（同一对象在处理完之前只能举报一次） | `SocialGovController` |
| GET | `/v/social/settings` |  | 我的主页设置 | `SocialUserController` |
| PUT | `/v/social/settings` |  | 保存主页设置（禁止关注 / 禁止评论 / 禁止点赞 / 备注，整份提交） | `SocialUserController` |
| GET | `/v/social/users/{id}` |  | 主页（头像 / 昵称 / 勋章 / 注册时间 / 发帖量 / 粉丝量 / 关注量 / 点赞量 / 备注 / 关系） | `SocialUserController` |
| GET | `/v/social/users/{id}/comments` |  | TA 的评论（只列我看得到的帖子下的） | `SocialUserController` |
| DELETE | `/v/social/users/{id}/follow` |  | 取消关注 | `SocialUserController` |
| POST | `/v/social/users/{id}/follow` |  | 关注（已关注不报错，data=false；TA 设置了禁止关注则拒绝） | `SocialUserController` |
| GET | `/v/social/users/{id}/followers` |  | 粉丝 | `SocialUserController` |
| GET | `/v/social/users/{id}/following` |  | 关注的人 | `SocialUserController` |
| GET | `/v/social/users/{id}/posts` |  | TA 的帖子（只列我看得到的） | `SocialUserController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/social/bans` | `social:ban` | 禁言记录（?volunteerId=） | `AdminSocialGovController` |
| POST | `/a/social/bans` | `social:ban` | 禁言（即时生效；scope 2 全部社区写入 / 4 禁止发帖 / 5 禁止评论 / 6 禁止点赞；天数必填） | `AdminSocialGovController` |
| POST | `/a/social/bans/{id}/lift` | `social:ban` | 提前解除禁言 | `AdminSocialGovController` |
| DELETE | `/a/social/chat-messages/{id}` | `social:chat-view` | 删除一条违规消息（双方都看不到；后台仍查得到） | `AdminSocialChatController` |
| GET | `/a/social/chat-reports` | `social:chat-view` | 私聊工单队列（?status= 0待处理/1成立/2不成立；关键词命中的插队在前） | `AdminSocialChatController` |
| POST | `/a/social/chat-reports/{id}/handle` | `social:chat-view` | 处理工单（成立 / 不成立；成立可顺带删掉那条消息。禁言另走 POST /a/social/bans） | `AdminSocialChatController` |
| GET | `/a/social/chats` | `social:chat-view` | 会话列表（?volunteerId= 只看某个人的） | `AdminSocialChatController` |
| GET | `/a/social/chats/{id}/messages` | `social:chat-view` | 一条会话的全部消息（含双方各自清空的、已删除的） | `AdminSocialChatController` |
| GET | `/a/social/comments` | `social:post-manage` | 评论列表（按时间；?postId=&authorId=&keyword=） | `AdminSocialGovController` |
| DELETE | `/a/social/comments/{commentId}` | `social:official` 或 `social:post-manage` | 删除评论：持 social:post-manage 可删任意评论；否则只能删本部门官方帖下的（持 social:official-all 不限部门） | `AdminOfficialPostController` |
| GET | `/a/social/keywords` | `social:review-setting` | 风控关键词列表 | `AdminSocialGovController` |
| POST | `/a/social/keywords` | `social:review-setting` | 添加风控关键词（此后发帖 / 改帖命中即先藏后审） | `AdminSocialGovController` |
| DELETE | `/a/social/keywords/{id}` | `social:review-setting` | 删除风控关键词（已命中的帖子不受影响，照常等审核） | `AdminSocialGovController` |
| GET | `/a/social/official-posts` | `social:official` | 官方帖列表（?department= 按部门筛） | `AdminOfficialPostController` |
| POST | `/a/social/official-posts` | `social:official` | 发布官方帖（以本账号的部门发布） | `AdminOfficialPostController` |
| DELETE | `/a/social/official-posts/{id}` | `social:official` | 删除官方帖（本部门的；持有 social:official-all 可删任何部门的） | `AdminOfficialPostController` |
| GET | `/a/social/official-posts/{id}/comments` | `social:official` | 官方帖下的评论 | `AdminOfficialPostController` |
| POST | `/a/social/official-posts/{id}/comments` | `social:official` | 以官方身份评论 / 回复（只能在官方帖下） | `AdminOfficialPostController` |
| GET | `/a/social/posts` | `social:post-manage` | 帖子列表（按发布时间；?reviewStatus=&keywordHit=&hidden=&authorId=&keyword=；持 social:real-name 才带真实姓名与学校） | `AdminSocialGovController` |
| DELETE | `/a/social/posts/{id}` | `social:post-manage` | 删除任意帖子 | `AdminSocialGovController` |
| DELETE | `/a/social/posts/{id}/hide` | `social:post-manage` | 取消隐藏 | `AdminSocialGovController` |
| POST | `/a/social/posts/{id}/hide` | `social:post-manage` | 隐藏帖子（除作者外谁都看不到，可恢复） | `AdminSocialGovController` |
| DELETE | `/a/social/posts/{id}/pin` | `social:post-manage` | 取消置顶 | `AdminSocialGovController` |
| POST | `/a/social/posts/{id}/pin` | `social:post-manage` | 置顶（最新与官方页签排在最前） | `AdminSocialGovController` |
| GET | `/a/social/reports` | `social:report` | 举报列表（?status= 0 待处理（先举报的在前）/ 1 成立 / 2 不成立） | `AdminSocialGovController` |
| POST | `/a/social/reports/{id}/dismiss` | `social:report` | 举报不成立（同一对象上全部待处理的一起结案） | `AdminSocialGovController` |
| POST | `/a/social/reports/{id}/uphold` | `social:report` | 举报成立（同一对象上全部待处理的一起结案；action 0 不处置 / 1 隐藏帖子 / 2 删除） | `AdminSocialGovController` |
| GET | `/a/social/review-setting` | `social:review` 或 `social:review-setting` | 审核设置（审核级数） | `AdminSocialGovController` |
| PUT | `/a/social/review-setting` | `social:review-setting` | 修改审核级数（1~3；调低之后已经过够新级数的待审帖直接算通过） | `AdminSocialGovController` |
| GET | `/a/social/reviewers` | `social:review-setting` | 审核员列表 | `AdminSocialGovController` |
| POST | `/a/social/reviewers` | `social:review-setting` | 设置审核员（后台账号 + 第几级；还须另外授予 social:review） | `AdminSocialGovController` |
| DELETE | `/a/social/reviewers/{id}` | `social:review-setting` | 移除审核员 | `AdminSocialGovController` |
| GET | `/a/social/reviews` | `social:review` | 待我审核的帖子（命中关键词的插队在前；只列我这一级的；超管看全部级） | `AdminSocialGovController` |
| POST | `/a/social/reviews/{postId}/approve` | `social:review` | 审核通过这一级（同一个人不能审同一条帖子的两级） | `AdminSocialGovController` |
| POST | `/a/social/reviews/{postId}/reject` | `social:review` | 驳回（对他人隐藏，站内提示作者） | `AdminSocialGovController` |
