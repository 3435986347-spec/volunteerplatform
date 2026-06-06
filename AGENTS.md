# Repository Guidelines

## Project Structure

Code lives under `代码/`; product, API, and test references live under `文档/`. Treat these documents as the working contract:

- `CLAUDE.md`: current implementation status, architecture rules, command notes, and module boundaries.
- `文档/业务领域划分.md`: 12-domain functional ownership map.
- `文档/功能清单.md`: full requirement checklist and current completion state.
- `文档/v1/url文档v1.md`: API path contract.
- `文档/v1/雷州市恒德爱心公益协会志愿者小程序V1.md`: V1 feature scope.

Implemented modules:

- `代码/hengde-volunteer-parent/`: parent POM, Java 17, Spring Boot 4.0.6, dependency management, shared plugins.
- `代码/hengde-volunteer-common/`: shared primitives, errors, crypto, Redis, SMS, OSS, Excel, pagination, Flyway migrations, and testcontainers test support.
- `代码/hengde-volunteer-auth/`: volunteer login/register/agreement/signature, admin auth, PII encryption/decryption, volunteer query/admin services.
- `代码/hengde-volunteer-organization/`: RBAC sub-accounts, groups, squads, structure, manager-team flag workflows, and volunteer-side RBAC grants.
- `代码/hengde-volunteer-activity/`: activity publishing, enrollment, attendance, service records, points, leaders, messages, recurring publish, backfill, volunteer-side publishing, and leader views.
- `代码/hengde-volunteer-publicity/`: banners, announcements, and downloadable files.
- `代码/hengde-volunteer-api/`: single deployable Spring Boot app, global config, filters, exception handling, aggregate controllers.

Planned but not yet built modules include `user`, `data`, `donate`, `honor`, `social`, and `enterprise`. Do not create one unless the task explicitly requires that domain.

Keep Java packages under `com.hengde`. Domain modules own their own `controller/service/dao/entity/dto/vo` packages. Cross-domain calls should go through public service APIs, not direct mapper access.

## Build And Test

Run Maven from `代码/hengde-volunteer-parent`. The parent POM is dependency management only and has no `<modules>` reactor, so use `-f` for child modules.

```powershell
cd 代码\hengde-volunteer-parent

# Install parent first.
.\mvnw.cmd install -N

# Build/install modules in dependency order when downstream modules need local changes.
.\mvnw.cmd clean install -DskipTests -f ..\hengde-volunteer-common\pom.xml
.\mvnw.cmd clean install -DskipTests -f ..\hengde-volunteer-auth\pom.xml
.\mvnw.cmd clean install -DskipTests -f ..\hengde-volunteer-organization\pom.xml
.\mvnw.cmd clean install -DskipTests -f ..\hengde-volunteer-activity\pom.xml
.\mvnw.cmd clean install -DskipTests -f ..\hengde-volunteer-publicity\pom.xml
.\mvnw.cmd clean install -DskipTests -f ..\hengde-volunteer-api\pom.xml

# Run tests for a changed module.
.\mvnw.cmd test -f ..\hengde-volunteer-activity\pom.xml
.\mvnw.cmd test -f ..\hengde-volunteer-organization\pom.xml
.\mvnw.cmd test -f ..\hengde-volunteer-api\pom.xml

# Run a single test class or method.
.\mvnw.cmd test -f ..\hengde-volunteer-activity\pom.xml -Dtest=ActivityAttendanceServiceTest
```

Docker Desktop must be running for MySQL/Redis Testcontainers tests.

## Coding Rules

- Use Java 17 and 4-space indentation.
- Class names use `PascalCase`; methods and fields use `camelCase`; constants use `UPPER_SNAKE_CASE`.
- Use setter injection consistently with this repo. Hand-write setters with `@Autowired`; Lombok-generated setters do not carry `@Autowired`.
- Use Lombok only where it removes boilerplate without hiding behavior.
- Controller paths must not include `/api`; `server.servlet.context-path=/api` supplies it.
- Use role prefixes from the URL contract: `/v` for volunteers, `/a` for admin, `/e` reserved for enterprise.
- Keep Jackson behavior in `hengde-volunteer-api` `JacksonConfig`, not `spring.jackson.*` YAML.
- Use Spring Boot's default HikariCP datasource; do not add Druid.
- Flyway scripts are centralized in `hengde-volunteer-common/src/main/resources/db/migration/` with one global version sequence. Current sequence is V18.

## Fixed Product Contracts

Search is fixed by V1:

