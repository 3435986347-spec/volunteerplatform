# 恒德志愿者平台 V1 上线 / 交付需求方测试 Checklist

适用：把 `hengde-volunteer-api-1.0-SNAPSHOT.jar` 从「本地联调」推进到「需求方真机测试 / 正式上线」。
本地联调请看同目录 `运行说明.md`；本清单只讲**对外交付**多出来的那些事。

> 关键判断：**管理后台（`/a/**`）门槛低、可最先交付**（账号密码登录、浏览器即可访问，不依赖微信）；
> **小程序志愿者端（`/v/**`）门槛高**，强依赖「微信小程序账号 + 已备案 HTTPS 域名 + 短信/OSS 真密钥」，且**域名备案最慢，要最先启动**。

---

## 0. 上线前先拍板的 V1 功能缺口

- [ ] **首页 `/v/home` 占位 —— 可选，不阻塞**。轮播图（`/v/publicity/banners`）、推荐活动（活动列表）、公告（`/v/publicity/announcements`）都已各有独立接口，前端首页**各块分别调用即可，首页不会是空的**。`/v/home` 只是「合并成一次请求」的可选聚合优化，V1 可不做。
- [ ] **投诉建议（data 领域）未实现** —— V1 列入但没做。决定：补 / 延后并告知。
- [ ] **签到 / 服务时长 / 证书公示** 闭环按设计**延后到下一版**。⚠️ 时长是志愿者最在意的诉求，**务必提前明确告知需求方 V1 不含**，避免测到一半的预期落差。
- [x] **代报名 + 小组管理员（≤3）+ 组长变更历史 + 解散字段**（V7 迁移已落地）：同小组成员可互相代报名（`POST /v/activity/activities/{id}/proxy-enrollments`，落 `proxy_by_volunteer_id` 字段，管理端报名列表显示「代报名人」）；组长可设最多 3 名管理员（`POST/DELETE /v/.../members/{memberId}/admin`），管理员与组长一同审批/移除；`volunteer_group` 新增 `dissolve_time/reason/by` 与 `approved_time/by` 字段（与 `reject_reason` 解耦）；新建 `volunteer_group_leader_history` 表（含建组首次任命+每次转移）；`/a/organization/groups/{id}/leader-history` 查询。⚠️ 被代报名者**不主动推送通知**——靠他们打开「我的报名」自己看（V1 范围内）。

---

## 1. 微信小程序（志愿者端的硬前提，最先办）

- [ ] 以协会主体注册**微信小程序**，拿到 **AppID + AppSecret**（→ 配 `WX_APPID` / `WX_SECRET`）。
- [ ] 小程序后台「开发管理 → 服务器域名」把后端域名加入 **request 合法域名**（必须 **HTTPS + 已备案**，真机/体验版无法绕过；开发者工具里「不校验合法域名」只在工具内有效）。
- [ ] 若上传走前端直传或预览第三方资源，按需配 **uploadFile / downloadFile 合法域名**。
- [ ] 提交小程序**类目与基础信息**审核（公益类目可能需资质，提前备齐）。
- [ ] 体验版发布、把需求方微信号加为**体验成员**。

## 2. 服务器与中间件

- [ ] 一台**有公网 IP 的服务器**（建议 2C4G 起；JDK 17）。
- [ ] **MySQL 8.x**：建空库 `hengde_volunteer`（utf8mb4），账号最好用非 root 专用账号。表由 Flyway 启动自动建。
- [ ] **Redis 5+**：建议设访问密码、不要裸跑公网。
- [ ] 安全组 / 防火墙：放行对外端口（见第 5 点反代），**MySQL/Redis 端口不要对公网开放**。
- [ ] 也可用 `docker-compose.yml` 起 MySQL+Redis（同目录），但**生产建议给 Redis 加密码、给 MySQL 用独立账号**，别直接套联调默认值。

## 3. 域名 + HTTPS + 备案（最耗时，第 1 步就启动）

- [ ] 域名 **ICP 备案**（数天~数周，微信加合法域名的前提）。
- [ ] 配 **HTTPS 证书**（微信强制 HTTPS）。
- [ ] **Nginx 反向代理**：`https://域名` → `http://127.0.0.1:8080`，并保留 `/api` 前缀（context-path）。前端 baseURL = `https://域名/api`。

## 4. 第三方真实密钥（关掉 dev stub）

- [ ] **火山引擎短信**：开通、**签名「雷州市恒德爱心公益协会」报备**、**验证码模板报备**（占位用 `${code}`）。配 `SMS_ENABLED=true` + `SMS_AK/SMS_SK/SMS_ACCOUNT/SMS_TPL_VERIFY`（region 默认 `cn-north-1`）。
      ⚠️ 不开短信 → 注册/找回密码验证码只打日志，需求方收不到，**注册走不通**。
