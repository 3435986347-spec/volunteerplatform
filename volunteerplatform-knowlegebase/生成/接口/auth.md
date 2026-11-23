<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# auth 模块的接口

19 个端点。模块说明见 [`知识库/模块/auth.md`](../../模块/auth.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/auth/agreement` |  | 获取志愿者协议文本（注册前阅读，含版本号） | `VolunteerAuthController` |
| POST | `/v/auth/login/dev` |  | 开发登录（跳过微信，仅 dev-login-enabled=true 时可用，生产禁用） | `VolunteerAuthController` |
| POST | `/v/auth/login/password` |  | 手机号+密码登录 | `VolunteerAuthController` |
| POST | `/v/auth/login/sms` |  | 手机号+验证码登录（陌生号自动建游客账号） | `VolunteerAuthController` |
| POST | `/v/auth/login/wechat` |  | 微信小程序登录 | `VolunteerAuthController` |
| POST | `/v/auth/logout` |  | 退出登录 | `VolunteerAuthController` |
| PUT | `/v/auth/password` |  | 设置/修改登录密码（需登录；首次设密码原密码可空） | `VolunteerAuthController` |
| PUT | `/v/auth/password/reset` |  | 忘记密码：手机号+验证码重置登录密码 | `VolunteerAuthController` |
| POST | `/v/auth/register` |  | 志愿者实名注册 | `VolunteerAuthController` |
| POST | `/v/auth/sms/codes` |  | 发送短信验证码（scene: register/login/volunteer-password-reset/change-phone，默认 register） | `VolunteerAuthController` |
| GET | `/v/auth/wechat/group-membership` |  | 企业微信群成员前置校验 | `VolunteerAuthController` |
| GET | `/v/notifications` |  | 我的提示（按时间倒序分页） | `NotificationController` |
| GET | `/v/notifications/unread-count` |  | 未读条数（角标） | `NotificationController` |
| POST | `/v/notifications/{id}/read` |  | 标记一条为已读（重复调用不报错） | `NotificationController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| POST | `/a/auth/login` |  | 账号密码登录 | `AdminAuthController` |
| POST | `/a/auth/logout` |  | 退出登录 | `AdminAuthController` |
| PUT | `/a/auth/password` |  | 修改密码 | `AdminAuthController` |
| PUT | `/a/auth/password/reset` |  | 凭验证码重置密码 | `AdminAuthController` |
| POST | `/a/auth/sms/codes` |  | 发送短信验证码（找回密码） | `AdminAuthController` |
