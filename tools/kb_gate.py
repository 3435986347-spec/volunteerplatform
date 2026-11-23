#!/usr/bin/env python3
"""知识库提交闸门：改了代码却没回写知识库的提交，不让提交、不让推送。

为什么要有它：AGENTS.md 里写了「改完更新知识库模块页」，各家 agent（Cursor、WorkBuddy、Kimi、Codex……）
都读到了，照样经常忘。写在文档里的规矩靠自觉；钩子不靠自觉——五个仓库共用这一份脚本，
谁提交都一样被拦。

规则（与 AGENTS.md「开工协议」第 5 条一致，改了一处要同步另一处）：

  后端 hengde-volunteer-<m>/src/main/**        → 知识库 模块/<m>.md
      （common 的 db/migration/ 不要求 common.md，只要求 生成/ 最新）
  后端 Controller / 迁移 / 权限点有变化         → `gen_knowledge_base.py --check` 要过
  小程序 pages/ components/ custom-tab-bar/ utils/ styles/ app.*
                                               → 跨端/前端页面进度.md、跨端/小程序页面构建.md、跨端/前端原型还原.md 任一
  控制台 index.html、assets/*.js（vendor 除外）  → 跨端/前端页面进度.md、跨端/后台控制台.md、跨端/后台页面构建.md 任一
  知识库仓库自身                                → `verify_knowledge_base.py` 与 `gen_knowledge_base.py --check` 要过
  文档仓库根 AGENTS.md                          → `gen_agent_entries.py --check` 要过

「回写了」的判据（任一）：那一页在同一次提交里；在知识库仓库里有没提交的改动；或者在这次提交前后的
12 小时内被提交过（知识库仓库或文档仓库里都算）。

确实不涉及时，提交说明里单独一行：  知识库: 不涉及 — <理由>
这些提交会出现在 audit 报告里。

用法（在文档仓库根，或任一仓库里经钩子调用）：
    python3 tools/kb_gate.py install            # 给五个仓库装钩子（core.hooksPath → tools/githooks）
    python3 tools/kb_gate.py audit [--days 14]  # 列出最近哪些提交没回写知识库、哪些写了「不涉及」
    python3 tools/kb_gate.py mirror [--apply]   # 文档仓库对它镜像的后端 / 知识库：对齐到子仓库 HEAD
    python3 tools/kb_gate.py check --msg-file F # commit-msg 钩子用
    python3 tools/kb_gate.py push               # pre-push 钩子用（stdin 是 git 给的 ref 列表）
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent          # 文档仓库根
BACKEND_DIR = "volunteerplatform-backend"
KB_DIR = "volunteerplatform-knowlegebase"
FRONTENDS = {"miniprogram": "volunteerplatform-miniprogram", "console": "volunteerplatform-back"}
# 改名前文档仓库里的旧目录名，mirror 时一并从索引里清掉
OLD_PREFIXES = {BACKEND_DIR: "代码", KB_DIR: "知识库"}
# 「最近回写过」的时间窗：提交前后 12 小时。环境变量 KB_GATE_WINDOW_HOURS 只能把它调小（测试用），不能放大。
WINDOW = 12 * 3600
try:
    WINDOW = min(WINDOW, int(float(os.environ.get("KB_GATE_WINDOW_HOURS", "12")) * 3600))
except ValueError:
    pass
SKIP_RE = re.compile(r"^\s*知识库\s*[:：]\s*不涉及\s*[—–\-:：]*\s*(\S.{1,})$", re.M)

MINI_PAGES = ("跨端/前端页面进度.md", "跨端/小程序页面构建.md", "跨端/前端原型还原.md")
CONSOLE_PAGES = ("跨端/前端页面进度.md", "跨端/后台控制台.md", "跨端/后台页面构建.md")
_MINI_RE = re.compile(r"^(pages|components|custom-tab-bar|utils|styles)/|^app\.(js|json|wxss)$")
_CONSOLE_RE = re.compile(r"^index\.html$|^assets/(?!vendor/).+\.js$")
_BACKEND_RE = re.compile(r"^hengde-volunteer-([a-z]+)/src/main/(.+)$")


# ---------------------------------------------------------------- git 与仓库

def git(cwd: Path, *args: str, stdin: str | bytes | None = None, check: bool = True) -> str:
    data = stdin.encode() if isinstance(stdin, str) else stdin
    p = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=cwd, input=data,
                       capture_output=True)
    if check and p.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败（{cwd}）：{p.stderr.decode(errors='replace').strip()}")
    return p.stdout.decode("utf-8", errors="replace")


def find_dir(name: str) -> Path | None:
    """子仓库先找文档仓库里面，再找与文档仓库同级。"""
    for d in (ROOT / name, ROOT.parent / name):
        if (d / ".git").exists():
            return d.resolve()
    return None


def repos() -> dict[str, Path]:
    out = {"docs": ROOT}
    for kind, name in (("backend", BACKEND_DIR), ("kb", KB_DIR),
                       ("miniprogram", FRONTENDS["miniprogram"]), ("console", FRONTENDS["console"])):
        d = find_dir(name)
        if d:
            out[kind] = d
    return out


def kind_of(top: Path) -> str | None:
    top = top.resolve()
    for kind, d in repos().items():
        if d == top:
            return kind
    return None


# ---------------------------------------------------------------- 规则

@dataclass
class Need:
    pages: dict[str, tuple[str, ...]] = field(default_factory=dict)   # 原因 → 任一页即可
    gen: bool = False          # 生成/ 要最新
    entries: bool = False      # 后端入口要与根 AGENTS.md 一致
    verify: bool = False       # 知识库点名要核对得上
    kb_pages_in_commit: set[str] = field(default_factory=set)        # 本次提交自己带的知识库页

    def empty(self) -> bool:
        return not (self.pages or self.gen or self.entries or self.verify)


def _backend_rule(path: str, need: Need) -> None:
    m = _BACKEND_RE.match(path)
    if not m:
        return
    module, rest = m.group(1), m.group(2)
    if "db/migration/" in rest:
        need.gen = True
        return
    if "/controller/" in rest or rest.endswith("Controller.java"):
        need.gen = True
    need.pages.setdefault(f"后端 {module} 模块", (f"模块/{module}.md",))


def requirements(kind: str, files: list[str]) -> Need:
    need = Need()
    for f in files:
        if kind == "backend":
            _backend_rule(f, need)
        elif kind == "miniprogram":
            if _MINI_RE.search(f):
                need.pages.setdefault("小程序页面", MINI_PAGES)
        elif kind == "console":
            if _CONSOLE_RE.search(f):
                need.pages.setdefault("后台控制台页面", CONSOLE_PAGES)
        elif kind == "kb":
            need.kb_pages_in_commit.add(f)
            if f.endswith(".md"):
                need.verify = True
            if f.startswith("生成/"):
                need.gen = True
        elif kind == "docs":
            if f.startswith(BACKEND_DIR + "/"):
                _backend_rule(f[len(BACKEND_DIR) + 1:], need)
            elif f.startswith(KB_DIR + "/"):
                need.kb_pages_in_commit.add(f[len(KB_DIR) + 1:])
                if f.endswith(".md"):
                    need.verify = True
            elif f == "AGENTS.md" or f == "tools/gen_agent_entries.py":
                need.entries = True
                need.verify = True
    return need


# ---------------------------------------------------------------- 「回写了」的证据

def kb_dirty_pages() -> set[str]:
    kb = find_dir(KB_DIR)
    if not kb:
        return set()
    out = set()
    for line in git(kb, "status", "--porcelain", "--untracked-files=all").splitlines():
        path = line[3:].split(" -> ")[-1].strip().strip('"')
        out.add(path)
    return out


_history_cache: dict[tuple[str, str], list[int]] = {}


def page_commit_times(page: str) -> list[int]:
    """这一页被提交过的时间点（知识库仓库 + 文档仓库，含改名前的 知识库/ 前缀）。"""
    key = ("all", page)
    if key in _history_cache:
        return _history_cache[key]
    times: list[int] = []
    kb = find_dir(KB_DIR)
    if kb:
        times += [int(t) for t in git(kb, "log", "--format=%ct", "--", page, check=False).split()]
    for prefix in (KB_DIR, OLD_PREFIXES[KB_DIR]):
        times += [int(t) for t in git(ROOT, "log", "--format=%ct", "--", f"{prefix}/{page}", check=False).split()]
    _history_cache[key] = times
    return times


def touched(page: str, need: Need, at: int, dirty: set[str] | None) -> bool:
    if page in need.kb_pages_in_commit:
        return True
    if dirty is not None and page in dirty:
        return True
    return any(at - WINDOW <= t <= at + WINDOW for t in page_commit_times(page))


def missing_pages(need: Need, at: int, dirty: set[str] | None) -> list[tuple[str, tuple[str, ...]]]:
    return [(why, pages) for why, pages in need.pages.items()
            if not any(touched(p, need, at, dirty) for p in pages)]


def run_tool(script: str) -> tuple[bool, str]:
    p = subprocess.run([sys.executable, str(ROOT / "tools" / script), *(["--check"] if script.startswith("gen_") else [])],
                       cwd=ROOT, capture_output=True, text=True)
    tail = "\n".join((p.stdout + p.stderr).strip().splitlines()[-12:])
    return p.returncode == 0, tail


# ---------------------------------------------------------------- 文档仓库的镜像

def mirror_plan(prefix: str) -> tuple[list[str], list[str]] | None:
    """返回 (应有的 index 行, 文档仓库索引里该删的路径)。子仓库不存在时返回 None。"""
    sub = ROOT / prefix
    if not (sub / ".git").exists():
        return None
    want = []
    rows = [l for l in git(sub, "ls-tree", "-r", "-z", "HEAD").split("\0") if l]
    paths = [f"{prefix}/{r.split(chr(9), 1)[1]}" for r in rows]
    ignored = set(git(ROOT, "check-ignore", "--no-index", "--stdin", "-z", stdin="\0".join(paths), check=False)
                  .split("\0")) - {""}
    for r, full in zip(rows, paths):
        meta, _ = r.split("\t", 1)
        mode, typ, sha = meta.split()
        if typ != "blob" or full in ignored:
            continue
        want.append(f"{mode} {sha}\t{full}")
    have = [l for l in git(ROOT, "ls-files", "-z", "--", prefix, OLD_PREFIXES[prefix]).split("\0") if l]
    return want, have


def mirror_diff(prefix: str) -> tuple[int, int, int] | None:
    plan = mirror_plan(prefix)
    if plan is None:
        return None
    want, _ = plan
    cur = {}
    for l in git(ROOT, "ls-files", "-s", "-z", "--", prefix, OLD_PREFIXES[prefix]).split("\0"):
        if l:
            meta, path = l.split("\t", 1)
            mode, sha, _stage = meta.split()
            cur[path] = f"{mode} {sha}"
    w = {l.split("\t", 1)[1]: l.split("\t", 1)[0] for l in want}
    added = len(set(w) - set(cur))
    removed = len(set(cur) - set(w))
    changed = sum(1 for p in set(w) & set(cur) if w[p] != cur[p])
    return added, changed, removed


def mirror_apply(prefix: str) -> None:
    plan = mirror_plan(prefix)
    if plan is None:
        return
    want, have = plan
    sub = ROOT / prefix
    shas = sorted({l.split()[1] for l in want})
    check = git(ROOT, "cat-file", "--batch-check", stdin="\n".join(shas) + "\n", check=False)
    missing = [l.split()[0] for l in check.splitlines() if l.endswith(" missing")]
    for sha in missing:
        blob = subprocess.run(["git", "cat-file", "blob", sha], cwd=sub, capture_output=True, check=True).stdout
        got = git(ROOT, "hash-object", "-w", "--stdin", stdin=blob).strip()
        if got != sha:
            raise RuntimeError(f"写入对象 {sha} 得到 {got}")
    if have:
        git(ROOT, "update-index", "--force-remove", "-z", "--stdin", stdin="\0".join(have) + "\0")
    git(ROOT, "update-index", "--add", "-z", "--index-info", stdin="\0".join(want) + "\0")


def mirror_ok_at(commit: str) -> list[str]:
    """文档仓库某个提交里的镜像是否等于子仓库 HEAD；返回落后的目录。"""
    bad = []
    for prefix in (BACKEND_DIR, KB_DIR):
        plan = mirror_plan(prefix)
        if plan is None:
            continue
        want = set(plan[0])
        got = set()
        for r in git(ROOT, "ls-tree", "-r", "-z", commit, "--", prefix).split("\0"):
            if r:
                meta, path = r.split("\t", 1)
                mode, typ, sha = meta.split()
                got.add(f"{mode} {sha}\t{path}")
        if want != got:
            bad.append(prefix)
    return bad


# ---------------------------------------------------------------- 报告

def explain(missing, tool_failures, kind: str) -> str:
    head = "这次提交改了代码，但没有回写知识库。" if missing else "知识库的生成物或点名核对没过。"
    lines = ["", f"✗ 知识库提交闸门：{head}"]
    for why, pages in missing:
        alts = " 或 ".join(f"{KB_DIR}/{p}" for p in pages)
        lines.append(f"  · {why} → 要改 {alts}")
    for name, tail in tool_failures:
        lines.append(f"  · {name} 没过：")
        lines += [f"      {t}" for t in tail.splitlines()]
    lines += [
        "",
        "  怎么办：按 AGENTS.md「开工协议」第 5 条更新上面的知识库页（模块页写「现行要点」与「按批次的记录」，",
        "  前端写 跨端/前端页面进度.md），跑 python3 tools/gen_knowledge_base.py 与 verify_knowledge_base.py，再提交。",
        "  知识库页可以先改着不提交（闸门认工作区里的改动），也可以先在知识库仓库里提交。",
        "  真的不涉及（纯测试 / 格式 / 依赖升级）：提交说明里单独写一行「知识库: 不涉及 — <理由>」。",
        "  不要用 --no-verify，不要改钩子。",
        "",
    ]
    return "\n".join(lines)


def evaluate(kind: str, files: list[str], msg: str, at: int, dirty: set[str] | None,
             run_tools: bool) -> tuple[str, str]:
    """返回 (状态, 说明)。状态：ok / skip / fail / none。"""
    need = requirements(kind, files)
    if need.empty():
        return "none", ""
    m = SKIP_RE.search(msg)
    if m and need.pages:
        return "skip", m.group(1).strip()
    missing = missing_pages(need, at, dirty)
    failures = []
    if run_tools:
        for flag, script in ((need.gen, "gen_knowledge_base.py"), (need.entries, "gen_agent_entries.py"),
                             (need.verify, "verify_knowledge_base.py")):
            if flag:
                ok, tail = run_tool(script)
                if not ok:
                    failures.append((script, tail))
    if missing or failures:
        return "fail", explain(missing, failures, kind)
    return "ok", ""


def toplevel() -> Path:
    return Path(git(Path.cwd(), "rev-parse", "--show-toplevel").strip())


def cmd_check(args) -> int:
    top = toplevel()
    kind = kind_of(top)
    if kind is None:
        return 0
    if (Path(git(top, "rev-parse", "--absolute-git-dir").strip()) / "MERGE_HEAD").exists():
        return 0
    msg = Path(args.msg_file).read_text(encoding="utf-8", errors="replace") if args.msg_file else ""
    msg = "\n".join(l for l in msg.splitlines() if not l.startswith("#"))
    files = [f for f in git(top, "diff", "--cached", "--name-only", "-z").split("\0") if f]
    state, text = evaluate(kind, files, msg, int(time.time()), kb_dirty_pages(), run_tools=True)
    if state == "fail":
        print(text, file=sys.stderr)
        return 1
    if state == "skip":
        print(f"⚠ 知识库闸门：本提交声明「不涉及」（{text}），会列进 kb_gate.py audit。", file=sys.stderr)
    return 0


def commits_in(top: Path, rev_args: list[str]) -> list[tuple[str, int, str]]:
    out = git(top, "log", "--no-merges", "--format=%H%x1f%ct%x1f%B%x1e", *rev_args, check=False)
    res = []
    for chunk in out.split("\x1e"):
        chunk = chunk.strip("\n")
        if not chunk:
            continue
        sha, ct, body = chunk.split("\x1f", 2)
        res.append((sha, int(ct), body))
    return res


def commit_files(top: Path, sha: str) -> list[str]:
    return [f for f in git(top, "diff-tree", "--no-commit-id", "--name-only", "-r", "-z", "--root", sha)
            .split("\0") if f]


def cmd_push(args) -> int:
    top = toplevel()
    kind = kind_of(top)
    if kind is None:
        return 0
    since = int(git(top, "config", "--get", "kbgate.since", check=False).strip() or 0)
    failed = []
    zero = "0" * 40
    for line in sys.stdin.read().splitlines():
        parts = line.split()
        if len(parts) != 4:
            continue
        _lref, lsha, _rref, rsha = parts
        if lsha == zero:
            continue
        rev = [lsha, "--not", "--remotes"] if rsha == zero else [f"{rsha}..{lsha}"]
        for sha, ct, body in commits_in(top, rev):
            if ct < since:
                continue
            state, text = evaluate(kind, commit_files(top, sha), body, ct, None, run_tools=False)
            if state == "fail":
                failed.append((sha, body.splitlines()[0] if body else "", text))
        if kind == "docs":
            bad = mirror_ok_at(lsha)
            if bad:
                failed.append((lsha, "文档仓库镜像落后", "\n".join(
                    [f"  · {p}/ 与该子仓库的 HEAD 不一致——子仓库的提交没在文档仓库里再提交一次。" for p in bad] +
                    ["  怎么办：python3 tools/kb_gate.py mirror --apply，然后用同名提交说明提交，再推送。"])))
    if failed:
        print("\n✗ 知识库闸门（pre-push）：以下提交没回写知识库，推送已拦下。", file=sys.stderr)
        for sha, subject, text in failed:
            print(f"\n── {sha[:9]} {subject}", file=sys.stderr)
            print(text.strip("\n"), file=sys.stderr)
        print("\n  按上面每条的说明补齐后再推送：缺知识库页的，补一个知识库提交即可（前后 12 小时内的都算数）。不要用 --no-verify。", file=sys.stderr)
        return 1
    return 0


def cmd_audit(args) -> int:
    since_ts = int(time.time()) - args.days * 86400
    bad = 0
    for kind, top in repos().items():
        rows = []
        for sha, ct, body in commits_in(top, [f"--since={since_ts}"]):
            state, text = evaluate(kind, commit_files(top, sha), body, ct, None, run_tools=False)
            subject = body.splitlines()[0] if body else ""
            day = time.strftime("%m-%d %H:%M", time.localtime(ct))
            if state == "fail":
                need = requirements(kind, commit_files(top, sha))
                miss = missing_pages(need, ct, None)
                rows.append(f"  ✗ {sha[:9]} {day}  {subject[:60]}\n      缺：" +
                            "；".join(f"{why}（{' / '.join(p)}）" for why, p in miss))
                bad += 1
            elif state == "skip":
                rows.append(f"  ⚠ {sha[:9]} {day}  {subject[:60]}\n      声明不涉及：{text}")
        print(f"\n[{kind}] {top}")
        print("\n".join(rows) if rows else "  ✓ 没有漏回写的提交")
    print("\n[docs 镜像]")
    for prefix in (BACKEND_DIR, KB_DIR):
        d = mirror_diff(prefix)
        if d is None:
            continue
        a, c, r = d
        print(f"  {'✓' if not (a or c or r) else '✗'} {prefix}/：文档仓库索引比子仓库 HEAD 少 {a}、不同 {c}、多 {r} 个文件"
              + ("" if not (a or c or r) else "——跑 python3 tools/kb_gate.py mirror --apply 后提交"))
    print(f"\n共 {bad} 个提交漏回写知识库（最近 {args.days} 天；判据见 tools/kb_gate.py 文件头）。")
    return 1 if bad else 0


def cmd_mirror(args) -> int:
    dirty = False
    for prefix in (BACKEND_DIR, KB_DIR):
        d = mirror_diff(prefix)
        if d is None:
            print(f"  - {prefix}/ 不是 git 仓库，跳过")
            continue
        a, c, r = d
        print(f"  {prefix}/：增 {a}、改 {c}、删 {r}")
        if a or c or r:
            dirty = True
            if args.apply:
                mirror_apply(prefix)
    if args.apply and dirty:
        print("✓ 已写入文档仓库的索引（只动索引，不动工作区）；用与子仓库同名的提交说明提交即可。")
    elif dirty:
        print("加 --apply 写入索引。")
    else:
        print("✓ 镜像与子仓库 HEAD 一致")
    return 0


def cmd_install(args) -> int:
    hooks = ROOT / "tools" / "githooks"
    for h in hooks.iterdir():
        if h.is_file():
            h.chmod(0o755)
    now = str(int(time.time()))
    for kind, top in repos().items():
        old = git(top, "config", "--get", "core.hooksPath", check=False).strip()
        git(top, "config", "core.hooksPath", str(hooks))
        if not git(top, "config", "--get", "kbgate.since", check=False).strip():
            git(top, "config", "kbgate.since", now)
        local = [p.name for p in (top / ".git" / "hooks").glob("*") if not p.name.endswith(".sample")] \
            if (top / ".git" / "hooks").is_dir() else []
        note = f"（原 .git/hooks 里的 {local} 不再生效）" if local else ""
        print(f"  ✓ {kind:12s} {top}  core.hooksPath={hooks}{'（原为 ' + old + '）' if old and old != str(hooks) else ''}{note}")
    print("装好了。pre-push 只检查装钩子之后的提交；历史提交用 audit 看。")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check"); c.add_argument("--msg-file")
    sub.add_parser("push").add_argument("--remote")
    a = sub.add_parser("audit"); a.add_argument("--days", type=int, default=14)
    m = sub.add_parser("mirror"); m.add_argument("--apply", action="store_true")
    sub.add_parser("install")
    args = ap.parse_args()
    return {"check": cmd_check, "push": cmd_push, "audit": cmd_audit,
            "mirror": cmd_mirror, "install": cmd_install}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
