<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->

# publicity 模块的接口

17 个端点。模块说明见 [`知识库/模块/publicity.md`](../../模块/publicity.md)。

## 志愿者端 `/v`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/v/publicity/announcements` |  | 公告列表 | `VolunteerPublicityController` |
| GET | `/v/publicity/announcements/{id}` |  | 公告详情 | `VolunteerPublicityController` |
| GET | `/v/publicity/banners` |  | 轮播图列表 | `VolunteerPublicityController` |
| GET | `/v/publicity/files` |  | 文件下载列表 | `VolunteerPublicityController` |

## 后台 `/a`

| 方法 | 路径 | 权限 | 说明 | 控制器 |
|---|---|---|---|---|
| GET | `/a/publicity/announcements` | `pub:announcement` | 公告列表 | `AdminPublicityController` |
| POST | `/a/publicity/announcements` | `pub:announcement` | 新增公告 | `AdminPublicityController` |
| DELETE | `/a/publicity/announcements/{id}` | `pub:announcement` | 删除公告 | `AdminPublicityController` |
| PUT | `/a/publicity/announcements/{id}` | `pub:announcement` | 修改公告 | `AdminPublicityController` |
| GET | `/a/publicity/banners` | `pub:banner` | 轮播图列表 | `AdminPublicityController` |
| POST | `/a/publicity/banners` | `pub:banner` | 新增轮播图 | `AdminPublicityController` |
| DELETE | `/a/publicity/banners/{id}` | `pub:banner` | 删除轮播图 | `AdminPublicityController` |
| PUT | `/a/publicity/banners/{id}` | `pub:banner` | 修改轮播图 | `AdminPublicityController` |
| PATCH | `/a/publicity/banners/{id}/sort` | `pub:banner` | 调整轮播图排序 | `AdminPublicityController` |
| GET | `/a/publicity/files` | `pub:file` | 全量文件列表 | `AdminPublicityController` |
| POST | `/a/publicity/files` | `pub:file` | 上传文件 | `AdminPublicityController` |
| DELETE | `/a/publicity/files/{id}` | `pub:file` | 删除文件 | `AdminPublicityController` |
| PATCH | `/a/publicity/files/{id}/access` | `pub:file` | 开放或关闭下载 | `AdminPublicityController` |