- `GET /v/search` returns a single information stream.
- Response type is `PageResult<SearchItemVO>`.
- Scope is activity + publicity + group + squad.
- Merge order is activity -> publicity -> group -> squad.
- `SearchItemVO` fields are `type`, `id`, `title`, `summary`, `imageUrl`.

Organization approval rules are fixed:

- Squad application approval must use CAS on pending status.
- Disabled squads cannot be approved or joined from volunteer flows.
- Volunteer assignment to `squad_id` must use conditional update with `squad_id is null` and explicit `update_time`.
- One active group per volunteer is enforced by service checks plus the V9 unique constraint.
- Group join approval must be reachable through `GET /v/organization/groups/{id}/join-applications`; this returns pending join rows and their `memberId` for approve/reject.

Volunteer-side RBAC rules are fixed:

- V18 adds `volunteer_permission` and `permission.volunteer_grantable`.
- Only activity-domain grantable points may be assigned to volunteers; `activity:menu` is not volunteer-grantable.
- Volunteer permission assignment is super-admin only.
- Non-empty assignment requires the target volunteer to be active and `manager_flag=1`.
- Empty assignment is allowed as a cleanup path even after the volunteer is downgraded.
- Permission reads use `VolunteerQueryService.isActiveManager`; suspended, deleted, or downgraded volunteers get no codes.
- `@SaCheckPermission` on `/v` endpoints must not use `type="admin"`; it should use the default login realm.
- `POST /v/activity/activities` consumes `activity:publish` for manager-team volunteers. Recurring and historical publishing remain admin-only.

Activity rules already decided in code/docs should keep their current wording and error semantics:

- `enroll_scope=1` specified squad enrollment is still postponed and should target a single squad when implemented.
- Activity albums are postponed until `social` exists.
- Historical activities are not visible to volunteers and must not grant points.
- Attendance/points changes require the V14 two-step approval flow.
- Activity backfill approval is terminal: it writes confirmed attendance directly; historical backfill records time only and finalizes points as issued with 0 points.
- Leader emergency reporting is a configured phone number (`hengde.activity.emergency-phone`) returned as `emergencyPhone`; no table is used.
- Manual violation records use `description` as required free text with max length 512.
- Manual `violationType` is optional and limited to 0-4. Type 5 means absence and is system-generated only by marking absent.
- `recordViolation` must keep service-level guards for required text, max length, and type range so internal callers cannot bypass DTO validation.
- Violation record views must not resolve `recorded_by` blindly through volunteer IDs because it can also contain `admin_user.id`. Only resolve `recordedByName` when the recorder is a volunteer leader of that activity; otherwise leave it null.

## Testing Guidelines

Tests use JUnit 5 and Spring Boot test. Put tests under `src/test/java` with names ending in `Test` or `Tests`.

- Prefer `@SpringBootTest` for service behavior, context wiring, Flyway, MyBatis, Redis, Redisson, and Testcontainers.
- Domain modules without a `main` app need a test-only `@SpringBootApplication` in `src/test/java/com/hengde/<domain>/`.
- MySQL/Redis integration tests should reuse common test-jar Testcontainers config and import only what the module needs.
- Do not use H2; migrations and MyBatis behavior target MySQL.
- Modules using Redisson must import Redis Testcontainers config.
- Domain module tests do not have the API module pagination interceptor; assert returned records, not `selectPage` totals.
- Run the relevant module test command before handing off code changes. For docs-only edits, state that tests were not run.

## Security And Data Ownership

- Do not commit production secrets. SMS, OSS, WeChat, Redis, database, and crypto secrets must come from environment variables or profile-specific config.
- Production config guards should fail fast when required production settings are missing or still using development placeholders.
- Sensitive volunteer data decryption stays in `auth`. Other modules should consume `auth` service outputs and should not manipulate encrypted columns directly.
- Public display use cases should request narrow outputs, such as name-only views, instead of loading decrypted phone numbers into memory.
- Cross-domain ID columns that can hold IDs from multiple tables must not be name-resolved without a subject-type discriminator or a safe local proof of subject type.
- OpenAPI auth uses `apiKey` in the `Authorization` header to match Sa-Token direct token behavior.

## Git And Handoff

- The worktree may contain user changes. Do not revert or rewrite unrelated files.
- Use concise conventional commits when commits are requested, such as `feat: add activity backfill` or `fix: guard attendance audit operator`.
- Handoff notes should include changed modules/files, test results, requirement/API document impact, migrations, permission points, and configuration changes.
