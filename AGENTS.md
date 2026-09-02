# Repository Guidelines

## Project Structure

Code lives under `代码/`. Product, API, design, deployment, and test references live under `文档/`. Treat these files as the working contract:

- `CLAUDE.md`: current implementation status, architecture rules, command notes, and module boundaries.
- `文档/业务领域划分.md`: 12-domain ownership map.
- `文档/功能清单.md`: requirement checklist and completion state.
- `文档/v1/url文档v1.md`: API path and permission contract.
- `文档/v1/后台管理页面设计prompt.md`: admin console UI/API contract.
- `文档/v1/雷州市恒德爱心公益协会志愿者小程序V1.md`: V1 feature scope.

Implemented backend modules:

- `代码/hengde-volunteer-parent/`: parent POM, Java 17, Spring Boot 4.0.6, dependency management, shared plugins.
- `代码/hengde-volunteer-common/`: shared primitives, errors, crypto, Redis, SMS, OSS, Excel, pagination, distributed lock helper, Flyway migrations, and Testcontainers support.
- `代码/hengde-volunteer-auth/`: volunteer login/register/agreement/signature, admin auth, PII encryption/decryption, volunteer query/admin services.
- `代码/hengde-volunteer-organization/`: RBAC sub-accounts, permissions, groups, squads, structure, manager-team flag workflows, admin `/a/auth/me`, and volunteer-side RBAC grants.
- `代码/hengde-volunteer-activity/`: activity publishing/review, enrollment, attendance, service records, points, leaders, messages, recurring publish, backfill, volunteer-side publishing, and leader views.
- `代码/hengde-volunteer-publicity/`: banners, announcements, and downloadable files.
- `代码/hengde-volunteer-user/`: admin volunteer management, search/filter/export, status changes, deletion, detail/update, and cross-domain display aggregates.
- `代码/hengde-volunteer-data/`: dashboard aggregate counts and data overview endpoints.
- `代码/hengde-volunteer-honor/`: rankings (attendance count / service minutes / points × month / year / total) with snapshot freezing of past periods, medals and role models (V2 batch 3), and **electronic certificates (V2 batch 4)** — auto-creation on secretary confirmation, lazy PDF rendering stamped onto the association's template, private storage with short-lived signed download URLs, batch upload, soft delete/restore, per-activity templates, plus a reconcile job that backfills certificates whose creation event was lost — **bounded by a lookback window** so a cron run can never mass-issue certificates for pre-refactor activities (the association has not ruled on back-issuing yet), with `POST /a/honor/certificates/reconcile?since=&activityId=` as the explicit, human-scoped remedy for gaps outside that window. Also **rewards/penalties (V2 batch 5)** — one order table for both praise and punishment, organiser review before anything becomes visible, a 7-day appeal window whose deadline is frozen at approval time, points credited/debited through the batch-1 ledger, and appeal-upheld reversals written as a **new opposite entry** (never by editing the original — the ledger is append-only). Reads activity-domain data **only through narrow services** — `ActivityRankingQueryService` for metrics, `ActivityCertificateQueryService` for certificate subjects, `ViolationReviewService#findApproved` for the on-site violation a penalty is based on; never through activity's mappers.
- `代码/hengde-volunteer-auth/` also owns **`volunteer_sanction`** (V32): the account-capability restrictions a penalty imposes — restrict-from-activities / restrict-from-community / deny-all-use, each with an optional expiry. It lives here, **not in honor**, because the enforcement gates hang off business entry points in `activity` and `publicity`; putting the table in honor would force those modules to depend on honor and close a cycle. Expiry is decided **by comparing timestamps at query time, never by a cron flipping a status flag** — a missed job would silently keep a lapsed penalty in force. The gate needs **two** things and they are easy to confuse. (1) *Serialization*: the writer (`SanctionService.impose`) and the gates (`SanctionQueryService`) **both lock the `volunteer` row** — X and S respectively. They run in separate transactions and share no Redisson lock (`lock:enroll:volunteer:` only serializes enrollments against each other), and the sanction row the gate needs to see does not exist yet when the gate reads, so a stable parent row is the only thing both sides can serialize on. (2) *Visibility*: the gate's sanction query must itself be a **current read** (`VolunteerSanctionMapper.selectBlockingForShare`). The parent-row lock alone buys "I wait for you to commit", not "I can see it once you have" — under REPEATABLE READ the read view is fixed at the transaction's first consistent read, and `doProxyEnroll` must run its same-group check (a plain read) before the gate for privacy reasons. Shipping only (1) leaves the gate silently missing freshly committed penalties; `EnrollmentGateIsolationTest` pins this — including the *reverse* direction (that `impose` itself takes the parent X lock) and one case that drives the real `proxyEnroll` entry point rather than comparing two query methods. Proxy enrollment uses the batch entry point `assertNoneRestricted`, which takes **all** parent-row locks in ascending id order before reading any sanctions — a current read takes gap locks too, so a per-target "lock parent, then read" loop can deadlock against a concurrent `impose`. The gate throws if called outside a transaction: `FOR SHARE` under autocommit releases at statement end, leaving the gate looking correct while enforcing nothing.
- `代码/hengde-volunteer-api/`: single deployable Spring Boot app, global config, filters, exception handling, upload controller, search controller, and aggregate wiring.

