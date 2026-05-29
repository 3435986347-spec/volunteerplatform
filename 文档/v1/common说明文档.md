# hengde-volunteer-common 模块说明文档

> 本文档说明 `hengde-volunteer-common` 模块里各公共类的用途、字段含义与用法，供前后端对接和后续模块开发参考。
> common 的定位是「全项目都会用到的公共基础设施」——统一返回体、错误码、异常、基础实体、工具类、常量与枚举，以及加密/脱敏、短信、对象存储、Excel、分页、数据库迁移、测试基座等可复用能力。它是**被动复用层**：各模块按需注入/调用（RedisConfig 这类全局序列化配置是少数例外，见对应章节说明）。

---

## 目录

- [一、统一返回结果 Result](#一统一返回结果-result)
- [二、错误码枚举 ResultCode](#二错误码枚举-resultcode)
- [三、业务异常 BusinessException](#三业务异常-businessexception)
- [四、基础实体 BaseEntity](#四基础实体-baseentity)
- [五、自动填充处理器 MyMetaObjectHandler](#五自动填充处理器-mymetaobjecthandler)
- [六、登录鉴权相关](#六登录鉴权相关)
- [七、Redis 相关](#七redis-相关)
- [八、数据加密与脱敏](#八数据加密与脱敏)
- [九、短信能力](#九短信能力)
- [十、文件存储（OSS）](#十文件存储oss)
- [十一、Excel 工具 ExcelUtil](#十一excel-工具-excelutil)
- [十二、分页 PageQuery / PageResult](#十二分页-pagequery--pageresult)
- [十三、数据库迁移（Flyway）](#十三数据库迁移flyway)
- [十四、测试基座 TestcontainersConfig](#十四测试基座-testcontainersconfig)
- [十五、常量与枚举](#十五常量与枚举)
- [十六、什么是枚举（科普）](#十六什么是枚举科普)
- [十七、包结构总览](#十七包结构总览)

---

## 一、统一返回结果 Result

**位置**：`com.hengde.common.result.Result`

所有 Controller 接口都返回这个对象，保证前端拿到的 JSON 结构永远一致：

```json
{
  "code": 200,
  "message": "成功",
  "data": { }
}
```

### 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | Integer | 状态码，200 成功，其余见 [ResultCode](#二错误码枚举-resultcode) |
| `message` | String | 给人看的提示信息 |
| `data` | T（泛型） | 业务数据，失败时一般为 null |

> 泛型 `T` 表示业务数据类型：`Result<UserVO>`、`Result<List<Xxx>>`；不需要返回数据时用 `Result<Void>`。

### 静态工厂方法（不要自己 new，用这些）

| 方法 | 用途 | 返回 |
| --- | --- | --- |
| `Result.ok()` | 成功、无数据（如新增/删除） | code=200, message="成功", data=null |
| `Result.ok(data)` | 成功、带数据 | code=200, message="成功", data=传入值 |
| `Result.fail(code, message)` | 失败、自定义码和提示 | data=null |
| `Result.fail(resultCode)` | 失败、用错误码枚举（推荐） | data=null |

### 用法示例

```java
return Result.ok(userVO);                      // 成功带数据
return Result.ok();                            // 成功无数据
return Result.fail(ResultCode.USER_NOT_FOUND); // 失败：用枚举（推荐）
return Result.fail(400, "手机号格式不正确");      // 失败：自定义
```

---

## 二、错误码枚举 ResultCode

**位置**：`com.hengde.common.result.ResultCode`

集中维护「状态码 + 中文提示」。约定：**400~599 沿用 HTTP 语义；1000 起为业务自定义码**，避免和 HTTP 码混淆。

| 枚举 | code | message |
| --- | --- | --- |
| `SUCCESS` | 200 | 成功 |
| `BAD_REQUEST` | 400 | 请求参数错误 |
| `UNAUTHORIZED` | 401 | 未登录或登录已过期 |
| `FORBIDDEN` | 403 | 无权访问 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `SERVER_ERROR` | 500 | 服务器内部错误 |
| `BUSINESS_ERROR` | 1000 | 业务异常 |
| `PARAM_ERROR` | 1001 | 参数校验失败 |
| `USER_NOT_FOUND` | 2001 | 用户不存在 |
| `USER_ALREADY_EXISTS` | 2002 | 用户已存在 |
| `PASSWORD_ERROR` | 2003 | 密码错误 |
| `SMS_CODE_ERROR` | 2004 | 验证码错误或已过期 |

> 提供 `getCode()` / `getMessage()` 读取。新增错误码时按「领域段」继续往后排（如 3000 起活动、4000 起组织……）。

---

## 三、业务异常 BusinessException

**位置**：`com.hengde.common.exception.BusinessException`

用于「可预期」的错误（如"用户不存在""验证码错误"）。业务代码里直接 `throw`，由 **api 模块的全局异常处理器**统一捕获并转成 `Result` 返回（全局处理器不在 common，在 api）。

- 继承 `RuntimeException`（非受检异常），不用在方法签名上到处 `throws`。
- 字段 `code`：业务状态码，对应 `ResultCode.getCode()`。

```java
throw new BusinessException(400, "手机号已被占用");   // 自定义码 + 提示
throw new BusinessException("操作失败");              // 只给提示，码默认 400
throw new BusinessException(ResultCode.USER_NOT_FOUND); // 用错误码枚举（最常用）
```

---

## 四、基础实体 BaseEntity

**位置**：`com.hengde.common.entity.BaseEntity`

所有数据库实体的基类。业务实体只需 `extends BaseEntity`。

| 字段 | 类型 | MyBatis-Plus 注解 | 说明 |
| --- | --- | --- | --- |
| `id` | Long | `@TableId(type = IdType.AUTO)` | 主键，数据库自增 |
| `createTime` | LocalDateTime | `@TableField(fill = INSERT)` | 创建时间，仅插入时自动填充 |
| `updateTime` | LocalDateTime | `@TableField(fill = INSERT_UPDATE)` | 更新时间，插入和更新都填充 |
| `isDeleted` | Integer | `@TableField(fill = INSERT)` + `@TableLogic` | 逻辑删除：0=未删除，1=已删除；插入时由自动填充器填 0 |

> **逻辑删除**：`@TableLogic` 让删除变成「改这个字段」而不是真正 DELETE，查询自动带「未删除」条件。
> ⚠️ 它和用户状态里的「注销」（`UserStatus.DELETED`）是两码事：一个是「行被隐藏」，一个是「账号业务状态为已注销」。

---

## 五、自动填充处理器 MyMetaObjectHandler

**位置**：`com.hengde.common.handler.MyMetaObjectHandler`

配合 BaseEntity 上的 `@TableField(fill=...)`，在 insert/update 时自动给字段赋值。

| 触发时机 | 自动填充内容 |
| --- | --- |
| insert | `createTime` = 当前时间、`updateTime` = 当前时间、`isDeleted` = 0 |
| update | `updateTime` = 当前时间 |

---

## 六、登录鉴权相关

### 1. JwtUtil（V1 暂不接入）

**位置**：`com.hengde.common.utils.JwtUtil`

> ⚠️ **V1 使用说明**：V1 的多角色登录态与鉴权**统一走 Sa-Token**（token 存 Redis）。**本类 V1 暂不接入**，保留以备特殊场景，**切勿与 Sa-Token 并行成两套 token 体系**。

| 方法 | 说明 |
| --- | --- |
| `generate(Long userId)` | 生成 token（有效期 7 天），把 userId 写入 claim |
| `verify(String token)` | 验证签名和过期，返回 userId；失败抛 `BusinessException(UNAUTHORIZED)` |
| `parseUserId(String token)` | 仅解析 userId、不校验过期（仅调试用） |

实现基于 `io.jsonwebtoken`（jjwt 0.12.x）。密钥目前写死在常量里，**上线前必须改为从配置/环境变量读取**。

### 2. PasswordUtil 密码加密

**位置**：`com.hengde.common.utils.PasswordUtil`

基于 Hutool 的 **BCrypt**。

| 方法 | 说明 |
| --- | --- |
| `encrypt(String rawPassword)` | 加密明文密码，返回密文（可直接存库） |
| `matches(String rawPassword, String encryptedPassword)` | 校验明文与密文是否匹配（登录时用） |

> BCrypt 自带随机盐、故意算得慢，抗彩虹表/暴破；密文含盐，校验无需单独存盐。

```java
String hash = PasswordUtil.encrypt("123456");
boolean ok = PasswordUtil.matches("123456", hash);
```

---

## 七、Redis 相关

### 1. RedisConfig 配置

**位置**：`com.hengde.common.config.RedisConfig`

> **定位说明**：本类虽属「应用级配置」，但它提供的 `RedisTemplate<String,Object>` 是 common 里 `RedisUtil` 的依赖 bean。领域模块依赖 common 而**不**依赖 api，若把本类挪到 api，领域模块的测试上下文就拿不到这个 bean，`RedisUtil` 会回落到默认 JDK 序列化、与生产的 JSON 序列化不一致。故本类与 `RedisUtil` 配套**留在 common**。

作用：替换 Spring Boot 默认 RedisTemplate（JDK 序列化乱码）为：key = 字符串、value = JSON。

> ⚠️ Boot 4 下 `GenericJackson2JsonRedisSerializer`（Jackson 2 版）已标记废弃、未来移除，替代类是 `GenericJacksonJsonRedisSerializer`（Jackson 3）。目前只是编译警告、功能正常，择机迁移时用新类 + `com.hengde.*` 范围的 `PolymorphicTypeValidator` 比旧的全开放更安全。

### 2. RedisUtil 工具类

**位置**：`com.hengde.common.utils.RedisUtil`（`@Component`，setter 注入）

| 分类 | 方法 |
| --- | --- |
| 通用 key | `expire` / `getExpire`（-1 永不过期，-2 不存在）/ `hasKey` / `delete` |
| String | `set(k,v)` / `set(k,v,seconds)` / `get` / `increment` / `decrement` |
| Hash | `hSet / hGet / hDelete` |
| List | `lPush / rPush / lRange` |
| Set | `sAdd / sMembers / sIsMember` |

> ⚠️ **计数 key 只用 increment/decrement，不要先 set**（set 存成 JSON 字符串后 increment 会报 not an integer）。

> common 是库，**没有 Redis 连接配置**；连哪台 redis 在 api 的 `application.yaml` 配 `spring.data.redis.*`。

---

## 八、数据加密与脱敏

**位置**：`com.hengde.common.crypto`（加解密/哈希）+ `com.hengde.common.utils.MaskUtil`（脱敏）

身份证号、手机号等敏感 PII 的方案是「**加密存储、按需解密、展示脱敏**」：密文入库，另存确定性哈希列供查重/查询，列表/详情默认展示脱敏值，只有确需明文时才解密。

### 1. SecurityProperties 配置

**位置**：`com.hengde.common.crypto.SecurityProperties`（`@ConfigurationProperties("hengde.security")`）

| 字段 | 说明 |
| --- | --- |
| `aesKey` | AES 密钥（任意字符串，CryptoUtil 用 SHA-256 派生为 256 位密钥） |
| `hmacKey` | HMAC 密钥（用于可查询哈希） |

> 两个字段都有 dev 默认值（仅为让无配置的领域测试上下文能启动）；api 的 yaml 会覆盖，**生产必须用环境变量覆盖**。白标每实例独立密钥。

### 2. CryptoUtil 加解密与哈希

**位置**：`com.hengde.common.crypto.CryptoUtil`（`@Component`，setter 注入）

| 方法 | 说明 |
| --- | --- |
| `encrypt(plain)` / `decrypt(stored)` | **AES-256/GCM**，每次随机 12 字节 IV，输出 `base64(IV‖密文+tag)`；GCM 自带完整性校验，**绝不用 ECB** |
| `hashIdCard(idCard)` | 身份证规范化（**转大写去空格**）后 HMAC-SHA256，供唯一约束/查重 |
| `hashPhone(phone)` | 手机号规范化（**只留数字**）后 HMAC-SHA256 |

> 为什么要哈希列：AES-GCM 带随机 IV，同一明文两次密文不同，无法直接等值查；哈希是确定性的，用来做唯一索引和精确查询。

### 3. MaskUtil 脱敏

**位置**：`com.hengde.common.utils.MaskUtil`（静态工具）

| 方法 | 效果 |
| --- | --- |
| `maskPhone(phone)` | `138****1234`（前 3 后 4） |
| `maskIdCard(idCard)` | `4101**********1234`（前 4 后 4） |
| `maskName(name)` | `张**`（保留姓） |

### 用法示例

```java
// 写入：加密 + 哈希
volunteer.setPhone(cryptoUtil.encrypt(rawPhone));
volunteer.setPhoneHash(cryptoUtil.hashPhone(rawPhone));
// 查重/精确查：走哈希列
wrapper.eq("phone_hash", cryptoUtil.hashPhone(rawPhone));
// 展示：默认脱敏；确需明文才 decrypt
vo.setPhone(MaskUtil.maskPhone(cryptoUtil.decrypt(volunteer.getPhone())));
```

---

## 九、短信能力

**位置**：`com.hengde.common.sms`

底层走火山引擎 all-in-one SDK（`com.volcengine:volc-sdk-java`，短信能力在 `com.volcengine.service.sms`；火山没有独立短信 artifact）。分**发送通道**与**验证码服务**两层。

> **职责边界**：common 只负责「发短信」和「验证码通用逻辑」；具体业务（注册校验、换绑手机号等）由各领域（如 auth）调用本层。

### 1. SmsProperties 配置（`@ConfigurationProperties("hengde.sms")`）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | false | false（dev/test 默认）时只打日志不发 |
| `accessKey` / `secretKey` | — | 火山访问凭证 |
| `signName` | — | 短信签名（「雷州市恒德爱心公益协会」） |
| `smsAccount` | — | 火山短信账号 |
| `region` | cn-north-1 | 服务区域 |
| `templates` | {} | 各用途模板 ID，键为用途（如 `verify-code`） |
| `codeLength` / `codeExpireSeconds` / `codeResendSeconds` | 6 / 300 / 60 | 验证码位数 / 有效期 / 重发间隔 |

### 2. SmsService / SmsServiceImpl 发送通道

| 方法 | 说明 |
| --- | --- |
| `send(phone, templateId, params)` | 通用发送，`params` 用 `setTemplateParamByMap` 自动转 JSON |
| `sendVerifyCode(phone, code)` | 便捷方法，用 `templates` 里的验证码模板，参数 `{code: ...}` |

- 常量 `TEMPLATE_VERIFY_CODE = "verify-code"`、`PARAM_CODE = "code"`；火山控制台建模板时占位符须用 `${code}`。
- 火山客户端懒加载；`enabled=false` 打日志直接返回；失败抛 `BusinessException`。

### 3. VerifyCodeService 验证码服务（`@Service`）

| 方法 | 说明 |
| --- | --- |
| `sendCode(phone, scene)` | 生成 → 存 Redis（key=`sms:code:{scene}:{phone}`，带 TTL）→ 发送；重发间隔内重复请求抛异常 |
| `verify(phone, scene, code)` | 校验，通过即删除（一次性消费）；不符/过期抛 `SMS_CODE_ERROR` |

### 4. SmsScene 场景常量

`REGISTER`（注册）/ `RESET_PASSWORD`（找回密码）/ `CHANGE_PHONE`（换绑手机号）——不同场景 Redis key 与限流互相隔离。

```java
verifyCodeService.sendCode("13800000000", SmsScene.REGISTER);
verifyCodeService.verify("13800000000", SmsScene.REGISTER, "123456");
```

---

## 十、文件存储（OSS）

**位置**：`com.hengde.common.oss`，底层阿里云 OSS（`aliyun-sdk-oss`）。

### 1. OssProperties（`@ConfigurationProperties("hengde.oss")`）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | false | false（dev/test 默认）只打日志、返回占位 URL |
| `endpoint` / `bucket` / `accessKeyId` / `accessKeySecret` | — | OSS 接入点 / 桶 / 凭证 |
| `urlPrefix` | — | CDN/自定义域名；留空按「桶名+endpoint」拼默认域名 |
| `maxFileSize` | 10MB | 单文件大小上限 |
| `allowedExtensions` | 图片+常见文档 | `upload(MultipartFile)` 的基线允许扩展名 |

### 2. FileStorageService / AliyunOssFileStorageService（`@Service`）

| 方法 | 说明 |
| --- | --- |
| `upload(MultipartFile file, String dir)` | 上传文件，对象名自动生成 `dir/yyyyMMdd/UUID.ext`，返回 URL；入口已做基线校验 |
| `upload(byte[] data, String objectName, String contentType)` | 上传字节（二维码、PDF 证书等） |
| `delete(String objectName)` | 删除对象 |

> 接口抽象了底层，将来换 MinIO 只需另写实现，业务代码不动。

### 3. FileValidator 上传校验（静态工具）

| 成员 | 说明 |
| --- | --- |
| `validate(file, allowedExtensions, maxSize)` | 校验扩展名+大小，不合规抛 `BusinessException`（400） |
| `IMAGE_EXTENSIONS` | 图片扩展名集合，供「仅允许图片」场景使用 |
| `extensionOf(filename)` | 取小写扩展名 |

> ⚠️ 当前只校验扩展名+大小，**未**校验文件魔数/真实 MIME。V1 上传口都在鉴权后够用；将来若开放**公开上传入口**，需用 Hutool `cn.hutool.core.io.FileTypeUtil` 按文件头探测真实类型，挡「改后缀绕过」。

---

## 十一、Excel 工具 ExcelUtil

**位置**：`com.hengde.common.excel.ExcelUtil`（静态工具，基于 EasyExcel）

| 方法 | 说明 |
| --- | --- |
| `export(response, fileName, sheetName, clazz, data)` | 导出 .xlsx，自动设下载头，浏览器直接下载 |
| `read(inputStream, clazz)` | 读取上传 Excel 全部行，返回 `List<T>` |

导入导出列由实体类上的 EasyExcel 注解（`@ExcelProperty` 等）描述。

> EasyExcel 4.0.3 的 `com.alibaba:easyexcel` 是空壳重定向包，真实实现透传依赖 `easyexcel-core`，门面类仍是 `com.alibaba.excel.EasyExcel`。

---

## 十二、分页 PageQuery / PageResult

**位置**：`com.hengde.common.page`

### PageQuery（入参）

| 成员 | 说明 |
| --- | --- |
| `page` / `size` | getter **自动纠正**：page 兜底 1，size 兜底默认并钳到 `MAX_PAGE_SIZE` |
| `toPage()` | 转 MyBatis-Plus `Page` |

> 领域查询 DTO 可 `extends PageQuery` 再加筛选字段。

### PageResult\<T>（结果）

| 字段/方法 | 说明 |
| --- | --- |
| `records / total / page / size / pages` | 记录 / 总数 / 页码 / 每页 / 总页数 |
| `of(IPage)` | 从 MyBatis-Plus 分页结果一行转换 |
| `of(records, total, page, size)` | 非 MP 来源时手动构造 |

```java
Page<User> p = userMapper.selectPage(query.toPage(), wrapper);
return Result.ok(PageResult.of(p.convert(this::toVO)));
```

---

## 十三、数据库迁移（Flyway）

**位置**：`com.hengde.common` 模块的 `src/main/resources/db/migration/`

**全项目唯一的 Flyway 迁移序列集中放在 common**。Flyway 依赖（`flyway-core` / `flyway-mysql` / `spring-boot-flyway`）也在 common，于是：

- **api 运行期**：依赖 common → 拿到脚本 + Flyway，启动时自动建表/升级；
- **各领域 Testcontainers 测试**：依赖 common → 同样拿到脚本 + Flyway，`@SpringBootTest + @Import(TestcontainersConfig)` 起容器后自动把全量 schema 建到容器库，mapper 测试可直接查真表。

约定：

- 文件名 `V{n}__描述.sql`，**版本号全局唯一**（所有领域共用一条序列，避免多领域各自 V1 撞车）。
- 公共列 `id / create_time / update_time / is_deleted` 对应 [BaseEntity](#四基础实体-baseentity)。
- DDL **不写硬外键**（如 `squad_id` 只是普通列），保证单领域测试建全量表时不因跨域外键报错。
- ⚠️ **Boot 4 注意**：Flyway 自动配置被拆到独立模块 `spring-boot-flyway`，缺它则「有 flyway-core 也不会触发迁移」（静默不建表）。

**现有迁移序列：**

| 版本 | 文件 | 内容 |
| --- | --- | --- |
| V1 | `V1__init_schema.sql` | 初始 schema（含 `admin_user` 等基础表） |
| V2 | `V2__organization_rbac.sql` | organization 域 RBAC：建 `permission`（权限点目录，`type` 1菜单/2操作/3审核）与 `admin_permission`（子账号↔权限点关联，物理删除、唯一约束 `uk_admin_perm`）两表，并预置 **23 个可分配权限点**。注：仅超管的 `user:edit`、`org:perm-assign` 不入表（写死、不可分配）。归属 organization 域但脚本按约定集中在 common。

**⚠️ 迁移文件不可变（非常重要）：**

- **已经被 Flyway 执行过的迁移文件，内容绝对不要再改**（包括格式、空格）。Flyway 会对每个脚本算 checksum 存进 `flyway_schema_history`，改动后启动即报 **checksum mismatch** 拒绝迁移。
- 后续表结构变更一律**新增** `V2__xxx.sql`、`V3__xxx.sql`，而不是回头改 `V1`。
- 本地开发若移动/修改过 `V1` 导致 checksum mismatch：**重建开发库**（drop/create schema 让其从头跑），或在确认结构与脚本一致后执行 `flyway repair` 修正历史表 checksum。
- 注意 `mvn clean` 只清理 `target/`，**不会清** 数据库里的 `flyway_schema_history`；checksum 问题不会因 clean 消失。

---

## 十四、测试基座 TestcontainersConfig

**位置**：`com.hengde.common.testsupport.TestcontainersConfig`（在 `src/test`，随 **test-jar** 发布）

各领域模块的 `@SpringBootTest` 通过 `@Import(TestcontainersConfig.class)` 复用同一份 MySQL 容器。`@ServiceConnection` 自动把容器接管为数据源，配合上一节的 common 迁移脚本，Flyway 在容器库建好全量 schema。**不用 H2**（MyBatis-Plus 走 MySQL 方言、`flyway-mysql` 不兼容 H2）。**跑测试需本机有 Docker。**

消费方 test 依赖：

```xml
<dependency>
  <groupId>com.hengde</groupId>
  <artifactId>hengde-volunteer-common</artifactId>
  <version>1.0-SNAPSHOT</version>
  <type>test-jar</type>
  <scope>test</scope>
</dependency>
```

并自带 `spring-boot-testcontainers` 与 `org.testcontainers:mysql`（test-jar 的依赖不传递，需各模块自行声明，版本由父 POM 管理）。

```java
@SpringBootTest
@Import(TestcontainersConfig.class)
class XxxMapperTest {
    @Autowired
    private XxxMapper xxxMapper;
    // 容器库已有全量表，可直接 CRUD
}
```

---

## 十五、常量与枚举

### 1. UserStatus 用户状态（`interface` 常量）

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `NORMAL` | 0 | 正常（取 0 与 DB int 默认值对齐） |
| `BANNED` | 1 | 禁用 |
| `DELETED` | 2 | 注销（业务状态，≠ 行级逻辑删除 isDeleted） |

### 2. CommonConstants 通用常量（`interface` 常量）

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `TOKEN_HEADER` | "Authorization" | 携带 token 的请求头名（与 Sa-Token token-name 一致，无 Bearer 前缀） |
| `DEFAULT_PAGE_SIZE` | 10 | 默认分页大小 |
| `MAX_PAGE_SIZE` | 100 | 最大分页大小 |

### 3. Gender / PoliticalStatus / Grade（真正的 `enum`）

注册采集与后台展示用的固定枚举，**入库存 `code`**（由 MyBatis-Plus 的 `@EnumValue` 标注决定），实体字段直接用枚举类型；都带 `fromCode(code)` 反查。

| 枚举 | 取值 | 备注 |
| --- | --- | --- |
| `Gender` | 0未知 / 1男 / 2女 | 可由身份证号解析 |
| `PoliticalStatus` | 1群众 / 2共青团员 / 3中共预备党员 / 4中共党员 / 5民主党派 | — |
| `Grade` | 1~9 年级 / 10-12 高中 / 13-17 大学 / 18 毕业 | **有序编码**，`next()` 升一级（毕业封顶），供 9 月年级自动升级 job 用 |

> 这三个枚举放 common（与 UserStatus 并列），因为 auth 注册、user 改资料、后台筛选、activity 报名条件等多领域都会用。

---

## 十六、什么是枚举（科普）

**枚举（enum）** 是 Java 里表示「一组固定取值」的特殊类型。比如「用户状态」只可能是 正常/禁用/注销，用枚举一次列清楚，避免到处写 `1`、`2` 这种「魔法数字」。

好处：

1. **可读**：`ResultCode.USER_NOT_FOUND` 比数字 `2001` 一眼就懂；
2. **不易错**：取值被限定，编译期挡住乱传；
3. **能带数据和方法**：`ResultCode`、`Grade` 等每项都绑了 `code`/`label` 并提供读取方法。

> 小区分：`ResultCode`、`Gender`、`PoliticalStatus`、`Grade` 是真正的 `enum`；而 `UserStatus`、`CommonConstants`、`SmsScene` 是用 `interface` 定义的**常量集合**（`Integer`/`String` 常量），不是枚举。两者都消灭魔法数字，形态不同——枚举适合「一组互斥状态且要带行为」，常量接口适合「零散的固定值」。

---

## 十七、包结构总览

```
hengde-volunteer-common/
└── src/
    ├── main/java/com/hengde/common/
    │   ├── result/          Result、ResultCode
    │   ├── exception/       BusinessException
    │   ├── entity/          BaseEntity
    │   ├── handler/         MyMetaObjectHandler
    │   ├── config/          RedisConfig（与 RedisUtil 配套，留在 common）
    │   ├── crypto/          SecurityProperties、CryptoUtil（AES-GCM + HMAC）
    │   ├── utils/           JwtUtil、PasswordUtil、RedisUtil、MaskUtil
    │   ├── constant/        UserStatus、CommonConstants、Gender、PoliticalStatus、Grade
    │   ├── sms/             SmsProperties、SmsService(Impl)、VerifyCodeService、SmsScene
    │   ├── oss/             OssProperties、FileStorageService、AliyunOssFileStorageService、FileValidator
    │   ├── excel/           ExcelUtil
    │   └── page/            PageQuery、PageResult
    ├── main/resources/
    │   └── db/migration/    V1__init_schema.sql、V2__organization_rbac.sql（全项目唯一迁移序列）
    └── test/java/com/hengde/common/
        └── testsupport/     TestcontainersConfig（随 test-jar 发布）
```

---

> 技术栈口径以 `CLAUDE.md` 与父 POM 为准：Spring Boot 4 / Java 17、MyBatis-Plus（boot4 starter）、Hutool、jjwt、Sa-Token（鉴权）、Redis、Flyway、火山 volc-sdk-java（短信）、阿里云 OSS、EasyExcel、Testcontainers 等。
