<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# api 模块的接口

24 个端点。模块说明见 [`知识库/模块/api.md`](../../模块/api.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| POST | `/v/files/album-photo` |  | 上传活动相册照片（已实名志愿者；原图，限图片） | `VolunteerFileUploadController` |
| POST | `/v/files/appeal-image` |  | 上传申诉凭证图片（任意登录志愿者，含被禁用/被判「拒绝使用」者；限图片） | `VolunteerFileUploadController` |
| POST | `/v/files/form-file` |  | 上传问卷附件（任意登录志愿者；图片与常见文档） | `VolunteerFileUploadController` |
| POST | `/v/files/profile-image` |  | 上传个人头像 / i志愿者码 / 协议签名图（任意登录志愿者；仅 dir=avatar\|ivcode\|signature、限图片） | `VolunteerFileUploadController` |
| POST | `/v/files/social-image` |  | 上传社区帖子图片（已实名志愿者；限图片） | `VolunteerFileUploadController` |
| POST | `/v/files/social-video/presign` |  | 社区帖子视频直传签名（已实名志愿者；extension=mp4\|mov，size=字节数） | `VolunteerFileUploadController` |
| POST | `/v/files/upload` | `activity:publish` | 上传活动封面（管理团队志愿者，需 activity:publish；仅 dir=activity、限图片） | `VolunteerFileUploadController` |
| GET | `/v/home` |  | 首页聚合数据 | `HomeAggregateController` |
| GET | `/v/search` |  | 全局搜索（活动+公告+小组+分队合并为单一信息流，下滑加载） | `SearchController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| POST | `/a/files/social-video/presign` | `social:official` | 官方帖视频直传签名（social:official；extension=mp4\|mov，size=字节数） | `FileUploadController` |
| POST | `/a/files/upload` |  | 通用上传图片/文件，返回可访问 URL（按 dir 做权限+类型门槛，dir 必传） | `FileUploadController` |
| GET | `/a/trade/orders` | `trade:order` | 交易单列表（按业务类型 / 状态 / 创建时间筛选） | `AdminTradeController` |
| GET | `/a/trade/orders/{id}` | `trade:order` | 交易单详情（含支付与退款流水） | `AdminTradeController` |
| POST | `/a/trade/orders/{id}/close` | `trade:order` | 关单（仅待支付；已支付的关掉等于把收到的钱从账上抹掉） | `AdminTradeController` |
| POST | `/a/trade/orders/{id}/query` | `trade:order` | 主动查单并同步本地（**本地与渠道不一致时以渠道为准**） | `AdminTradeController` |
| POST | `/a/trade/orders/{id}/refund` | `trade:refund` | 退款（不填金额=整单退；原因必填） | `AdminTradeController` |
| GET | `/a/trade/reconciliations` | `trade:order` | 对账记录列表（每日定时 + 手动，新的在前；不含差异明细） | `AdminTradeController` |
| POST | `/a/trade/reconciliations` | `trade:order` | 手动对账（**第四道，不是唯一那道**；范围必须显式给出，已支付与已关闭两侧都核，结果落库） | `AdminTradeController` |
| GET | `/a/trade/reconciliations/{id}` | `trade:order` | 对账记录详情（含逐条差异） | `AdminTradeController` |

## 爱心企业端 `/e`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| POST | `/e/files/image` |  | 上传企业头像 / 照片（企业登录态，待审核也可；限图片） | `EnterpriseFileUploadController` |
| POST | `/e/files/social-image` |  | 上传社区帖子图片（企业登录态，须审核通过；限图片） | `EnterpriseFileUploadController` |
| POST | `/e/files/social-video/presign` |  | 社区帖子视频直传签名（企业登录态，须审核通过；extension=mp4\|mov，size=字节数） | `EnterpriseFileUploadController` |

## 其他（回调 / 分享 / WebSocket 等）

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| POST | `/callback/trade/wechat/pay` |  | 支付结果通知（APIv3 验签 + 解密） | `TradeCallbackController` |
| POST | `/callback/trade/wechat/refund` |  | 退款结果通知（APIv3 验签 + 解密） | `TradeCallbackController` |
