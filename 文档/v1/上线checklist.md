# 恒德志愿者平台 V1 上线 / 交付需求方测试 Checklist

适用：把 `hengde-volunteer-api-2.0.1.jar` 从「本地联调」推进到「需求方真机测试 / 正式上线」。
本地联调请看同目录 `运行说明.md`；**服务器上的具体安装动作（systemd / nginx / 目录约定 / 升级回滚）见同目录 `部署说明.md`**（配套制品在仓库 `部署/`：`nginx.conf`、`hengde-api.service`、`api.env.example`）；本清单只讲**对外交付**多出来的那些事。

> 关键判断：**管理后台（`/a/**`）门槛低、可最先交付**（账号密码登录、浏览器即可访问，不依赖微信）；
> **小程序志愿者端（`/v/**`）门槛高**，强依赖「微信小程序账号 + 已备案 HTTPS 域名 + 短信/OSS 真密钥」，且**域名备案最慢，要最先启动**。

---

## 0. 上线前先拍板的 V1 功能缺口

- [ ] **首页 `/v/home` 占位 —— 可选，不阻塞**。轮播图（`/v/publicity/banners`）、推荐活动（活动列表）、公告（`/v/publicity/announcements`）都已各有独立接口，前端首页**各块分别调用即可，首页不会是空的**。`/v/home` 只是「合并成一次请求」的可选聚合优化，V1 可不做。
- [ ] **投诉建议（data 领域）未实现** —— V1 列入但没做。决定：补 / 延后并告知。
- [x] **签到 / 服务时长 / 积分闭环已全量交付**（V1.1 三批：GPS+扫码签到签退、统一签退算时长、秘书部确认、积分发放、考勤变更二次审核、补录）——志愿者最在意的时长诉求已覆盖。仍延后的只有**证书生成**与**名单公示展示页**，交付时说明即可。
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
- [ ] **Nginx 反向代理**：`https://域名` → `http://127.0.0.1:8080`，并保留 `/api` 前缀（context-path）。前端 baseURL = `https://域名/api`。**现成配置见仓库 `部署/nginx.conf`**（已含安全头/gzip/缓存/上传大小/接口文档屏蔽），安装步骤见 `部署说明.md` 第 3 节。
- [ ] **后台管理前端（admin-web）**：`volunteer-platform-back/` 的 `index.html + assets/` 拷到 nginx 静态根（`/opt/hengde/admin-web`，**README/联调指南/uploads 参考资料不要上服务器**）；需要地图选点的话在 `index.html` 填 `__AMAP_KEY__`。前端在 80/443 下自动用同源 `/api`，零改动。

## 4. 第三方真实密钥（关掉 dev stub）

> **2026-07-28 状态**：短信 / 对象存储 / 实名核验三项**代码均已接通并在开发环境实测通过**（用真实凭证跑过真实调用）。此处剩下的是**把同一套凭证配到生产环境变量**，不再有待写的代码。

- [x] **火山引擎短信** —— ✅ 已接通并实测（真实短信可收到）。开通、**签名「雷州市恒德爱心公益协会」报备**、**模板报备**（占位必须用 `${code}`，与代码写死的参数名一致）。配 `SMS_ENABLED=true` + `SMS_AK/SMS_SK/SMS_ACCOUNT`（region 默认 `cn-north-1`）。
      **模板按场景分别配置**：`SMS_TPL_VERIFY`（兜底，未单独配的场景用它）+ `SMS_TPL_REGISTER`/`SMS_TPL_LOGIN`/`SMS_TPL_PWD_RESET`/`SMS_TPL_ADMIN_PWD_RESET`/`SMS_TPL_CHANGE_PHONE`。只配兜底也能跑，但注册/改绑手机号会收到登录文案。
      ⚠️ 不开短信 → 验证码只打日志，需求方收不到，**注册/登录走不通**。