Frontend/admin console:

- `volunteer-platform-back/`: no-build admin console. Entry is `index.html`; scripts are plain `.js` files under `assets/` using `React.createElement`, loaded by ordinary `<script>` tags.
- Runtime Babel and CDN React have been removed. React production files live under `assets/vendor/`.
- `assets/api.js` owns request/auth/download/upload behavior. `assets/authz.js` owns `hasPerm`. `assets/preview-identities.js` only serves the Tweaks preview identities.
- `assets/shell.js` owns navigation metadata and `TODO_SOURCES`; overview cards and sidebar badges must share this source for pending-count endpoints.

Planned but not yet built domains include `donate`, `social`, and `enterprise`. `honor` now carries rankings, medals, role models, the electronic-certificate core, and the rewards/penalties centre. ⚠️ Because `social` does not exist, the sanction scope "restrict from posting in the community" is **recorded but inert** — wire its gate up when `social` lands. Still unbuilt inside batch 4: the **paper-certificate application path** (5 tables, 18 endpoints, WeChat Pay) and **i志愿 certificates** (batch 4B) — both deliberately frozen, see `文档/v2/V2规划.md`. Do not create a module unless the task explicitly requires that domain.

Keep Java packages under `com.hengde`. Domain modules own their `controller/service/dao/entity/dto/vo` packages. Cross-domain calls should go through public service APIs, not direct mapper access.

## Build And Test

Run Maven from `代码/hengde-volunteer-parent`. The parent POM now carries a `<modules>` reactor aggregating **all 9 modules** in dependency order (common → auth → organization → publicity → activity → user → data → honor → api), so a plain full build works; after a full build, verify the Reactor Summary lists parent + all 9 modules (a missing entry means the api jar silently packages a stale module from the local repo — this bit us on 2026-07-02 when user/data were absent from the list). Update this count whenever a module is added, or the check silently stops catching the very thing it exists for.

**A green build does not mean the endpoint exists.** On 2026-08-04 a javadoc edit dropped a closing `*/`, so `AdminRewardPunishController.list` and its `@GetMapping` were swallowed by the comment block that followed. `GET /a/honor/reward-punishes` did not exist at runtime, yet `mvn` reported BUILD SUCCESS (comments are legal Java) and `python tools/verify_url_contract.py --root .` reported `✓ 契约一致` (it regex-scanned raw source). The script now strips comments before parsing and has self-tests for that exact shape, but it still only proves *the annotations are written correctly* — not that Spring registered the handler. When you add or move an endpoint, confirm with `javap -p <module>/target/classes/.../XxxController.class` that the method is actually in the compiled class.

