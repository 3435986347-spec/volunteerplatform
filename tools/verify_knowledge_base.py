#!/usr/bin/env python3
"""知识库校验：知识库里点名的类、方法、路径、配置键、权限点、迁移号，在代码里是不是真的存在。

知识库的页是手写的，代码一改名、一删类，页里那句话就悄悄过期——而 agent 会照着过期的名字去找、去调。
本脚本把页里**能机械核对的引用**逐个对回代码，对不上的列出来（`文件:行号  引用  原因`）。

核对范围：`知识库/**/*.md`、根 `CLAUDE.md` / `AGENTS.md`。只看反引号里的内容（行内代码），正文里的中文叙述不核对。

  1. 类名        `PointService`、`MallImmediateOrderTest` —— 代码里（main 或 test）要有这个类型声明；
                 第三方类（代码里 import 过的）与前端组件名（控制台 / 小程序的 js 里出现过的）放行
  2. 成员        `PointService.record`、`ActivityStatus#isUnderReview` —— 声明该类型的文件里要出现这个名字
  3. 路径        `代码/...`、`文档/...`、`知识库/...`、`tools/...`、`部署/...`、`../volunteerplatform-back/...` —— 要存在
  4. 配置键      `hengde.donate.mall.order-review` —— 代码或 yml 里要出现
  5. 权限点      `donate:mall-order` 这类（前缀是 14 个模块的权限域）—— 迁移里要登记过
  6. 方法名 / 表列名  单独写的 `detailForVolunteer`、`point_record` —— 代码（java / sql / yml / 前端 js）里要出现过这个词
  7. 迁移号      正文里的 `V123` —— 不能大于「当前最大号 + 1」（写了还不存在的号，多半是笔误或计划被推翻）
  8. 数字        根文件里「迁移当前到 **Vn**」「权限点现行 **n** 个」「n 个 Maven 模块」—— 要与代码一致
  9. 协会决策号  `XH21` 这类引用 —— `知识库/决策/协会决策.md` 里要有这一条
 10. 页内链接    `[文字](../模块/donate.md)` 这类相对链接 —— 目标文件要存在（拆页、改名之后最容易断）

用法：

    python3 tools/verify_knowledge_base.py            # 在仓库根运行
    python3 tools/verify_knowledge_base.py --verbose  # 连同已放行的第三方名一起打印
    python3 tools/verify_knowledge_base.py --selftest # 只跑内置用例

**能力边界**：只能证明「点名的东西还在」，证明不了「这句话说的行为还对」——后者靠每批收尾时更新现行要点。
成员核对是「名字在文件里出现过」，不做语法分析；同名重载、继承来的方法都算通过。

退出码：0 = 全部对得上；1 = 有引用对不上；2 = 用法 / 环境错误。
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

# 权限点的域前缀（迁移里 permission.code 的冒号前半段）
PERM_DOMAINS = ("activity", "honor", "org", "donate", "social", "user", "enterprise",
                "publicity", "data", "system", "trade")

# 代码里没 import、但确实是第三方 / JDK / 数据库的名字（出现在踩坑记录里）
EXTERNAL_ALLOWLIST = {
    "NoSuchMethodError", "ZipArchiveOutputStream", "ZipArchiveEntry", "ClassNotFoundException",
    "RuntimeException", "IllegalStateException", "IllegalArgumentException", "NullPointerException",
    "OutOfMemoryError", "UnicodeEncodeError", "BigDecimal", "LocalDateTime", "LocalDate",
    "MockMvc", "TestRestTemplate", "SpringBootTest", "SpringBootApplication", "ServiceConnection",
    "TransactionalEventListener", "EventListener", "Transactional", "Scheduled", "Autowired",
    "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping",
    "SaCheckPermission", "SaCheckLogin", "SaMode", "SaManager", "StpUtil", "StpLogic", "StpInterface",
    "SaTokenDao", "RedissonClient", "RLock", "MybatisPlusConfig", "PaginationInnerInterceptor",
    "MyBatis", "MyBatisPlus", "HikariCP", "SpringBoot", "JsonFormat", "JsonSerialize", "ToStringSerializer",
    "ObjectMapper", "HttpServletResponse", "HttpServletRequest", "ResponseEntity", "MultipartFile",
    "DataIntegrityViolationException", "DuplicateKeyException", "DeadlockLoserDataAccessException",
    "CannotAcquireLockException", "PessimisticLockingFailureException", "TransactionSynchronization",
    "TransactionTemplate", "PlatformTransactionManager", "WebSocketHandler", "TextWebSocketHandler",
    "HandshakeInterceptor", "EasyExcel", "ExcelProperty", "PdfReader", "PdfStamper", "ZXing",
    "JavaTimeModule", "SimpleModule", "WebMvcConfigurer", "OncePerRequestFilter", "ApplicationEvent",
    "TestPropertySource", "DynamicPropertySource", "DirtiesContext", "BeforeEach", "AfterEach",
    "RepeatedTest", "ParameterizedTest", "ExtendWith", "MockBean", "MockitoBean", "SpyBean",
    "CountDownLatch", "CyclicBarrier", "ExecutorService", "CompletableFuture", "AtomicInteger",
    "ConcurrentHashMap", "ThreadLocal", "ReentrantLock", "SecureRandom", "MessageDigest",
    "InnoDB", "MySQL", "GitHub", "WeChat", "JavaScript", "TypeScript", "PowerShell", "OpenAPI",
    "ConfigurationProperties", "EnableScheduling", "ConditionalOnProperty", "ConditionalOnMissingBean",
    "SpringApplication", "CommandLineRunner", "ApplicationRunner", "FlywayMigrationStrategy",
    "Flyway", "Testcontainers", "MySQLContainer", "GenericContainer", "DockerClientFactory",
    "IdCardVerification", "XxlJob", "BoundedInputStream", "EnvironmentFile", "MinAPIVersion",
}

# 第三方工具的配置键 / 参数名（蛇形或小驼峰，代码里本来就没有）
EXTERNAL_WORDS = {"project_doc_max_bytes", "project_doc_fallback_filenames"}

# 页里**明说是旧名 / 反例**的名字（「第一版叫 `DataPermission`」）——它们本来就不该在代码里
HISTORICAL_NAMES = {"DataPermission"}

# 按设计**不入权限点表**的码（V2 迁移抬头列明的两个高危点，写死仅超管，见 url 契约脚本的同名豁免）
PERM_EXEMPT = {"user:edit", "org:perm-assign"}

PATH_ROOTS = ("代码/", "文档/", "知识库/", "tools/", "部署/", "docs/",
              "../volunteerplatform-back/", "../volunteerplatform-miniprogram/")

_SPAN = re.compile(r"`([^`\n]+)`")
_TYPE_DECL = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+([A-Z][A-Za-z0-9_]*)")
_IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?[\w.]+\.([A-Z][A-Za-z0-9_]*)(?:\.[\w*]+)?\s*;", re.M)
# 多驼峰的类名（`PointService`），后面可跟 `.member` / `#member`
_TYPE_REF = re.compile(r"(?<![\w.$/*-])([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+)(?:[.#]([A-Za-z_][A-Za-z0-9_]*))?")
_CONFIG_KEY = re.compile(r"(?<![\w.])(hengde\.[a-z0-9][a-z0-9.-]*[a-z0-9])")
_PERM = re.compile(r"(?<![\w:])((?:" + "|".join(PERM_DOMAINS) + r"):[a-z0-9][a-z0-9-]*)(?![\w:/])")
_MIGRATION_REF = re.compile(r"(?<![A-Za-z0-9])V(\d{1,3})(?![0-9.])")
# 单独写的方法名（小驼峰、至少两段）与表 / 列名（蛇形、至少两段），可带 `()` 或 `(...)`
_CAMEL = re.compile(r"^([a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)+)(?:\(.*\))?$")
_SNAKE = re.compile(r"^([a-z][a-z0-9]*(?:_[a-z0-9]+)+)$")
_WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_MD_LINK = re.compile(r"\]\(([^)\s]+)\)")
_DECISION_REF = re.compile(r"(?<![A-Za-z0-9])XH(\d{2})(?!\d)")
_DECISION_HEAD = re.compile(r"^### XH(\d{2}) · ", re.M)
_ICLOUD_DUP = re.compile(r" \d+(\.\w+)?$")


@dataclass
class Problem:
    where: str
    ref: str
    reason: str


# ---------------------------------------------------------------- 代码侧索引

class CodeIndex:
    def __init__(self) -> None:
        self.types: dict[str, list[Path]] = {}   # 类型名 → 声明它的文件
        self.texts: dict[Path, str] = {}
        self.external: set[str] = set(EXTERNAL_ALLOWLIST)
        self.config_text = ""
        self.words: set[str] = set()   # java / sql / yml / 前端 js 里出现过的全部标识符
        self.perms: set[str] = set()
        self.max_migration = 0
        self.module_count = 0
        self.decisions: set[str] = set()   # 协会决策记录里有的编号

    def declares(self, name: str) -> bool:
        return name in self.types

    def has_member(self, type_name: str, member: str) -> bool:
        pat = re.compile(r"\b" + re.escape(member) + r"\b")
        return any(pat.search(self.texts[p]) for p in self.types.get(type_name, []))


def _skip(p: Path) -> bool:
    return "target" in p.parts or "node_modules" in p.parts or bool(_ICLOUD_DUP.search(p.stem))


def build_index(root: Path) -> CodeIndex:
    idx = CodeIndex()
    code = root / "代码"
    for f in code.rglob("*.java"):
        if _skip(f):
            continue
        text = f.read_text(encoding="utf-8", errors="ignore")
        idx.texts[f] = text
        for name in _TYPE_DECL.findall(text):
            idx.types.setdefault(name, []).append(f)
        idx.external.update(_IMPORT.findall(text))
    cfg_parts = []
    for pattern in ("*.yml", "*.yaml", "*.properties", "*.java"):
        for f in code.rglob(pattern):
            if not _skip(f):
                cfg_parts.append(f.read_text(encoding="utf-8", errors="ignore"))
    idx.config_text = "\n".join(cfg_parts)
    for text in cfg_parts:
        idx.words.update(_WORD.findall(text))
    for f in code.rglob("*.sql"):
        if not _skip(f):
            idx.words.update(_WORD.findall(f.read_text(encoding="utf-8", errors="ignore")))
    # 前端（控制台 / 小程序）里出现过的大写开头标识符：组件名、页面对象名
    for sibling in ("volunteerplatform-back", "volunteerplatform-miniprogram"):
        d = root.parent / sibling
        if d.is_dir():
            for f in d.rglob("*.js"):
                if _skip(f) or "vendor" in f.parts:
                    continue
                js = f.read_text(encoding="utf-8", errors="ignore")
                idx.external.update(re.findall(r"\b[A-Z][A-Za-z0-9]+\b", js))
                idx.words.update(_WORD.findall(js))
    mig = code / "hengde-volunteer-common/src/main/resources/db/migration"
    for f in mig.glob("V*__*.sql"):
        if _skip(f):
            continue
        idx.max_migration = max(idx.max_migration, int(re.match(r"V(\d+)", f.name).group(1)))
        idx.words.add(f.stem.split("__", 1)[-1])
    idx.perms = set(load_permissions(mig))
    record = root / "知识库" / "决策" / "协会决策.md"
    if record.exists():
        idx.decisions = set(_DECISION_HEAD.findall(record.read_text(encoding="utf-8")))
    pom = (code / "hengde-volunteer-parent/pom.xml").read_text(encoding="utf-8")
    modules = re.search(r"<modules>(.*?)</modules>", pom, re.S)
    idx.module_count = len(re.findall(r"<module>", modules.group(1))) if modules else 0
    return idx


def load_permissions(migration_dir: Path) -> dict:
    """→ {code: (name, module, type, 迁移号)}，按迁移号顺序回放 INSERT 与改名的 UPDATE。"""
    perms: dict = {}
    files = [f for f in migration_dir.glob("V*__*.sql") if not _skip(f)]
    for f in sorted(files, key=lambda x: int(re.match(r"V(\d+)", x.name).group(1))):
        text = f.read_text(encoding="utf-8", errors="ignore")
        text = re.sub(r"--[^\n]*", "", text)
        version = f.name.split("__")[0]
        for m in re.finditer(r"INSERT\s+INTO\s+permission\s*\([^)]*\)\s*VALUES(.*?);", text, re.S | re.I):
            for t in re.finditer(r"\(\s*'([a-z0-9:_-]+)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*(\d+)", m.group(1)):
                perms[t.group(1)] = (t.group(2), t.group(3), int(t.group(4)), version)
        for m in re.finditer(r"UPDATE\s+permission\s+SET\s+name\s*=\s*'([^']*)'.*?WHERE\s+code\s*=\s*'([^']+)'",
                             text, re.S | re.I):
            if m.group(2) in perms:
                name, module, typ, v = perms[m.group(2)]
                perms[m.group(2)] = (m.group(1), module, typ, v)
        for m in re.finditer(r"DELETE\s+FROM\s+permission\s+WHERE\s+code\s*=\s*'([^']+)'", text, re.I):
            perms.pop(m.group(1), None)
    return perms


# ---------------------------------------------------------------- 文档侧

def doc_files(root: Path) -> list[Path]:
    files = sorted((root / "知识库").rglob("*.md"))
    for name in ("CLAUDE.md", "AGENTS.md"):
        if (root / name).exists():
            files.append(root / name)
    return [f for f in files if not _skip(f)]


def check_span(span: str, idx: CodeIndex, root: Path, where: str) -> list[Problem]:
    out: list[Problem] = []
    s = span.strip()
    # 3. 路径
    if s.startswith(PATH_ROOTS):
        path = re.split(r"[\s（(]", s, maxsplit=1)[0]
        path = re.sub(r":\d+(-\d+)?$", "", path).rstrip("/，。；")
        if not any(ch in path for ch in "{}<>*…") and "..." not in path.split("/", 1)[-1]:
            target = (root / path).resolve() if not path.startswith("../") else (root / path).resolve()
            if not target.exists():
                out.append(Problem(where, s, "路径不存在"))
        return out
    # 4. 配置键
    for key in _CONFIG_KEY.findall(s):
        # @ConfigurationProperties 走宽松绑定：yml 写 freeze-delay-days，代码里是字段 freezeDelayDays
        last = key.rsplit(".", 1)[-1]
        camel = re.sub(r"-([a-z0-9])", lambda m: m.group(1).upper(), last)
        if key not in idx.config_text and camel not in idx.words:
            out.append(Problem(where, key, "配置键在代码 / yml 里找不到"))
    # 5. 权限点
    for code in _PERM.findall(s):
        if code not in idx.perms and code not in PERM_EXEMPT and not code.endswith("-"):
            out.append(Problem(where, code, "权限点不在迁移登记的表里"))
    # 6. 单独写的方法名 / 表列名
    m = _CAMEL.match(s) or _SNAKE.match(s)
    if m:
        if m.group(1) not in idx.words and m.group(1) not in EXTERNAL_WORDS:
            out.append(Problem(where, m.group(1), "代码里没出现过这个名字"))
        return out
    # 1 / 2. 类型与成员
    for m in _TYPE_REF.finditer(s):
        name, member = m.group(1), m.group(2)
        if idx.declares(name):
            if member and not idx.has_member(name, member):
                out.append(Problem(where, f"{name}.{member}", f"{name} 里找不到 {member}"))
        elif name not in idx.external and name not in HISTORICAL_NAMES:
            out.append(Problem(where, name, "代码里没有这个类型"))
    return out


def check_numbers(text: str, idx: CodeIndex, where_prefix: str) -> list[Problem]:
    out: list[Problem] = []
    for lineno, line in enumerate(text.splitlines(), 1):
        m = re.search(r"当前到\s*\*\*V(\d+)\*\*", line)
        if m and int(m.group(1)) != idx.max_migration:
            out.append(Problem(f"{where_prefix}:{lineno}", f"V{m.group(1)}", f"迁移实际到 V{idx.max_migration}"))
        m = re.search(r"权限点现行\s*\*\*(\d+)\*\*", line)
        if m and int(m.group(1)) != len(idx.perms):
            out.append(Problem(f"{where_prefix}:{lineno}", m.group(1), f"权限点实际 {len(idx.perms)} 个"))
        m = re.search(r"(\d+)\s*个\s*Maven\s*模块", line)
        if m and int(m.group(1)) != idx.module_count:
            out.append(Problem(f"{where_prefix}:{lineno}", m.group(1), f"父 POM 实际聚合 {idx.module_count} 个模块"))
    return out


def check_file(f: Path, idx: CodeIndex, root: Path) -> list[Problem]:
    rel = f.relative_to(root).as_posix()
    text = f.read_text(encoding="utf-8", errors="ignore")
    out: list[Problem] = []
    in_fence = False
    for lineno, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        where = f"{rel}:{lineno}"
        for span in _SPAN.findall(line):
            out.extend(check_span(span, idx, root, where))
        for target in _MD_LINK.findall(_SPAN.sub("", line)):   # 反引号里的是写法示例，不是链接
            if re.match(r"^[a-z]+:|^#", target):
                continue
            path = target.split("#", 1)[0]
            if path and not (f.parent / path).resolve().exists():
                out.append(Problem(where, target, "链接目标不存在"))
        for d in _DECISION_REF.findall(line):
            if d not in idx.decisions:
                out.append(Problem(where, f"XH{d}", "协会决策记录里没有这一条"))
        for v in _MIGRATION_REF.findall(line):
            if int(v) > idx.max_migration + 1:      # +1：「下一个从 V101 起」是正当写法
                out.append(Problem(where, f"V{v}", f"大于当前最大迁移号 V{idx.max_migration}"))
    out.extend(check_numbers(text, idx, rel))
    return out


# ---------------------------------------------------------------- 自测

def selftest() -> int:
    idx = CodeIndex()
    p = Path("/x/PointService.java")
    idx.types = {"PointService": [p]}
    idx.texts = {p: "public class PointService { public void record() {} }"}
    idx.config_text = "hengde.donate.mall.order-review: false"
    idx.perms = {"donate:mall-order"}
    idx.words = {"detailForVolunteer", "point_record", "freezeDelayDays"}
    idx.max_migration = 100
    cases = [
        ("PointService.record", 0), ("PointService#record", 0), ("PointService.balanceOf", 1),
        ("GoneService", 1), ("HttpServletResponse", 0), ("hengde.donate.mall.order-review", 0),
        ("hengde.donate.mall.gone", 1), ("donate:mall-order", 0), ("donate:gone", 1),
        ("lock:enroll:volunteer:", 0), ("NORMAL", 0), ("status=1", 0), ("data:image/png", 0),
        ("selectBy*ForShare", 0), ("user:edit", 0), ("hengde.honor.ranking.freeze-delay-days", 0),
        ("isOwnUpload(url, PointService.GONE)", 1), ("detailForVolunteer", 0), ("detailForVolunteer()", 0),
        ("goneMethod(id)", 1), ("point_record", 0), ("gone_table", 1),
    ]
    failed = 0
    for span, want in cases:
        got = len(check_span(span, idx, Path("/nonexistent"), "t"))
        if got != want:
            failed += 1
            print(f"✗ {span!r}: 期望 {want} 条，得到 {got} 条")
    probs = check_numbers("迁移当前到 **V99**\n权限点现行 **1** 个", idx, "t")
    if len(probs) != 1:
        failed += 1
        print(f"✗ 数字核对：期望 1 条（迁移号），得到 {len(probs)} 条")
    print("✓ 自测通过" if not failed else f"✗ 自测失败 {failed} 条")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".", help="文档仓库根目录（含 代码/ 与 知识库/）")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    root = Path(args.root).resolve()
    if not (root / "代码").is_dir() or not (root / "知识库").is_dir():
        print(f"找不到 {root}/代码 或 {root}/知识库——请在文档仓库根目录运行，或用 --root 指定", file=sys.stderr)
        return 2
    idx = build_index(root)
    problems: list[Problem] = []
    files = doc_files(root)
    for f in files:
        problems.extend(check_file(f, idx, root))
    print(f"核对了 {len(files)} 页；代码里 {len(idx.types)} 个类型、{len(idx.perms)} 个权限点、"
          f"迁移到 V{idx.max_migration}、父 POM 聚合 {idx.module_count} 个模块")
    if args.verbose:
        print(f"放行的第三方 / 前端名 {len(idx.external)} 个")
    if not problems:
        print("✓ 知识库里点名的东西都还在")
        return 0
    for p in problems:
        print(f"{p.where}  `{p.ref}`  {p.reason}")
    print(f"✗ {len(problems)} 处对不上")
    return 1


if __name__ == "__main__":
    sys.exit(main())
