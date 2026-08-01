#!/usr/bin/env python3
"""接口契约校验：让 `文档/v2/url文档v2.md` 与 `代码/` 里的 Controller 保持一致。

由两份一次性 scratch 脚本合并而来，作为**仓库内的常驻工具**——脚本留在临时目录里，
下一个人无从复现，评审结论也就退化成一句「我当时跑过」。

检查四类问题，任一出现即以非 0 退出：

  1. 端点差集      —— 代码有而文档无（漏记）、文档有而代码无（幻影接口）
  2. 权限不一致    —— 同一端点的权限点、或 AND/OR 组合方式，两侧对不上
  3. 重复接口      —— 同一「方法 + 路径」在文档里出现多次
  4. 未标注的文档独有项 —— 文档写了但代码没有，且未显式标注「未实现 / ⬜ / 占位」

用法：

    python tools/verify_url_contract.py            # 在仓库根运行
    python tools/verify_url_contract.py --verbose  # 连同逐条明细一起打印
    python tools/verify_url_contract.py --selftest # 只跑内置回归用例，不读仓库

`--selftest` 覆盖解析器与比对逻辑最容易写错的地方（都是真实踩过的形态）：
无参数的裸 `@GetMapping`、`PermissionCode.X` 常量解引用、`SaMode.OR` 多权限、
markdown 粗体写的 `**或**`、以及「文档要求权限点但代码没有注解」这一类权限回归。

**能力边界（不要高估本脚本）**：

  · `manual_guard` 只扫 controller 方法文本里的关键词，**守卫写在 service 层就发现不了**；
    它仅作提示，不参与通过/失败判定，因此「未标记」不等于「没有守卫」。
  · 只比对静态注解与文档表格。运行期的动态鉴权（拦截器、`StpUtil` 手工判定、
    数据级越权）一概不在覆盖范围内。
  · 文档只写「需登录」而不细化权限点时无从比对，这类端点的权限正确性靠人工与测试保证。

退出码：0 = 一致；1 = 发现问题；2 = 用法/环境错误。
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# Windows 控制台默认 GBK，编不了 ✓/✗ 与部分中文标点，会让脚本在打印阶段崩掉
# （报的是 UnicodeEncodeError，与契约本身无关，极易误判成「脚本坏了」）。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

# ---------------------------------------------------------------- 数据模型

HTTP_METHODS = ("GET", "POST", "PUT", "DELETE", "PATCH")


@dataclass(frozen=True)
class Endpoint:
    """一个端点 = 方法 + 规范化路径。权限单独比，避免路径相同权限不同时被当成两个端点。"""

    method: str
    path: str

    def __str__(self) -> str:  # pragma: no cover - 仅用于打印
        return f"{self.method} {self.path}"


@dataclass
class Perm:
    """权限要求。codes 为空表示「仅需登录」或「公开」，由 public 区分。"""

    codes: frozenset[str] = frozenset()
    mode: str = "AND"          # AND / OR，与 SaMode 对齐
    public: bool = False        # 无需登录
    specified: bool = True      # 代码侧是否真的写了权限注解（未写 ≠ 公开）
    manual_guard: bool = False  # 注解之外，方法体内还有手工守卫（如超管判定）
    exempt: bool = False        # 文档侧：声明「不入权限点表」，按设计无需注解

    def signature(self) -> tuple:
        """比较用的规范形态。单个权限点时 AND/OR 无差别，统一成 AND 免得误报。"""
        mode = self.mode if len(self.codes) > 1 else "AND"
        return (tuple(sorted(self.codes)), mode, self.public)

    def describe(self) -> str:
        if self.public:
            return "公开"
        if not self.codes:
            return "需登录"
        joiner = " OR " if self.mode == "OR" and len(self.codes) > 1 else " AND "
        text = joiner.join(sorted(self.codes))
        return text + ("（+手工守卫）" if self.manual_guard else "")


@dataclass
class Findings:
    missing_in_doc: list = field(default_factory=list)
    missing_in_code: list = field(default_factory=list)
    perm_mismatch: list = field(default_factory=list)
    duplicate_doc: list = field(default_factory=list)
    # 人工守卫豁免（文档声明「不入权限点表」）。在 allowlist 内的不计为问题，但**必须在输出里露面**。
    exempted: list = field(default_factory=list)
    # 不在 allowlist 内的豁免 —— 计为问题，必须由评审者显式登记后才能通过
    unexpected_exempt: list = field(default_factory=list)

    def total(self) -> int:
        return (len(self.missing_in_doc) + len(self.missing_in_code)
                + len(self.perm_mismatch) + len(self.duplicate_doc)
                + len(self.unexpected_exempt))


# ---------------------------------------------------------------- 路径规范化

_PATH_VAR = re.compile(r"\{[^}/]+\}")


def normalize_path(path: str) -> str:
    """把路径变量名抹平并去掉尾斜杠——`/{id}` 与 `/{applyId}` 是同一个端点。"""
    path = path.strip().strip('`').split("?")[0].strip()
    if not path.startswith("/"):
        path = "/" + path
    path = _PATH_VAR.sub("{}", path)
    if len(path) > 1 and path.endswith("/"):
        path = path[:-1]
    return path


def join_path(base: str, sub: str) -> str:
    base = (base or "").strip().strip('"')
    sub = (sub or "").strip().strip('"')
    if not sub:
        return normalize_path(base or "/")
    if not base:
        return normalize_path(sub)
    return normalize_path(base.rstrip("/") + "/" + sub.lstrip("/"))


# ---------------------------------------------------------------- 解析：代码侧

_CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
# 裸注解（无括号）与带参数两种都要吃到——漏掉裸注解会让端点数悄悄少一截
_METHOD_MAPPING = re.compile(
    r'@(Get|Post|Put|Delete|Patch)Mapping\b(?:\(\s*(?:value\s*=\s*)?("([^"]*)")?[^)]*\))?')
_SA_PERM = re.compile(r'@SaCheckPermission\(([^;]*?)\)\s*(?=@|public|private|protected)', re.S)
_SA_LOGIN = re.compile(r'@SaCheckLogin\b')
_PERM_CONST = re.compile(r'PermissionCode\.([A-Z0-9_]+)')
_PERM_LITERAL = re.compile(r'"([a-z][a-z0-9-]*:[a-z0-9-]+)"')
_CONST_DEF = re.compile(r'String\s+([A-Z0-9_]+)\s*=\s*"([^"]+)"')
# 方法体里的手工超管守卫（注解之外的第二道判定）
_MANUAL_GUARD = re.compile(r'(isSuperAdmin|requireSuperAdmin|SUPER_ADMIN|超级管理员)')


def load_permission_constants(code_root: Path) -> dict:
    """把各模块 PermissionCode 里的常量名映射到真实权限串。"""
    mapping: dict = {}
    for f in code_root.rglob("PermissionCode.java"):
        if "target" in f.parts:
            continue
        for name, value in _CONST_DEF.findall(f.read_text(encoding="utf-8", errors="ignore")):
            mapping[name] = value
    return mapping


def _split_methods(body: str) -> list:
    """按方法切块，每块 = 该方法的**整个注解簇** + 方法体。

    <b>不能从 `@XxxMapping` 往后切</b>：本项目的注解顺序是
    `@Operation` → `@SaCheckPermission` → `@GetMapping` → 方法体，
    从 mapping 往后切会把权限注解留在上一个方法的块里，于是每个端点都被判成「无权限注解」。
    故先从 mapping 向上回溯到注解簇起点，再以「下一个方法的注解簇起点」为终点。
    """
    lines = body.splitlines(keepends=True)
    offsets, pos = [], 0
    for ln in lines:
        offsets.append(pos)
        pos += len(ln)

    def line_index(char_pos: int) -> int:
        lo, hi = 0, len(offsets) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if offsets[mid] <= char_pos:
                lo = mid
            else:
                hi = mid - 1
        return lo

    hits = list(_METHOD_MAPPING.finditer(body))
    starts = []
    for m in hits:
        i = line_index(m.start())
        # 向上吃掉连续的注解行；遇到上一个方法的收尾（} / ;）或空行即停
        while i - 1 >= 0:
            s = lines[i - 1].strip()
            if not s or s.endswith("}") or s.endswith(";") or s.startswith("//"):
                break
            i -= 1
        starts.append(offsets[i])

    blocks = []
    for i, m in enumerate(hits):
        end = starts[i + 1] if i + 1 < len(hits) else len(body)
        blocks.append((m, body[starts[i]:end]))
    return blocks


def _annotation_args(text: str, name: str):
    """取 `@name(...)` 的括号内容（按括号配平，比正则可靠——权限注解里有嵌套的 {} 与 ()）。"""
    idx = text.find("@" + name + "(")
    if idx < 0:
        return None
    start = text.index("(", idx)
    depth = 0
    for j in range(start, len(text)):
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:j]
    return None


def parse_controllers(code_root: Path, consts: dict) -> dict:
    """→ {Endpoint: Perm}"""
    result: dict = {}
    for f in code_root.rglob("*Controller.java"):
        if "target" in f.parts or "/test/" in f.as_posix():
            continue
        body = f.read_text(encoding="utf-8", errors="ignore")
        cm = _CLASS_MAPPING.search(body)
        base = cm.group(1) if cm else ""
        for m, block in _split_methods(body):
            verb = m.group(1).upper()
            sub = m.group(3) or ""
            ep = Endpoint(verb, join_path(base, sub))
            result[ep] = _perm_of(block, consts)
    return result


def _perm_of(block: str, consts: dict) -> Perm:
    manual = bool(_MANUAL_GUARD.search(block))
    args = _annotation_args(block, "SaCheckPermission")
    if args is None:
        # 方法上没有权限注解。**不能据此判定「公开」**——本项目大量端点靠类级
        # @SaCheckLogin 或全局拦截器要求登录，判成公开会凭空造出上百条假不一致。
        # 记为「未细化」，比对时跳过；真正的公开与否由文档侧声明。
        return Perm(specified=False, manual_guard=manual)
    codes = {consts[n] for n in _PERM_CONST.findall(args) if n in consts}
    codes |= set(_PERM_LITERAL.findall(args))
    mode = "OR" if "SaMode.OR" in args else "AND"
    return Perm(frozenset(codes), mode, public=False, specified=True, manual_guard=manual)


# ---------------------------------------------------------------- 解析：文档侧

_DOC_ROW = re.compile(
    r'^\|\s*(' + "|".join(HTTP_METHODS) + r')\s*\|\s*([^|]+?)\s*\|(.*)\|\s*([^|]*?)\s*\|\s*$')
_UNIMPLEMENTED = re.compile(r'未实现|⬜|占位|未建|尚未|not implemented')
# 「该端点按设计就没有权限注解」的**决定性标记**。
#
# 只认 `不入权限点表` 这一句，不认宽泛的 `仅超管`/`不可分配`——早先版本用后者做豁免，
# 结果任何一行只要出现「仅超管」字样就被清空权限码，于是
#     文档：activity:publish；仅超管    代码：无 @SaCheckPermission
# 这种组合会被判成一致，**又一次放过了删注解**（评审实测构造）。
# `不入权限点表` 是对「这个码不是可授权权限点」的明确声明（见 V2 迁移抬头列的两个高危点），
# 语义精确、可 grep、不会被顺手写进普通行。
_EXEMPT_MARKER = re.compile(r'不入权限点表')

# **已登记的人工守卫豁免集合**——出现集合之外的豁免即失败。
#
# 仅靠「打印警告」不够：CI 只看退出码，警告会被无视，于是任何人往文档里写一句
# 「不入权限点表」就能悄悄免掉一个端点的权限校验。改成 allowlist 后，新增豁免
# **必须有人显式改这里**，等于强制一次人工复核。
#
# 登记条件：该端点的守卫必须真实存在于 service 层（已逐个人工核实）。
EXEMPT_ALLOWLIST = {
    # AdminVolunteerService.updateBy 开头 requireSuperAdmin(operatorAdminId)；
    # user:edit 是 V2 迁移抬头列明的两个「不入权限表、写死仅超管」高危点之一
    ("PUT", "/a/user/volunteers/{}"),
}


def parse_doc(doc: Path, consts: dict) -> tuple:
    """→ ({Endpoint: Perm}, duplicates, unimplemented_set)"""
    rows: dict = {}
    dups: list = []
    unimplemented: set = set()
    for lineno, line in enumerate(doc.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        m = _DOC_ROW.match(line)
        if not m:
            continue
        verb, raw_path, middle, perm_cell = m.groups()
        # 一行写多个方法（如 `GET·DELETE`）视为未拆分，按第一个方法记，并在差集里自然暴露
        ep = Endpoint(verb.upper(), normalize_path(raw_path))
        if ep in rows:
            dups.append((ep, lineno))
            continue
        if _UNIMPLEMENTED.search(middle) or _UNIMPLEMENTED.search(perm_cell):
            unimplemented.add(ep)
        rows[ep] = _doc_perm(perm_cell, consts)
    return rows, dups, unimplemented


def _doc_perm(cell: str, consts: dict) -> Perm:
    # 先剥掉 markdown 强调符：文档里写的是 `A **或** B（SaMode.OR）`，
    # 不剥就会因为匹配的是「 或 」而漏判成 AND——这正是本脚本第一版误报的 3 处。
    text = cell.replace("`", "").replace("*", "").strip()
    if "公开" in text:
        return Perm(public=True)
    if _EXEMPT_MARKER.search(text):
        # 文档明确声明「不入权限点表」——该码按设计**不是可授权权限点**，因而端点上不会有
        # @SaCheckPermission，守卫写在 service（`requireSuperAdmin`）。V2 迁移抬头列明的两个高危点
        # `user:edit`、`org:perm-assign` 即属此类：故意不入权限表，避免被分配出去。
        # 不识别这条声明，解析器会从解释性文字里裸抽出 `user:edit` 当成必须的注解而误报。
        # 豁免的端点会在输出里**单列**，不让它悄无声息。
        return Perm(codes=frozenset(), public=False, exempt=True)
    codes = set(re.findall(r'[a-z][a-z0-9-]*:[a-z0-9-]+', text))
    mode = "OR" if ("SaMode.OR" in text or "或" in text
                    or re.search(r'\bOR\b', text)) else "AND"
    return Perm(frozenset(codes), mode, public=False)


# ---------------------------------------------------------------- 比对

def compare(code: dict, doc: dict, dups: list, unimplemented: set) -> Findings:
    f = Findings()
    f.duplicate_doc = dups
    for ep in sorted(set(code) - set(doc), key=str):
        f.missing_in_doc.append(ep)
    for ep in sorted(set(doc) - set(code), key=str):
        if ep not in unimplemented:      # 显式标注未实现的文档独有项是允许的
            f.missing_in_code.append(ep)
    for ep in sorted(set(code) & set(doc), key=str):
        a, b = code[ep], doc[ep]
        if b.exempt:
            if (ep.method, ep.path) in EXEMPT_ALLOWLIST:
                f.exempted.append((ep, a.specified))
            else:
                f.unexpected_exempt.append(ep)
            continue
        if not b.codes and not b.public:
            # 文档只写「需登录」、没细化权限点 → 两侧都不声明，无从比对
            continue
        if not a.specified:
            if b.public:
                # 文档声明「公开」而代码也无权限注解 —— 两侧一致
                continue
            # 文档明确要求权限点，代码侧却没有任何 @SaCheckPermission。
            # **这正是「权限注解被删掉」的形态，是本脚本要防的最危险一类回归。**
            # 早先版本在此直接 continue（理由写的是「无从比对」），结果把删注解放过去了：
            # 实测把 POST /a/activity/activities 的解析结果改成「无注解」，文档仍要求
            # activity:publish，脚本照样返回 perm_mismatch=0。故此处必须报错而非跳过。
            f.perm_mismatch.append((ep, "（代码无 @SaCheckPermission）", b.describe()))
            continue
        if a.signature() != b.signature():
            f.perm_mismatch.append((ep, a.describe(), b.describe()))
    return f


# ---------------------------------------------------------------- 自检用例

SELFTEST_CONTROLLER = '''
package demo;
@RestController
@RequestMapping("/a/demo")
public class DemoController {
    @GetMapping                                              // ① 裸注解，无参数
    public Result list() { return null; }

    @PostMapping("/items")                                   // ② 权限常量解引用
    @SaCheckPermission(value = PermissionCode.DEMO_WRITE, type = "admin")
    public Result create() { return null; }

    @PutMapping("/items/{id}")                               // ③ SaMode.OR 多权限
    @SaCheckPermission(value = {PermissionCode.DEMO_WRITE, PermissionCode.DEMO_AUDIT},
            mode = SaMode.OR, type = "admin")
    public Result update() { return null; }

    @DeleteMapping("/items/{id}")                            // ④ 手工超管守卫
    @SaCheckPermission(value = PermissionCode.DEMO_WRITE, type = "admin")
    public Result remove() {
        if (!isSuperAdmin()) { throw new BusinessException("仅超管"); }
        return null;
    }
}
'''

SELFTEST_CONSTS = {"DEMO_WRITE": "demo:write", "DEMO_AUDIT": "demo:audit"}


def selftest() -> int:
    ok = True

    def check(label, actual, expected):
        nonlocal ok
        if actual != expected:
            ok = False
            print(f"  ✗ {label}\n      实际 {actual}\n      期望 {expected}")
        else:
            print(f"  ✓ {label}")

    blocks = dict()
    cm = _CLASS_MAPPING.search(SELFTEST_CONTROLLER)
    base = cm.group(1) if cm else ""
    for m, block in _split_methods(SELFTEST_CONTROLLER):
        ep = Endpoint(m.group(1).upper(), join_path(base, m.group(3) or ""))
        blocks[ep] = _perm_of(block, SELFTEST_CONSTS)

    print("解析器回归用例：")
    check("① 裸 @GetMapping 继承类级路径",
          Endpoint("GET", "/a/demo") in blocks, True)
    check("② PermissionCode 常量解引用",
          blocks[Endpoint("POST", "/a/demo/items")].codes, frozenset({"demo:write"}))
    put = blocks[Endpoint("PUT", "/a/demo/items/{}")]
    check("③ SaMode.OR 识别为 OR", put.mode, "OR")
    check("③ OR 的两个权限点都取到",
          put.codes, frozenset({"demo:write", "demo:audit"}))
    # ④ 只证明「controller 方法文本里的关键词能被识别」。守卫若写在 service 层，
    #    本脚本看不到——manual_guard 因此只作提示、不参与判定，别把它当成守卫存在性检查。
    check("④ controller 方法内的手工超管守卫关键词被标记",
          blocks[Endpoint("DELETE", "/a/demo/items/{}")].manual_guard, True)
    check("④ 手工守卫不影响权限签名（仅提示，不参与判定）",
          blocks[Endpoint("DELETE", "/a/demo/items/{}")].signature(),
          (("demo:write",), "AND", False))
    check("路径变量名被抹平（{id} 与 {applyId} 同一端点）",
          normalize_path("/a/x/{applyId}"), normalize_path("/a/x/{id}"))
    check("单权限时 OR 与 AND 签名等价（避免误报）",
          Perm(frozenset({"a:b"}), "OR").signature(),
          Perm(frozenset({"a:b"}), "AND").signature())

    print("\n文档侧解析回归用例：")
    # 文档用 markdown 粗体写「或」，不剥强调符就会漏判成 AND（第一版误报的 3 处即此）
    or_cell = "honor:medal **或** honor:medal-audit（SaMode.OR）"
    check("⑤ `**或**` 形态识别为 OR", _doc_perm(or_cell, {}).mode, "OR")
    check("⑤ 两个权限点都取到",
          _doc_perm(or_cell, {}).codes, frozenset({"honor:medal", "honor:medal-audit"}))
    check("⑥ 「需登录（activity:manage）」取到权限点且为 AND",
          _doc_perm("需登录（`activity:manage`）", {}).signature(),
          (("activity:manage",), "AND", False))
    check("⑦ 「公开」识别为免登录", _doc_perm("公开", {}).public, True)
    check("⑧ 代码侧无权限注解不判为公开（否则凭空造出上百条假不一致）",
          _perm_of("@GetMapping\npublic Result x() { return null; }", {}).specified, False)

    print("\n比对逻辑回归用例（权限回归必须报错，不得跳过）：")
    ep = Endpoint("POST", "/a/demo/items")
    # ⑨ 最危险的一类：注解被删掉，而文档仍要求权限点
    f = compare({ep: Perm(specified=False)},
                {ep: Perm(frozenset({"demo:write"}))}, [], set())
    check("⑨ 文档要求权限点、代码无注解 → 报权限不一致", len(f.perm_mismatch), 1)
    check("⑨ 该情形计入总数（非 0 退出）", f.total(), 1)
    # ⑩ 反向：两侧都不细化时不得误报
    f2 = compare({ep: Perm(specified=False)}, {ep: Perm()}, [], set())
    check("⑩ 文档也只写「需登录」时不误报", f2.total(), 0)
    # ⑪ 文档声明公开、代码无注解 → 一致
    f3 = compare({ep: Perm(specified=False)}, {ep: Perm(public=True)}, [], set())
    check("⑪ 文档「公开」+ 代码无注解 → 一致", f3.total(), 0)
    # ⑫ 显式豁免：文档声明「仅超管、不入权限点表」时，不得因文字里出现 user:edit 而误报
    su_cell = "仅超管（`user:edit`，不入权限点表、不可分配，service 手工守卫）"
    check("⑫ 「仅超管」声明不被当成必须的权限注解",
          _doc_perm(su_cell, {}).codes, frozenset())
    # 用**已登记**的端点：未登记的情形由 ⑯ 专门覆盖
    listed = Endpoint("PUT", "/a/user/volunteers/{}")
    f4 = compare({listed: Perm(specified=False)}, {listed: _doc_perm(su_cell, {})}, [], set())
    check("⑫ 已登记端点无注解不报错（守卫在 service）", f4.total(), 0)
    # ⑬ 豁免不得外溢：普通权限点仍必须有注解
    f5 = compare({ep: Perm(specified=False)},
                 {ep: _doc_perm("需登录（`user:list`）", {})}, [], set())
    check("⑬ 普通权限点不受豁免影响，仍报错", f5.total(), 1)
    # ⑭ **反向用例（评审实测构造）**：文档同时出现权限码与「仅超管」，
    #    但没有「不入权限点表」这条决定性声明 → 删掉注解必须仍然失败。
    #    早先版本用宽泛的 `仅超管|不可分配` 做豁免，正是在这里放过了权限回归。
    mixed = _doc_perm("`activity:publish`；仅超管", {})
    check("⑭ 「权限码 + 仅超管」不被豁免（仍要求注解）", mixed.exempt, False)
    check("⑭ 权限码仍被解析出来", mixed.codes, frozenset({"activity:publish"}))
    f6 = compare({ep: Perm(specified=False)}, {ep: mixed}, [], set())
    check("⑭ 该组合下删注解必须报错", f6.total(), 1)
    # ⑮ 只有「不入权限点表」才豁免，且会被单列出来
    exempt_cell = "仅超管（`user:edit`，不入权限点表、不可分配，service 手工守卫）"
    allow_ep = Endpoint("PUT", "/a/user/volunteers/{}")   # 已登记在 EXEMPT_ALLOWLIST 内
    f7 = compare({allow_ep: Perm(specified=False)},
                 {allow_ep: _doc_perm(exempt_cell, {})}, [], set())
    check("⑮ 已登记的豁免通过", f7.total(), 0)
    check("⑮ 豁免被单列（不静默）", len(f7.exempted), 1)
    # ⑯ **未登记的豁免必须失败**：只打印警告不够，CI 只看退出码，
    #    否则任何人往文档里写一句「不入权限点表」就能悄悄免掉一个端点的权限校验
    f8 = compare({ep: Perm(specified=False)}, {ep: _doc_perm(exempt_cell, {})}, [], set())
    check("⑯ 未登记的豁免计为问题（非 0 退出）", f8.total(), 1)
    check("⑯ 且归类到 unexpected_exempt", len(f8.unexpected_exempt), 1)
    check("⑯ 不混进已登记豁免列表", len(f8.exempted), 0)

    print("\n自检" + ("通过" if ok else "失败"))
    return 0 if ok else 1


# ---------------------------------------------------------------- 主流程

def main() -> int:
    ap = argparse.ArgumentParser(description="接口契约校验（代码 ↔ url文档v2.md）")
    ap.add_argument("--root", default=".", help="仓库根，默认当前目录")
    ap.add_argument("--verbose", action="store_true", help="打印逐条明细")
    ap.add_argument("--selftest", action="store_true", help="只跑解析器回归用例")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = Path(args.root).resolve()
    code_root = root / "代码"
    doc = root / "文档" / "v2" / "url文档v2.md"
    if not code_root.is_dir() or not doc.is_file():
        print(f"找不到 代码/ 或 文档/v2/url文档v2.md（--root={root}）", file=sys.stderr)
        return 2

    consts = load_permission_constants(code_root)
    code = parse_controllers(code_root, consts)
    docmap, dups, unimplemented = parse_doc(doc, consts)
    f = compare(code, docmap, dups, unimplemented)

    # 区分两个数：`unimplemented` 是**所有**带「未实现/占位」标记的文档行（多数端点其实有实现，
    # 只是某个子功能标了占位）；真正被豁免出差集的只有「文档独有 **且** 已标注」的那部分。
    # 早先把前者印成「文档独有且已标注未实现」，数字与 --verbose 明细对不上（3 vs 1）。
    doc_only_unimplemented = sorted((set(docmap) - set(code)) & unimplemented, key=str)
    print(f"代码端点 {len(code)}　文档端点 {len(docmap)}　"
          f"文档独有且已标注未实现 {len(doc_only_unimplemented)}"
          f"（全文档「未实现」标记行 {len(unimplemented)}）　权限常量 {len(consts)}")

    if f.missing_in_doc:
        print(f"\n✗ 代码有、文档无（{len(f.missing_in_doc)}）：")
        for ep in f.missing_in_doc:
            print(f"    {ep}")
    if f.missing_in_code:
        print(f"\n✗ 文档有、代码无且未标注未实现（{len(f.missing_in_code)}）：")
        for ep in f.missing_in_code:
            print(f"    {ep}")
    if f.perm_mismatch:
        print(f"\n✗ 权限不一致（{len(f.perm_mismatch)}）：")
        for ep, a, b in f.perm_mismatch:
            print(f"    {ep}\n        代码 {a}\n        文档 {b}")
    if f.duplicate_doc:
        print(f"\n✗ 文档重复行（{len(f.duplicate_doc)}）：")
        for ep, lineno in f.duplicate_doc:
            print(f"    {ep}　第 {lineno} 行")

    if f.unexpected_exempt:
        print(f"\n✗ 未登记的人工守卫豁免（{len(f.unexpected_exempt)}）——"
              f"文档写了「不入权限点表」，但该端点不在 EXEMPT_ALLOWLIST 内：")
        for ep in f.unexpected_exempt:
            print(f"    {ep}")
        print("    → 请先人工确认 service 层守卫确实存在，再把它登记进脚本的 EXEMPT_ALLOWLIST。")

    if f.exempted:
        # 永远打印（不受 --verbose 控制）：豁免是人工判断，必须每次都摆在评审眼前
        print(f"\n⚠ 已登记的人工守卫豁免（文档声明「不入权限点表」，故不要求 @SaCheckPermission）："
              f"{len(f.exempted)} 处")
        for ep, has_annotation in f.exempted:
            note = "代码侧另有权限注解" if has_annotation else "代码侧无注解，守卫应在 service"
            print(f"    {ep}　—— {note}")

    if args.verbose and doc_only_unimplemented:
        print(f"\n（已标注未实现、不计为问题的文档独有项）")
        for ep in doc_only_unimplemented:
            print(f"    {ep}")

    if f.total() == 0:
        print("\n✓ 契约一致")
        return 0
    print(f"\n共 {f.total()} 处不一致")
    return 1


if __name__ == "__main__":
    sys.exit(main())