```powershell
cd 代码\hengde-volunteer-parent

# Full build (preferred).
.\mvnw.cmd clean install -DskipTests

# Or incrementally: build/install a single module with -f when iterating
# (re-install an upstream module before building downstream/api).
.\mvnw.cmd clean install -DskipTests -f ..\hengde-volunteer-activity\pom.xml
.\mvnw.cmd clean install -DskipTests -f ..\hengde-volunteer-api\pom.xml

# Run tests for changed modules.
.\mvnw.cmd test -f ..\hengde-volunteer-activity\pom.xml
.\mvnw.cmd test -f ..\hengde-volunteer-organization\pom.xml
.\mvnw.cmd test -f ..\hengde-volunteer-user\pom.xml
.\mvnw.cmd test -f ..\hengde-volunteer-data\pom.xml
.\mvnw.cmd test -f ..\hengde-volunteer-api\pom.xml

# Run a single test class or method.
.\mvnw.cmd test -f ..\hengde-volunteer-activity\pom.xml -Dtest=ActivityAttendanceServiceTest
```

Docker Desktop must be running for MySQL/Redis Testcontainers tests.

Frontend checks:

```powershell
cd volunteer-platform-back
node --check .\assets\app.js
python -m http.server 5500
```

For `.js` files that are plain scripts, `node --check` is valid syntax verification. Browser end-to-end checks still require the API, MySQL, and Redis.

## Coding Rules

- Use Java 17 and 4-space indentation.
- Class names use `PascalCase`; methods and fields use `camelCase`; constants use `UPPER_SNAKE_CASE`.
- Use setter injection consistently. Hand-write setters with `@Autowired`; Lombok-generated setters do not carry `@Autowired`.
- Use Lombok only where it removes boilerplate without hiding behavior.
- Controller paths must not include `/api`; `server.servlet.context-path=/api` supplies it.
- Use URL role prefixes: `/v` for volunteers, `/a` for admin, `/e` reserved for enterprise.
- Keep Jackson behavior in `hengde-volunteer-api` `JacksonConfig`, not `spring.jackson.*` YAML. `LocalDateTime` format is `yyyy-MM-dd HH:mm:ss`; Long serializes as string for JS safety.
- Use Spring Boot's default HikariCP datasource; do not add Druid.
- Flyway scripts are centralized in `hengde-volunteer-common/src/main/resources/db/migration/` with one global version sequence. Current sequence is **V38** (V30 = per-slot attendance, V31 = electronic certificates, V32 = rewards/penalties + sanctions, V33 = a cap on `sanction_days`, V34 = binary collation on `point_record.request_id`, V35 = rejected tickets release the violation key, V36 = the `honor:sanction-all` permission, V37 = the `volunteer_notification` in-app notice table, V38 = appeal-upheld tickets also release the violation key); the next migration must therefore start at V39. **Put at most one statement that can fail in a migration file.** MySQL commits each DDL implicitly, so "statement 1 succeeded, statement 2 failed" leaves the database half-migrated *and* unable to re-run — statement 1 fails the second time with a duplicate name. V33 and V34 were one file until this actually bit: `utf8mb4_0900_bin` only exists from MySQL 8.0.17, so on 8.0.16 the CHECK was added and committed, the collation change failed with `ERROR 1273`, and the post-upgrade re-run died on `ERROR 3822`. **The project floor is therefore MySQL 8.0.17**, checked by `DatabaseVersionGuard` *before* Flyway migrates — a check that runs after migration can only refuse to serve, it cannot protect the database. Never edit a migration that may already have run anywhere — Flyway `repair` only realigns the schema-history checksum, it does **not** re-execute a version recorded as successful, so the change would silently never reach that database. Add a new version instead (V25 → V26 was split for exactly this reason). Check the directory before picking a version — this line is a pointer, the directory is the source of truth.
- New status/role constants should live in domain constant holders. Existing service-private aliases may reference the shared constants to avoid broad call-site churn.
- Use `DistributedLockSupport` for Redisson lock helpers. Multi-lock flows must deduplicate IDs, acquire in ascending order, and release in reverse order.

