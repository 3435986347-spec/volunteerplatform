#!/usr/bin/env python3
"""从代码生成知识库里「能推出来的事实」：迁移清单、权限点表、表清单、按模块的接口索引。

这些数字和清单以前手写在 CLAUDE.md / 模块页里，每批都要同步，漏一处就互相矛盾（权限点总数先后写过
51 / 57 / 58 / 60 / … / 96，读者不知道哪个是现在的）。改成从代码生成后，**页里只放链接，不再手抄数字**。

输出到 `知识库/生成/`（整个目录都是生成物，不要手改）：

    迁移.md             V1…Vn：文件名 + 文件头第一行注释
    权限点.md           code / 名称 / 模块 / 类型 / 登记于哪个迁移（回放 INSERT 与改名的 UPDATE）
    表.md               表名 / 表注释 / 建于哪个迁移 / 实体在哪个模块
    接口/<模块>.md      方法 / 路径 / 权限 / 说明（@Operation summary）/ 控制器

用法：

    python3 tools/gen_knowledge_base.py           # 在仓库根运行，重写 知识库/生成/
    python3 tools/gen_knowledge_base.py --check   # 只比对、不写；生成物过期则退出码 1（收尾 / CI 用）

接口解析复用 `tools/verify_url_contract.py`（剥注释、按注解簇切方法、权限常量解引用），口径与契约核对一致。
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_url_contract as contract  # noqa: E402
from verify_knowledge_base import load_permissions  # noqa: E402

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

MODULE_ORDER = ["common", "auth", "organization", "publicity", "activity", "trade", "donate", "user",
                "honor", "social", "enterprise", "data", "system", "api"]
PERM_MODULE_LABEL = {"org": "organization"}
PERM_TYPE_LABEL = {1: "菜单", 2: "操作", 3: "审核"}   # V2 迁移：type 1菜单 2操作 3审核
ROLE_LABEL = {"/v": "志愿者端 `/v`", "/a": "后台 `/a`", "/e": "爱心企业端 `/e`"}
_ICLOUD_DUP = re.compile(r" \d+(\.\w+)?$")
_OPERATION = re.compile(r'@Operation\(\s*summary\s*=\s*"((?:[^"\\]|\\.)*)"')
_MAPPING = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?(?:\{\s*)?"([^"]*)")?')

HEADER = "<!-- 生成物：由 tools/gen_knowledge_base.py 从代码生成，不要手改；改了代码重跑脚本。 -->\n\n"


def _skip(p: Path) -> bool:
    return "target" in p.parts or bool(_ICLOUD_DUP.search(p.stem))


def _md(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ").strip()


def _module_of(path: Path) -> str:
    for part in path.parts:
        if part.startswith("hengde-volunteer-"):
            return part[len("hengde-volunteer-"):]
    return "?"


# ---------------------------------------------------------------- 迁移

def migrations(mig_dir: Path) -> list[tuple[int, str, str]]:
    rows = []
    for f in mig_dir.glob("V*__*.sql"):
        if _skip(f):
            continue
        num = int(re.match(r"V(\d+)", f.name).group(1))
        first = ""
        for line in f.read_text(encoding="utf-8", errors="ignore").splitlines():
            s = line.strip()
            if s.startswith("--") and s.strip("- "):
                first = s.lstrip("-").strip()
                break
        rows.append((num, f.name, first))
    return sorted(rows)


def render_migrations(rows) -> str:
    out = [HEADER, "# 迁移清单\n\n",
           f"共 {len(rows)} 个，当前最大 **V{rows[-1][0]}**，下一个从 **V{rows[-1][0] + 1}** 起"
           "（挑号前先跑跨分支最大号命令，见 `知识库/模块/common.md`）。"
           "脚本在 `代码/hengde-volunteer-common/src/main/resources/db/migration/`。\n\n",
           "| 版本 | 文件 | 文件头第一行 |\n|---|---|---|\n"]
    for num, name, first in reversed(rows):
        out.append(f"| V{num} | `{name}` | {_md(first)} |\n")
    return "".join(out)


# ---------------------------------------------------------------- 权限点

def render_permissions(perms: dict) -> str:
    by_module = defaultdict(list)
    for code, (name, module, typ, version) in perms.items():
        by_module[PERM_MODULE_LABEL.get(module, module)].append((code, name, typ, version))
    out = [HEADER, "# 权限点\n\n",
           f"现行 **{len(perms)}** 个（回放全部迁移里的 `INSERT INTO permission` 与改名的 `UPDATE`）。"
           "另有两个**按设计不入表**的高危码 `user:edit`、`org:perm-assign`（写死仅超管，见 V2 迁移抬头）。\n\n",
           "| 模块 | 个数 |\n|---|---|\n"]
    order = [m for m in MODULE_ORDER if m in by_module] + sorted(set(by_module) - set(MODULE_ORDER))
    for m in order:
        out.append(f"| {m} | {len(by_module[m])} |\n")
    for m in order:
        out.append(f"\n## {m}\n\n| 权限点 | 名称 | 类型 | 登记于 |\n|---|---|---|---|\n")
        for code, name, typ, version in sorted(by_module[m]):
            out.append(f"| `{code}` | {_md(name)} | {PERM_TYPE_LABEL.get(typ, typ)} | {version} |\n")
    return "".join(out)


# ---------------------------------------------------------------- 表

def _table_options(text: str, open_paren: int) -> str:
    """从建表语句的左括号起按括号配平，返回右括号之后、分号之前的表选项（`ENGINE=… COMMENT='…'`）。

    不能用非贪婪正则找右括号：列定义里的 `VARCHAR(64)`、`DECIMAL(10,2)` 会让它停在第一列，拿到的是列注释。
    """
    depth, i, in_str = 0, open_paren, False
    while i < len(text):
        ch = text[i]
        if ch == "'" and not in_str:
            in_str = True
        elif ch == "'" and in_str:
            if i + 1 < len(text) and text[i + 1] == "'":
                i += 1
            else:
                in_str = False
        elif not in_str and ch == "(":
            depth += 1
        elif not in_str and ch == ")":
            depth -= 1
            if depth == 0:
                end = text.find(";", i)
                return text[i + 1:end if end >= 0 else len(text)]
        i += 1
    return ""


def tables(code_root: Path, mig_dir: Path) -> list[tuple[str, str, str, str]]:
    created: dict[str, tuple[str, str]] = {}
    files = [f for f in mig_dir.glob("V*__*.sql") if not _skip(f)]
    for f in sorted(files, key=lambda x: int(re.match(r"V(\d+)", x.name).group(1))):
        text = re.sub(r"--[^\n]*", "", f.read_text(encoding="utf-8", errors="ignore"))
        version = f.name.split("__")[0]
        for m in re.finditer(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\(", text, re.I):
            options = _table_options(text, m.end() - 1)
            comment = re.search(r"COMMENT\s*=?\s*'([^']*)'", options, re.I)
            created.setdefault(m.group(1), (version, comment.group(1) if comment else ""))
        for m in re.finditer(r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`?(\w+)`?", text, re.I):
            created.pop(m.group(1), None)
        for m in re.finditer(r"RENAME\s+TABLE\s+`?(\w+)`?\s+TO\s+`?(\w+)`?", text, re.I):
            if m.group(1) in created:
                created[m.group(2)] = created.pop(m.group(1))
    owner: dict[str, str] = {}
    for f in code_root.rglob("*.java"):
        if _skip(f) or "/src/main/" not in f.as_posix():
            continue
        for t in re.findall(r'@TableName\(\s*(?:value\s*=\s*)?"(\w+)"', f.read_text(encoding="utf-8", errors="ignore")):
            owner.setdefault(t, _module_of(f))
    return sorted((t, owner.get(t, "—"), v, c) for t, (v, c) in created.items())


def render_tables(rows) -> str:
    out = [HEADER, "# 表\n\n",
           f"共 {len(rows)} 张（回放迁移里的 `CREATE` / `DROP` / `RENAME TABLE`）。「模块」是 `@TableName` 实体所在的模块，"
           "「—」表示没有 `@TableName` 实体（只被 mapper 里手写的 SQL 读写，或是一次性的留底表）。\n\n",
           "| 表 | 模块 | 建于 | 表注释 |\n|---|---|---|---|\n"]
    for t, module, version, comment in rows:
        out.append(f"| `{t}` | {module} | {version} | {_md(comment)} |\n")
    return "".join(out)


# ---------------------------------------------------------------- 接口

def endpoints(code_root: Path, consts: dict) -> dict[str, list[tuple]]:
    """→ {模块: [(method, path, perm_text, summary, controller)]}"""
    result: dict[str, list[tuple]] = defaultdict(list)
    for f in sorted(code_root.rglob("*Controller.java")):
        if _skip(f) or "/test/" in f.as_posix():
            continue
        body = contract.strip_java_comments(f.read_text(encoding="utf-8", errors="ignore"))
        cm = contract._CLASS_MAPPING.search(body)
        base = cm.group(1) if cm else ""
        for m, block in contract._split_methods(body):
            verb = m.group(1).upper()
            mm = _MAPPING.search(block)
            sub = (mm.group(2) or "") if mm else (m.group(3) or "")
            path = "/" + "/".join(p for p in (base.strip("/"), sub.strip("/")) if p)
            perm = contract._perm_of(block, consts)
            if perm.codes:
                joiner = " 或 " if perm.mode == "OR" else " + "
                perm_text = joiner.join(f"`{c}`" for c in sorted(perm.codes))
            else:
                perm_text = ""
            op = _OPERATION.search(block)
            summary = op.group(1).replace('\\"', '"') if op else ""
            result[_module_of(f)].append((verb, path, perm_text, summary, f.stem))
    return result


def _role(path: str) -> str:
    for prefix in ROLE_LABEL:
        if path == prefix or path.startswith(prefix + "/"):
            return prefix
    return "其他"


def render_endpoint_index(eps: dict) -> str:
    total = sum(len(v) for v in eps.values())
    out = [HEADER, "# 接口索引（按模块）\n\n",
           f"共 **{total}** 个端点，从 `代码/` 的 Controller 注解解析（剥注释后解析，口径同 `tools/verify_url_contract.py`）。"
           "路径不含 context-path `/api`。权限列为空＝方法上没有 `@SaCheckPermission`（只需登录，或公开——公开的见根文件鉴权白名单）。"
           "请求体 / 响应字段看 `文档/v1~v4/url文档v*.md` 或运行中的 `/api/doc.html`。\n\n",
           "| 模块 | `/v` | `/a` | `/e` | 其他 | 页 |\n|---|---|---|---|---|---|\n"]
    for m in [x for x in MODULE_ORDER if x in eps]:
        counts = defaultdict(int)
        for e in eps[m]:
            counts[_role(e[1])] += 1
        out.append(f"| {m} | {counts['/v'] or ''} | {counts['/a'] or ''} | {counts['/e'] or ''} | "
                   f"{counts['其他'] or ''} | [接口/{m}.md](接口/{m}.md) |\n")
    return "".join(out)


def render_endpoints(module: str, rows: list[tuple]) -> str:
    out = [HEADER, f"# {module} 模块的接口\n\n",
           f"{len(rows)} 个端点。模块说明见 [`知识库/模块/{module}.md`](../../模块/{module}.md)。\n"]
    by_role = defaultdict(list)
    for r in rows:
        by_role[_role(r[1])].append(r)
    for role in ["/v", "/a", "/e", "其他"]:
        if not by_role.get(role):
            continue
        out.append(f"\n## {ROLE_LABEL.get(role, '其他（回调 / 分享 / WebSocket 等）')}\n\n"
                   "| 方法 | 路径 | 权限 | 说明 | 控制器 |\n|---|---|---|---|---|\n")
        for verb, path, perm, summary, ctrl in sorted(by_role[role], key=lambda r: (r[1], r[0])):
            out.append(f"| {verb} | `{path}` | {perm} | {_md(summary)} | `{ctrl}` |\n")
    return "".join(out)


# ---------------------------------------------------------------- 入口

def generate(root: Path) -> dict[Path, str]:
    code = root / "代码"
    mig = code / "hengde-volunteer-common/src/main/resources/db/migration"
    out_dir = root / "知识库" / "生成"
    files: dict[Path, str] = {}
    files[out_dir / "迁移.md"] = render_migrations(migrations(mig))
    files[out_dir / "权限点.md"] = render_permissions(load_permissions(mig))
    files[out_dir / "表.md"] = render_tables(tables(code, mig))
    eps = endpoints(code, contract.load_permission_constants(code))
    files[out_dir / "接口索引.md"] = render_endpoint_index(eps)
    for m, rows in eps.items():
        files[out_dir / "接口" / f"{m}.md"] = render_endpoints(m, rows)
    return files


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true", help="只比对，不写")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    if not (root / "代码").is_dir():
        print(f"找不到 {root}/代码——请在文档仓库根目录运行", file=sys.stderr)
        return 2
    files = generate(root)
    out_dir = root / "知识库" / "生成"
    existing = {p for p in out_dir.rglob("*.md") if p.name != "README.md"} if out_dir.exists() else set()
    stale = [p for p, text in files.items() if not p.exists() or p.read_text(encoding="utf-8") != text]
    orphans = sorted(existing - set(files))
    if args.check:
        for p in stale:
            print(f"过期：{p.relative_to(root)}")
        for p in orphans:
            print(f"多余：{p.relative_to(root)}（代码里已没有对应内容）")
        if stale or orphans:
            print("✗ 生成物与代码不一致，跑一次 python3 tools/gen_knowledge_base.py")
            return 1
        print(f"✓ {len(files)} 个生成物与代码一致")
        return 0
    for p, text in files.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    for p in orphans:
        p.unlink()
    print(f"写了 {len(files)} 个文件（{len(stale)} 个有变化，删了 {len(orphans)} 个多余的）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
