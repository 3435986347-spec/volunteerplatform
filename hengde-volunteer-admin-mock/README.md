# 恒德志愿者后台管理端

本目录是后台管理端静态前端，不依赖 npm，可直接打开 `index.html` 预览。页面保留 Mock 预览模式，也支持连接本地 `hengde-volunteer-api-1.1-SNAPSHOT.jar` 的真实接口，用来给微信小程序造测试数据。

## 覆盖页面

- 首页看板
- 活动管理：新增活动、编辑活动、删除活动、周期发布、历史活动、复制活动、查看报名列表
- 轮播公告：新增/编辑/删除轮播图，新增/编辑/删除公告，预留图片 URL、推文/小程序跳转字段
- 组织管理：志愿小组列表、组长转移、解散小组、归属分队列表、创建/编辑/删除分队
- 志愿者管理
- 考勤补录：考勤/积分变更审核、活动补录入口
- 子账号权限
- 接口操作台：覆盖报名审核、活动负责人、统一签退、服务记录、权限、志愿者状态、公示文件等尚未拆成独立页面的后台接口

## 真实接口联调

1. 在 `Code/run-artifacts` 下启动 MySQL 和 Redis：

```bash
docker compose up -d
```

2. 启动后端 jar：

```bash
java -jar hengde-volunteer-api-1.1-SNAPSHOT.jar --spring.profiles.active=dev
```

3. 打开本目录的 `index.html`。
4. 点击顶部“Mock 数据 / 真实接口”按钮切到“真实接口”。
5. 使用默认管理端账号登录：

```text
admin / admin123
```

6. 在后台新增轮播图、公告、活动或分队后，到微信开发者工具中把小程序 `useMockApi` 改为 `false` 进行查看。

## URL 约定

- 管理端接口统一按 `/api/a/{domain}/{resource}` 对接。
- 接口路径常量集中在 `assets/api-endpoints.js`。
- 默认真实接口基址：`http://localhost:8080/api`。