## Frontend Rules

- Keep `volunteer-platform-back` no-build. Do not reintroduce JSX syntax, runtime Babel, npm bundling, or CDN React unless the user explicitly changes the deployment strategy.
- Use plain `.js` script files and `React.createElement`.
- Keep permissions driven by `hasPerm(identity, code)` from `assets/authz.js`.
- Do not revive `assets/data.js` or `window.HD` mock data. Tweaks preview identities belong in `assets/preview-identities.js`.
- When adding sidebar badges or overview pending cards, add the endpoint once in `TODO_SOURCES` and reuse it.
- Count requests must be gated by the endpoint's real permission to avoid 403s and stale data leaks.
- On identity changes, clear derived permission-sensitive state and guard late async responses with cancellation.
- Uploads go through `API.upload(file, dir)` with backend `dir` gates. Image fields should crop to required ratio before upload when a preset exists.
- `window.__API_BASE__` and AMap placeholders are configured in `index.html`; do not hard-code production hosts in page scripts.

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
- Admin group member reads must use admin-safe service methods, not volunteer same-group checks.
- Deleting a squad must reject when active volunteers still reference it.

Volunteer-side RBAC rules are fixed:

- V18 adds `volunteer_permission` and `permission.volunteer_grantable`.
- Only activity-domain grantable points may be assigned to volunteers; `activity:menu` is not volunteer-grantable.
- Volunteer permission assignment is super-admin only.
- Non-empty assignment requires the target volunteer to be active and `manager_flag=1`.
- Empty assignment is allowed as a cleanup path even after the volunteer is downgraded.
- Permission reads use `VolunteerQueryService.isActiveManager`; suspended, deleted, or downgraded volunteers get no codes.
- `@SaCheckPermission` on `/v` endpoints must not use `type="admin"`; it should use the default login realm.
- `POST /v/activity/activities` consumes `activity:publish` for manager-team volunteers.

Activity rules already decided in code/docs should keep their current wording and error semantics:

- `POST /v/activity/activities` creates pending-review activities (`status=4`), not immediately published.
- Admin direct publish remains immediately online and does not enter the review queue.
- Review-domain statuses are `4` pending review and `5` rejected. Normal admin menu/detail/write flows must exclude or reject these unless using review endpoints.
- Under-review/rejected activities must not be modified, deleted, copied, assigned leaders, backfilled, or exposed through volunteer leader read paths.
- `review-detail` is gated by `activity:publish-audit` and includes review trail fields.
- Copying an activity must clear review trail, run status, actual times, summary fields, and historical marker.
- Historical activities are not visible to volunteers and must not grant points.
- Activity albums are postponed until `social` exists.
- Attendance/points changes require the V14 two-step approval flow.
- Activity backfill approval is terminal: it writes confirmed attendance directly; historical backfill records time only and finalizes points as issued with 0 points.
- Leader emergency reporting is a configured phone number (`hengde.activity.emergency-phone`) returned as `emergencyPhone`; no table is used.
- Manual violation records use `description` as required free text with max length 512.
- Manual `violationType` is optional and limited to 0-4. Type 5 means absence and is system-generated only by marking absent.
- `recordViolation` must keep service-level guards for required text, max length, and type range so internal callers cannot bypass DTO validation.
- Violation record views must not resolve `recorded_by` blindly through volunteer IDs because it can also contain `admin_user.id`. Only resolve `recordedByName` when the recorder is a volunteer leader of that activity; otherwise leave it null.

User/data contracts:

