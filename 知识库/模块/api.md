# api 模块 · 启动与全局配置

> 代码：`代码/hengde-volunteer-api/`。本页由根 `CLAUDE.md` 的「已完成」表下沉而来（2026-10-17）；
> 上半部分是**现行要点**，下半部分是按批次的原始记录（为什么这样做、撞过什么）。两者冲突时以上半部分与代码为准，并顺手修正下半部分。

## 职责

唯一可部署单元：启动类、Sa-Token 路由、Jackson 与 MVC 转换器、MyBatis 分页、调度开关、全局异常；跨域聚合（全局搜索、上传）与两条 webhook 的控制器。

## 现行要点

- 启动时显式 `SaManager.putStpLogic(...)` 注册后台与企业两套 StpLogic，**这几行不能删**——删了重启后第一个打到带注解的 `/a/**` 的请求会 500。
- MVC 出参由前置的 Jackson-2 转换器接管：时间统一 `yyyy-MM-dd HH:mm:ss`、Long 转字符串；入参时间兼容空格与 ISO `T` 两种。
- 分页拦截器只在 api 装配：领域模块测试里 `selectPage` 不加 LIMIT，断言 `records` 而不是 `total`。
- `@EnableScheduling` / `@EnableAsync` 在 api 的 `AsyncConfig`，领域模块只提供 `@Scheduled` bean。
- 上传端点按 `dir` 双门槛（权限 + 文件类型），未知 `dir` 拒绝。
- `/callback/**`、`/share/**` 靠验签 / 令牌公开：路由处理器里只对 `/v` `/a` `/e` 做 check，**别加全局 `match("/**")`**。
- webhook 应答直接写 `HttpServletResponse`，不要返回 `String`（会被 Jackson 再序列化一层）。
- api 的 HTTP 用例按 properties 起多个上下文，Sa-Token 静态状态由测试里的 `SaTokenContextRebinder` 指回当前上下文；单独跑绿不等于全量跑绿。

## 按批次的记录

**全局配置层已完成**：`SaTokenConfigure`（⚠️ **启动时显式 `SaManager.putStpLogic(StpAdminUtil.STP_LOGIC)`，这一行不能删**——`SaInterceptor` 先做注解校验、后跑路由函数，而 `@SaCheckPermission(type="admin")` 要靠 `SaManager` 查 StpLogic；那个 StpLogic 只在 `StpAdminUtil` **类初始化**时注册，`StpAdminUtil.TYPE` 又是编译期常量、引用它不触发初始化。缺这一行时，**重启后第一个打到带注解的 `/a/**` 端点的请求（带不带 token 都一样）抛「未能获取对应StpLogic，type=admin」成 500**，未登录拿不到 401、控制台跳不回登录页，直到有人碰巧先调了登录或 `/a/auth/me` 才自愈——V3 trade 批 `TradeCallbackApiTest` 的反向断言撞出来的全项目级缺陷，那条用例**只在单独跑、JVM 里此前没人碰过 `StpAdminUtil` 时才确定性地红**）、`WebMvcConfig`（CORS + **MVC 消息转换器前置**：`extendMessageConverters` 把基于 `JacksonConfig` ObjectMapper 的 Jackson-2 `MappingJackson2HttpMessageConverter` 插到 converters 首位，接管 HTTP 出入参序列化）、`JacksonConfig`（注册 `JavaTimeModule`——手建 ObjectMapper 不会自动注册 jsr310，否则 VO 的 LocalDateTime 运行期序列化抛异常；LocalDateTime→`yyyy-MM-dd HH:mm:ss`、Long→字符串防 JS 丢精度；**Boot 4 / Spring 7 的 MVC 默认走 Jackson 3（`tools.jackson`），这个 `@Primary` 的 Jackson-2 ObjectMapper 在 MVC 层本不生效**——曾致所有接口响应时间带 ISO `T`（如「报名截止：2026-06-10T09:00:00」）、Long 也不转字符串；现由上面 WebMvcConfig 前置 Jackson-2 转换器统一接管出参；**入参侧 LocalDateTime 改宽松反序列化**[`LenientLocalDateTimeDeserializer`：先按固定 `yyyy-MM-dd HH:mm:ss` 解析、失败回退 `DateTimeFormatter.ISO_LOCAL_DATE_TIME`]，同时兼容后台 datetime-local 送的 ISO `T` 与小程序送的空格格式（出参恒为空格格式）；配 `JacksonConfigTest` 烟囱测试 + `JacksonMvcFormatTest` 锁「出参无 T/Long 为字符串、双格式入参均可、转换器确用本 mapper 前置」3 例）、`MybatisPlusConfig`（含分页拦截器 `PaginationInnerInterceptor`，**仅此处装配**，领域模块测试上下文没有）、`AsyncConfig`（`@EnableAsync` + **`@EnableScheduling`**——调度总开关按「主动改变应用行为的配置放 api」的约定落在这里，领域模块只提供带 `@Scheduled` 的 bean，好处是领域模块测试上下文默认不起调度线程；当前挂着 honor 的排行榜快照冻结任务）、`SpringDocConfig`（Knife4j，分组 volunteer/admin/enterprise）、`GlobalExceptionHandler`、`TraceFilter`、`HomeAggregateController`（占位）、`SearchController`（**已实现**：`/v/search` 全局搜索，聚合 activity/publicity/organization 四类——活动+公告+小组+分队，各领域 `countSearch`+`search(offset,limit)` 跨块精确分页，合并成单一信息流 `PageResult<SearchItemVO>`）、`FileUploadController`（**通用上传** `POST /a/files/upload`：multipart `file`+**必传** `dir`，**按 dir 双门槛**——权限[banner→pub:banner/announcement→pub:announcement/file→pub:file/activity→activity:publish或edit/summary→activity:manage，经 `StpAdminUtil.STP_LOGIC.checkPermission`]+ 类型[图片目录仅 `FileValidator.IMAGE_EXTENSIONS`，仅 file 目录收文档]，未知 dir 拒；调 common `FileStorageService` 返 `{url,name,size}`，业务表只存 url；OSS 未配置回占位 URL 便于联调）、`VolunteerFileUploadController`（**志愿者端上传** `POST /v/files/upload`：给「管理团队」志愿者在小程序发活动传封面用；与 `/a/files` 分开因小程序持志愿者 token 过不了 `/a/**`，权限走默认 login 域吃志愿者 RBAC `@SaCheckPermission(activity:publish)`，**仅放 `dir=activity` 限图片**，其它目录拒）、`SuperAdminInitializer`（启动预置超管）。迁移脚本在 common；`RedissonClient` 由 `redisson-spring-boot-starter` 自动从 `spring.data.redis` 装配