- [x] **对象存储（火山 TOS）** —— ✅ 已接通并实测（图片真实上传、URL 可匿名访问、字节数无损）。置 `OSS_PROVIDER=volc` + `OSS_ENABLED=true` + `OSS_ENDPOINT`（**外网** Endpoint，形如 `tos-cn-guangzhou.volces.com`，**不要填 S3 endpoint 或内网 `.ivolces.com`**）+ `OSS_REGION`（与 endpoint 地域一致）+ `OSS_BUCKET/OSS_AK/OSS_SK`；绑了 CDN/自定义域名再填 `OSS_URL_PREFIX`。
      ⚠️ **桶默认私有读会导致上传成功但图片全 403**。代码已在上传时给对象打公共读 ACL（`OSS_PUBLIC_READ=true`，默认开）来规避；若桶开了「阻止公共访问」类总开关，仍需在控制台放开。
      ⚠️ 若后端部署在火山云同地域，可用内网 endpoint 提速，但**必须同时把 `OSS_URL_PREFIX` 设为外网域名**，否则拼出的图片 URL 外部访问不了。
      ⚠️ 弱网/大文件可调 `OSS_READ_TIMEOUT_MS`（默认 120000）。
- [x] **实名认证（身份证二要素，腾讯云）** —— ✅ **已实现并实测通过**（原为「代码未写、开启即抛异常」）。腾讯云人脸核身 `IdCardVerification`，按次计费。开启需 `AUTH_REALNAME_ENABLED=true` + `REALNAME_SECRET_ID`/`REALNAME_SECRET_KEY`。
      ⚠️ 密钥是**腾讯云 CAM 账号级密钥**（`访问管理 → 访问密钥 → API 密钥管理`，SecretId 36 位 `AKID` 开头 / SecretKey 32 位，**必须同一对**；SecretKey 仅创建时显示一次）。与火山引擎 AK/SK 无关，不可复用。
      ⚠️ 开启但密钥为空会被 `ProductionConfigGuard` **fail-fast 拒启**。关闭时仅校验号码格式后放行，仅体验流程可暂留。
- [x] **地图选点（高德 Web端 JSAPI）** —— ✅ key 已配。在后台前端 `index.html` 填 `__AMAP_KEY__` + `__AMAP_SECURITY_CODE__`（2021-12-02 后申请的 key 必须配安全密钥）。
      ⚠️ key/jscode 会出现在前端源码里，**务必在高德控制台配域名白名单**兜底。生产也可改用代理（填 `__AMAP_SERVICE_HOST__`，见 nginx 配置注释）。
      说明：**签到/签退不依赖高德**（后端 Haversine + 微信原生定位），高德只用于后台发布活动时的地图选点，未配也能手填经纬度。
- [ ] **企业微信群校验**：`AUTH_WEWORK_ENABLED`，需要的话开启并配 `AUTH_WEWORK_QR_URL`（引导入群二维码）。**实接代码未做**，开启前需先补。
- [ ] **微信小程序**：`WX_APPID` 已有；**`WX_SECRET` 需在微信公众平台「设置 → 开发设置」重置获取**（只能重置不能查看，需管理员扫码）。

## 5. 应用配置（环境变量全集）

启动前用环境变量覆盖，**不改 jar**。

- [ ] **必须显式指定 profile**：生产 `--spring.profiles.active=prod`（或 `SPRING_PROFILES_ACTIVE=prod`）。
      base config 不再默认进 dev；**不带 profile 启动会被 `ProductionConfigGuard` fail-fast 拦下**。
