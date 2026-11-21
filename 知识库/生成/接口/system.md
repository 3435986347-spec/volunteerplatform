<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# system 模块的接口

25 个端点。模块说明见 [`知识库/模块/system.md`](../../模块/system.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/system/files` |  | 此刻开放的内置文件（关掉下载的不下发地址） | `VolunteerSystemFileController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/system/logs` | `system:log` | 操作日志（?logType= 1操作/2页面访问、?actorType=、?actorId=、?keyword=、?from=、?to=） | `AdminOperationLogController` |
| GET | `/a/system/menu-order` |  | 后台菜单排序（仅需登录） | `AdminSystemConfigController` |
| PUT | `/a/system/menu-order` | `system:config` | 改后台菜单排序（整份覆盖；菜单键不能重复） | `AdminSystemConfigController` |
| POST | `/a/system/page-views` |  | 上报一次页面访问（前端在切换页面时调；仅需登录） | `AdminOperationLogController` |
| GET | `/a/system/serials` | `system:config` | 编号段一览（Row 75：十位编号，前三位是功能段） | `AdminSystemConfigController` |
| POST | `/a/system/vault/files` | `system:file` | 登记一个文件（先经 POST /a/files/upload?dir=vault 上传；编号自动取十位号） | `AdminFileVaultController` |
| DELETE | `/a/system/vault/files/{id}` | `system:file` | 删除文件（逻辑删除） | `AdminFileVaultController` |
| PUT | `/a/system/vault/files/{id}/folder` | `system:file` | 移动文件到别的文件夹（两边都要有写权限） | `AdminFileVaultController` |
| PUT | `/a/system/vault/files/{id}/name` | `system:file` | 重命名文件 | `AdminFileVaultController` |
| PUT | `/a/system/vault/files/{id}/publish` | `system:file` | 公开到小程序「文件下载」+ 开放时间与下载开关（Row 19；撤销公开会把窗口一起清掉） | `AdminFileVaultController` |
| GET | `/a/system/vault/files/{id}/shares` | `system:file` | 文件的分享链接列表 | `AdminFileVaultController` |
| POST | `/a/system/vault/files/{id}/shares` | `system:file` | 分享文件（body `requireLogin` 默认 true / `hours` 默认 168、0 = 不过期） | `AdminFileVaultController` |
| GET | `/a/system/vault/folders` | `system:file` | 文件夹树（只列我看得到的；`canWrite` 说明我能不能往里放东西） | `AdminFileVaultController` |
| POST | `/a/system/vault/folders` | `system:file` | 新建文件夹（根目录只有超管能建） | `AdminFileVaultController` |
| DELETE | `/a/system/vault/folders/{id}` | `system:file` | 删文件夹（有下级或有文件时拒绝，不连带删） | `AdminFileVaultController` |
| PUT | `/a/system/vault/folders/{id}` | `system:file` | 改文件夹（名称 / 排序） | `AdminFileVaultController` |
| GET | `/a/system/vault/folders/{id}/files` | `system:file` | 文件夹里的文件（?keyword= 按文件名或编号） | `AdminFileVaultController` |
| GET | `/a/system/vault/folders/{id}/grants` | `system:file` | 文件夹授权列表 | `AdminFileVaultController` |
| POST | `/a/system/vault/folders/{id}/grants` | `system:file` | 给文件夹授权（**仅超管**：授给某个后台账号或一整个部门，可读或可写） | `AdminFileVaultController` |
| DELETE | `/a/system/vault/grants/{id}` | `system:file` | 撤销授权（**仅超管**） | `AdminFileVaultController` |
| DELETE | `/a/system/vault/shares/{id}` | `system:file` | 撤销分享 | `AdminFileVaultController` |
| GET | `/a/system/watermark` |  | 界面水印设置 + 给当前账号算好的水印文字（仅需登录） | `AdminSystemConfigController` |
| PUT | `/a/system/watermark` | `system:config` | 改界面水印（显示哪几样、字号、透明度、角度） | `AdminSystemConfigController` |

## 其他（回调 / 分享 / WebSocket 等）

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/share/files/{token}` |  | 打开分享（要登录的分享没登录时报错；每打开一次记一次下载数） | `FileShareController` |
