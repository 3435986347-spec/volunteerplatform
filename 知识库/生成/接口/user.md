<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# user 模块的接口

31 个端点。模块说明见 [`知识库/模块/user.md`](../../模块/user.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/user/addresses` |  | 我的地址（置顶在最前） | `AddressController` |
| POST | `/v/user/addresses` |  | 新增地址（最多 20 个） | `AddressController` |
| DELETE | `/v/user/addresses/{id}` |  | 删除地址 | `AddressController` |
| PUT | `/v/user/addresses/{id}` |  | 修改地址 | `AddressController` |
| DELETE | `/v/user/addresses/{id}/top` |  | 取消置顶 | `AddressController` |
| POST | `/v/user/addresses/{id}/top` |  | 置顶（一人至多一条，置顶新的会取消旧的） | `AddressController` |
| GET | `/v/user/center-contents/{key}` |  | 志愿者看个人中心内容（还没设置时 data 为空） | `CenterContentController` |
| DELETE | `/v/user/deactivation` |  | 撤销注销（冷静期内） | `AccountDeactivationController` |
| GET | `/v/user/deactivation` |  | 我的注销状态（是否在冷静期、何时生效） | `AccountDeactivationController` |
| POST | `/v/user/deactivation` |  | 申请注销（验证码发到本人手机号，scene=account-deactivate）；进入冷静期，期内可撤销 | `AccountDeactivationController` |
| GET | `/v/user/notify-preferences` |  | 我的订阅（全部话题；optional=false 的不能关） | `NotifyPreferenceController` |
| PUT | `/v/user/notify-preferences` |  | 打开 / 关闭一个话题的短信提醒（只影响短信；站内提示照常留存） | `NotifyPreferenceController` |
| DELETE | `/v/user/pad-signature` |  | 清除安全中心手写签名板 | `MyProfileController` |
| PUT | `/v/user/pad-signature` |  | 设置安全中心手写签名板（body {url}；先经 /v/files/profile-image?dir=signature 上传；与注册协议签名分开） | `MyProfileController` |
| PUT | `/v/user/phone` |  | 修改/换绑手机号（原号与新号都要短信验证，scene=change-phone；未绑过手机号的只验新号） | `MyProfileController` |
| GET | `/v/user/profile` |  | 我的资料（本人完整资料 + 时长/积分/小组/分队） | `MyProfileController` |
| PATCH | `/v/user/profile` |  | 修改我的资料（头像/学校/年级/政治面貌/地址/紧急联系方式，部分更新） | `MyProfileController` |
| POST | `/v/user/profile/school-confirm` |  | 确认本学年的学校和年级（每年一次、确认即通过、不影响报名；GET /profile 的 schoolConfirmPending 为真时提示） | `MyProfileController` |
| GET | `/v/user/volunteer-card` |  | 我的志愿者证（第一次打开时发证；带码图） | `VolunteerCardController` |
| POST | `/v/user/volunteer-card/reset` |  | 重置证件二维码（旧码当场失效，证件截图外流时用） | `VolunteerCardController` |
| GET | `/v/user/volunteer-cards/verify` |  | 扫码核验（公开、免登录；只给头像 / 姓名只留姓 / 编号 / 注册时间 / 累计时长 / 活动次数） | `VolunteerCardController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/user/center-contents/{key}` | `user:center-content` | 后台读个人中心内容 | `CenterContentController` |
| PUT | `/a/user/center-contents/{key}` | `user:center-content` | 后台设置个人中心内容（整份替换；图片先经 /a/files/upload?dir=center） | `CenterContentController` |
| GET | `/a/user/volunteers` | `user:list` | 志愿者列表（多条件筛选 + 分页） | `AdminVolunteerController` |
| GET | `/a/user/volunteers/export` | `user:export` | 导出志愿者名单（Excel，支持与列表相同筛选） | `AdminVolunteerController` |
| DELETE | `/a/user/volunteers/{id}` | `user:delete` | 删除志愿者（逻辑删除） | `AdminVolunteerController` |
| GET | `/a/user/volunteers/{id}` | `user:list` | 志愿者详情（含明文手机号/身份证尾号） | `AdminVolunteerController` |
| PUT | `/a/user/volunteers/{id}` |  | 修改志愿者全量信息（实名敏感字段，仅超管） | `AdminVolunteerController` |
| POST | `/a/user/volunteers/{id}/password/reset` | `user:pwd-reset` | 重置志愿者密码（清空 password，重置后需手机号验证码重登再自设新密码） | `AdminVolunteerController` |
| POST | `/a/user/volunteers/{id}/restore` | `user:restore` | 恢复已注销的账号（填一个当前没被占用的手机号；理事会） | `AdminVolunteerController` |
| PATCH | `/a/user/volunteers/{id}/status` | `user:status` | 暂停/恢复志愿者账号（status 0正常/1禁用） | `AdminVolunteerController` |