- [ ] **prod 启动会强校验（任一不过直接拒绝启动，宁可起不来也不让弱配置上线）**：`SECURITY_AES_KEY`/`SECURITY_HMAC_KEY` 非空且非 `dev-only-*` 默认值；超管密码非 `admin123`；`AUTH_DEV_LOGIN_ENABLED=false`（开发登录绕过微信鉴权，禁止上生产）；`AUTH_AGREEMENT_VERSION` 非空；**协议正文 `hengde.auth.agreement-text` 非占位文本**（否则志愿者签的是占位协议——正式文本是长文本，推荐写服务器上的 `application-prod.yaml`，见 `部署说明.md` 第 2 节）。
- [ ] **环境变量模板**：仓库 `部署/api.env.example` 已按本表整理好全部变量与注释，复制到 `/etc/hengde/api.env`（chmod 600）填真值即可。

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
| `AUTH_AGREEMENT_VERSION` + 协议正文 | ✅ | 版本号 env 即可；**正文**（`hengde.auth.agreement-text`）是长文本，放服务器 `application-prod.yaml`（守卫强校验非占位） |
| `HENGDE_ACTIVITY_EMERGENCYPHONE` | 可选 | 活动现场「紧急上报」预设电话（小程序负责人端 `tel:` 拨号，不配则前端隐藏入口） |
| `LOGGING_LEVEL_COM_HENGDE` | 可选 | base 默认已是 `INFO`；dev profile 下为 `DEBUG`，需要更细可覆盖 |

⚠️ **AES/HMAC 密钥极其关键**：志愿者身份证/手机号用它加密入库。
  - 上线**第一次写入真实数据前**就要定好这对密钥，**之后绝不能更换或丢失**——换了/丢了，库里已加密的 PII 全部无法解密。
  - 用足够随机的强随机串，**妥善备份保管**，不要进代码仓库。

## 6. 上线后冒烟验证

- [ ] 后端在线：`curl -i https://域名/api/a/auth/login -X POST` 返回 JSON 错误体即在线。**`/api/doc.html` 应为 403**——生产 nginx 已屏蔽接口文档（预期行为；联调需要时临时注释 `部署/nginx.conf` 对应 location，或服务器本机直连 `:8080` 访问）。
- [ ] **管理后台**：`https://域名/` 打开登录页（控制台无报错）→ `admin` 强密码登录 → 概览**数据看板有数字**、待办卡按权限显示；`https://域名/README.md`、`/uploads/...` 均 403。
- [ ] 管理端：发活动 / 建公告 / 建分队成功。
- [ ] 志愿者端（真机体验版）：微信登录拿到 token → 收到**真实短信**验证码 → 实名注册 → 看活动 → 报名/取消 → 看公告 → 全局搜索。
- [ ] 上传一张图（头像/活动图），确认是**真实 OSS URL** 且能访问。
- [ ] 报名审核：管理端通过/拒绝，志愿者端「我的报名」状态正确。
- [ ] 确认弱默认值（admin123 / dev-only-* 密钥）**均已被覆盖**。
- [ ] **V4 新增的三条公开路径**（不带登录态也打得到，靠各自的凭据）：`GET /api/v/user/volunteer-cards/verify?token=`（志愿者证核验）、
      `GET /api/share/files/{token}`（文件分享，V4 系统治理批）、`POST /api/callback/**`（支付与快递 webhook）。
      逐条 `curl` 一次，确认**不是 401**（401 说明鉴权拦截器把它们一起挡了，钱与扫码都会静默失败）。
- [ ] **私信的 WebSocket**：`/api/ws/social/chat?token=<登录 token>` 能握手（nginx 要放行 `Upgrade`/`Connection`，见《部署说明》第 3 节）。
      握不上手不影响业务——消息已落库，只是对方要刷新才看得到，**所以这条必须主动验**，否则没人会发现。
- [ ] **Excel 导出**（志愿者名单或报名名单各导一次）：2.0.1 那版 commons-compress 不配套会 `NoSuchMethodError`，
      父 POM 已钉 1.27.1，**重新发版后要在生产实际导出一次**确认。

## 7. 多组织（白标）说明

每个社会组织 = **独立库 + 独立配置，跑同一份 jar**。给恒德之外的组织部署时，重复 2~6（新库、新密钥、新微信小程序、新域名），互不共用数据。监管后台是另一套独立应用，不在本制品内。
