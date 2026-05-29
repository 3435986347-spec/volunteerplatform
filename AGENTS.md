# Repository Guidelines

## Project Structure & Module Organization

Code lives under `代码/`; product and API references live under `文档/`. Treat `文档/url文档v1.md` and `文档/雷州市恒德爱心公益协会志愿者小程序V1.md` as the V1 contract for API paths and feature scope.

- `代码/hengde-volunteer-parent/`: parent POM for Java 17, Spring Boot 4.0.6, dependency versions, and shared plugins.
- `代码/hengde-volunteer-common/`: shared library for `Result`, `ResultCode`, exceptions, base entity, constants, Redis/JWT/password utilities, Excel, Flyway, and common test support.
- `代码/hengde-volunteer-auth/`: authentication and volunteer account domain, including login, account query services, and sensitive-data decryption output.
- `代码/hengde-volunteer-organization/`: organization domain, including groups, squads, RBAC, and organization admin workflows.
- `代码/hengde-volunteer-activity/`: activity publish/query/enrollment/admin workflows.
- `代码/hengde-volunteer-publicity/`: publicity/announcement/banner workflows.
- `代码/hengde-volunteer-api/`: Spring Boot entrypoint, API-level config, global exception handling, filters, Flyway migrations, and aggregate controllers.

Keep Java packages under `com.hengde`. API controllers belong in `com.hengde.api.controller`; common reusable code belongs in `com.hengde.common`.

## Build, Test, and Development Commands

Run Maven commands from the module being changed:

- `cd 代码/hengde-volunteer-parent; .\mvnw.cmd clean install`
- `cd 代码/hengde-volunteer-common; .\mvnw.cmd test`
- `cd 代码/hengde-volunteer-common; .\mvnw.cmd clean install`
- `cd 代码/hengde-volunteer-auth; .\mvnw.cmd clean install`
- `cd 代码/hengde-volunteer-organization; .\mvnw.cmd clean install`
- `cd 代码/hengde-volunteer-activity; .\mvnw.cmd clean install`
- `cd 代码/hengde-volunteer-publicity; .\mvnw.cmd clean install`
- `cd 代码/hengde-volunteer-api; .\mvnw.cmd test`

Child module POMs intentionally keep `<relativePath/>`; install the parent before building children.

## Coding Style & Naming Conventions

Use Java 17 and 4-space indentation. Class names use `PascalCase`; methods and fields use `camelCase`; constants use `UPPER_SNAKE_CASE`. Prefer Spring constructor or setter injection consistently with nearby code. Use Lombok only where it removes boilerplate without hiding behavior.

Controller paths must not include `/api`; `server.servlet.context-path=/api` supplies it. Use role prefixes from the URL document: `/v`, `/a`, and reserved `/e`.

In `hengde-volunteer-api`, keep Jackson behavior in `JacksonConfig` Java code rather than `spring.jackson.*` YAML because Boot 4 binding differs from older Boot versions. Use the default Spring Boot HikariCP datasource; do not add Druid to the API module.

Search behavior is fixed by the V1 contract:

- `GET /v/search` is a single information stream.
- It returns `PageResult<SearchItemVO>`.
- The merged scope is activity + publicity + group + squad.
- The order is fixed: activity -> publicity -> group -> squad.
- `SearchItemVO` fields are `type`, `id`, `title`, `summary`, `imageUrl`.

Organization approval rules are also fixed:

- Approving a squad application must use CAS on `status = 待审核`.
- Approving a disabled squad must be rejected.
- Volunteer assignment to `squad_id` must use conditional update (`squad_id is null`) with explicit `update_time`.
- If a rule is already decided in the doc or current implementation, keep the same wording and error semantics.

## Testing Guidelines

Tests use JUnit 5 and Spring Boot test. Put tests under `src/test/java` with names ending in `Test` or `Tests`.

- Add focused unit tests for utilities and config behavior.
- Use `@SpringBootTest` when verifying context wiring, Flyway, MyBatis, Redis, or Testcontainers.
- MySQL/Redis integration tests should reuse common test-jar Testcontainers config and import only what the module needs.
- Testcontainers starts its own temporary containers; do not depend on fixed local 3306/6379 containers, but Docker Desktop daemon must be running.
- Run the relevant module’s `.\mvnw.cmd test` before handing off changes.

## Commit & Pull Request Guidelines

Use concise conventional-style commits, such as:

- `feat: add activity enrollment management`
- `fix: make enrollment audit update atomic`

PRs or handoff notes should include changed modules, test results, relevant requirement or API document sections, and any database migration, configuration, or permission-point impact.

## Security & Configuration Tips

Do not commit production secrets. Local defaults may exist for development, but SMS, OSS, WeChat, Redis, and database credentials should come from environment variables or profile-specific config. For OpenAPI auth, use `apiKey` in the `Authorization` header to match Sa-Token’s direct token header convention.

Sensitive data decryption should stay inside the module that owns the domain data. For example, volunteer phone numbers may be decrypted in `auth` service outputs for admin-facing scenarios; callers should not manipulate encrypted columns directly.