- [ ] **对象存储**：代码支持两家，由 `OSS_PROVIDER` 选择——`aliyun`（默认）或 `volc`（火山引擎 TOS）。恒德实例用火山 TOS：置 `OSS_PROVIDER=volc` + `OSS_ENABLED=true` + `OSS_ENDPOINT`(形如 `tos-cn-beijing.volces.com`) + `OSS_REGION`(如 `cn-beijing`) + `OSS_BUCKET/OSS_AK/OSS_SK`，绑了 CDN/自定义域名再填 `OSS_URL_PREFIX`；配桶 **CORS**（允许小程序域名）。
      ⚠️ 不开 → 上传返回占位 URL，头像/活动图/下载文件都不是真文件。
- [ ] **实名认证（身份证二要素，腾讯云）**：`AUTH_REALNAME_ENABLED` 当前关闭=放行。⚠️ **是待补的代码接入项**（`RealNameServiceImpl` 开启即抛异常），需先按腾讯云所选接口写对接代码 + 配密钥，不是配个变量就行。仅体验流程可暂留放行。
- [ ] **企业微信群校验**：`AUTH_WEWORK_ENABLED`，需要的话开启并配 `AUTH_WEWORK_QR_URL`（引导入群二维码）。

## 5. 应用配置（环境变量全集）

启动前用环境变量覆盖，**不改 jar**。

- [ ] **必须显式指定 profile**：生产 `--spring.profiles.active=prod`（或 `SPRING_PROFILES_ACTIVE=prod`）。
      base config 不再默认进 dev；**不带 profile 启动会被 `ProductionConfigGuard` fail-fast 拦下**。
- [ ] **prod 启动会强校验关键密钥**：若 `SECURITY_AES_KEY`/`SECURITY_HMAC_KEY` 仍是 `dev-only-*`/为空，或超管密码仍是 `admin123`，**直接拒绝启动**（宁可起不来也不让弱配置上线）。

| 变量 | 必改? | 说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ✅ | 生产设 `prod`（不设会启动失败，见上） |
| `SPRING_DATASOURCE_URL` | 视情况 | **datasource.url 在 yaml 里写死 localhost**，MySQL 不在同机就必须覆盖整条 url |
| `DB_USER` / `DB_PWD` | ✅ | 数据库账号密码（别用 root/root） |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | 视情况 | **Redis 也写死 localhost:6379**，不同机要覆盖 |
| `SPRING_DATA_REDIS_PASSWORD` | 视情况 | yaml 默认无密码；Redis 设了密码就要加 |
| `SECURITY_AES_KEY` | ✅✅ | PII 加密密钥。见下方 ⚠️ |
| `SECURITY_HMAC_KEY` | ✅✅ | PII 可查询哈希密钥。见下方 ⚠️ |
| `AUTH_SUPER_ADMIN_USERNAME` / `AUTH_SUPER_ADMIN_PASSWORD` | ✅ | 初始超管。**首次启动前就设好强密码**（无超管时才创建，admin123 一旦建出来不会自动改；prod 下用 admin123 会被守卫拒启） |
| `WX_APPID` / `WX_SECRET` | ✅(志愿者端) | 微信小程序密钥 |
| `SMS_ENABLED` + `SMS_*` | ✅ | 见第 4 点 |
| `OSS_PROVIDER` | ✅ | `aliyun`(默认) / `volc`(火山 TOS)；恒德置 `volc` |
| `OSS_ENABLED` + `OSS_ENDPOINT/OSS_REGION/OSS_BUCKET/OSS_AK/OSS_SK` | ✅ | 见第 4 点；火山 TOS 必填 `OSS_REGION` |
| `AUTH_REALNAME_ENABLED` / `AUTH_WEWORK_ENABLED` / `AUTH_WEWORK_QR_URL` | 视情况 | 实名 / 企业微信群 |
| `LOGGING_LEVEL_COM_HENGDE` | 可选 | base 默认已是 `INFO`；dev profile 下为 `DEBUG`，需要更细可覆盖 |

⚠️ **AES/HMAC 密钥极其关键**：志愿者身份证/手机号用它加密入库。
  - 上线**第一次写入真实数据前**就要定好这对密钥，**之后绝不能更换或丢失**——换了/丢了，库里已加密的 PII 全部无法解密。
  - 用足够随机的强随机串，**妥善备份保管**，不要进代码仓库。

## 6. 上线后冒烟验证

- [ ] `https://域名/api/doc.html` 能打开（或确认生产是否关闭文档）。
- [ ] 管理端：`admin` 强密码登录成功 → 发活动 / 建公告 / 建分队成功。
- [ ] 志愿者端（真机体验版）：微信登录拿到 token → 收到**真实短信**验证码 → 实名注册 → 看活动 → 报名/取消 → 看公告 → 全局搜索。
- [ ] 上传一张图（头像/活动图），确认是**真实 OSS URL** 且能访问。
- [ ] 报名审核：管理端通过/拒绝，志愿者端「我的报名」状态正确。
- [ ] 确认弱默认值（admin123 / dev-only-* 密钥）**均已被覆盖**。

## 7. 多组织（白标）说明

每个社会组织 = **独立库 + 独立配置，跑同一份 jar**。给恒德之外的组织部署时，重复 2~6（新库、新密钥、新微信小程序、新域名），互不共用数据。监管后台是另一套独立应用，不在本制品内。