- Admin volunteer list/detail/export only include registered volunteers (`register_time` not null). Cancelled volunteers (`status=2`) are still visible as registered records.
- Volunteer status is `0` normal, `1` disabled, `2` cancelled. `setStatus` only accepts `0` or `1`; cancelled is terminal in the admin frontend.
- **Disabled is not "cannot log in".** Since the association's 2026-08-11 ruling ("give the disabled account a small opening — it may only view rewards/penalties and file an appeal"), `ensureLoginable` issues a token for `status=1` and rejects only `status=2`; `SaTokenConfigure.checkVolunteerEnabled` then allows a disabled account exactly the paths in `BannedAccountGate.EXEMPT_PATHS` (logout, reward-punish list, appeal, sanction view, notifications) and returns 403 for everything else. **A successful login therefore no longer implies a usable account.** Two rules follow. First, the disabled branch must never call `logout()` — that would void the token the appeal path needs, closing the opening while the allow-list still looks correct; only the cancelled branch logs out. Second, `BannedAccountGate.EXEMPT_PATHS` must stay a subset of `DenyAllUseGate.EXEMPT_PATHS`: someone can be both disabled and carrying a `SanctionScope.ALL` penalty, and the two gates sit on the same request chain, so a path missing from either one silently costs that person their right to appeal. The two lists are deliberately not merged — `DenyAllUseGate` serves accounts that are *normal* but heavily sanctioned (hence it opens all of `/v/auth/**`), while opening that whole segment to a disabled account would let it complete real-name registration.
- Sensitive volunteer PII decryption stays in `auth`. Other modules consume narrow auth service outputs.
- Admin volunteer update is super-admin only. Empty phone fields have explicit clearing semantics.
- Dashboard manager count means active registered managers only: `manager_flag=1`, `register_time is not null`, `status=0`.
- Dashboard activity/participation/service-time counts must exclude draft/cancelled/review-domain activities; published and ended activities count.

## Testing Guidelines

Tests use JUnit 5 and Spring Boot test. Put tests under `src/test/java` with names ending in `Test` or `Tests`.

- Prefer `@SpringBootTest` for service behavior, context wiring, Flyway, MyBatis, Redis, Redisson, and Testcontainers.
- Domain modules without a `main` app need a test-only `@SpringBootApplication` in `src/test/java/com/hengde/<domain>/`.
- MySQL/Redis integration tests should reuse common test-jar Testcontainers config and import only what the module needs.
- Do not use H2; migrations and MyBatis behavior target MySQL.
- Modules using Redisson must import Redis Testcontainers config.
- Domain module tests do not have the API module pagination interceptor; assert returned records, not `selectPage` totals.
- For wrapper/CAS updates, explicitly set `update_time` when MyBatis-Plus auto-fill will not run.
- Run the relevant module test command before handing off code changes. For docs-only edits, state that tests were not run.

## Security And Data Ownership

- Do not commit production secrets. SMS, OSS, WeChat, Redis, database, crypto, AMap, and nginx/server secrets must come from environment variables or deployment config.
- Production config guards should fail fast when required production settings are missing or still using development placeholders.
- Sensitive volunteer data decryption stays in `auth`. Other modules should consume `auth` service outputs and should not manipulate encrypted columns directly.
- Public display use cases should request narrow outputs, such as name-only views, instead of loading decrypted phone numbers into memory.
- Cross-domain ID columns that can hold IDs from multiple tables must not be name-resolved without a subject-type discriminator or a safe local proof of subject type.
- OpenAPI auth uses `apiKey` in the `Authorization` header to match Sa-Token direct token behavior.
- Admin upload is double-gated by `dir`: permission plus file type. Unknown or missing `dir` must be rejected.

## Git And Handoff

- The worktree may contain user or other-agent changes. Do not revert or rewrite unrelated files.
- Use `rg`/`rg --files` first for searches.
- Use `apply_patch` for manual file edits.
- Do not use destructive commands such as `git reset --hard` or `git checkout --` unless explicitly requested.
- Use concise conventional commits when commits are requested, such as `feat: add activity backfill` or `fix: guard attendance audit operator`.
- Handoff notes should include changed modules/files, test results, requirement/API document impact, migrations, permission points, and configuration changes.
