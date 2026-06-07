# 恒德志愿者小程序前端 mock

本目录是 V1 阶段的小程序前端骨架，采用原生微信小程序结构。默认使用本地 mock 数据，也可切换到 A 提供的本地后端 jar 接口。

## 范围

- 三个 Tab：首页 / 社区 / 我的。
- V1 首页能力：轮播图、公告栏、全局搜索、数据看板、推荐活动入口。
- B 负责页面：志愿小组、归属分队、轮播/公告展示、全局搜索静态结果。
- 协作页面：登录、志愿者注册、活动列表/详情/报名、我的资料/我的活动。

## 使用方式

1. 打开微信开发者工具。
2. 导入本目录 `Code/hengde-volunteer-miniprogram`。
3. AppID 可先使用测试号或游客模式。

## Mock / 真实接口切换

- 默认 `app.js` 中 `globalData.useMockApi=true`，页面走 `utils/mock.js`。
- 联调 A 的 jar 时，将 `globalData.useMockApi` 改为 `false`，接口基础路径仍为 `http://localhost:8080/api`。
- A 的 `hengde-volunteer-api-1.1-SNAPSHOT.jar` 已提供志愿者开发登录：`POST /v/auth/login/dev`。本小程序默认 `globalData.devVolunteerLogin=true`，因此没有 AppID 时也可在“短信登录/志愿者登录”入口拿真实志愿者 token 联调。
- 如需改回真实微信登录，将 `globalData.devVolunteerLogin` 改为 `false`，并配置后端 `WX_APPID` / `WX_SECRET`。
- 页面统一经 `utils/data-service.js` 取数，不直接读 `utils/mock.js`。
- 若需要临时切换，可在开发者工具调试时调用 `utils/api-mode.js` 中的 `setUseMockApi(false)`，或直接修改 `app.js`。

## URL 约定

- 接口基础路径：`http://localhost:8080/api`，对应后端 `server.servlet.context-path=/api`。
- 志愿者端接口使用 `/v/{domain}/{resource}`。
- 管理端接口使用 `/a/{domain}/{resource}`。
- 分队资源统一命名为 `squads`，页面路径也使用 `squad/squads`，避免和管理团队、发布团队混淆。
- 前端接口路径常量集中在 `utils/api-endpoints.js`。
