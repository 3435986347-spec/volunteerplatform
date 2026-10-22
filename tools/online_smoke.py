#!/usr/bin/env python3
"""线上只读冒烟：发版后不登录、不写库，确认服务器上跑的是新包、鉴权没漏、没有接口一碰就 500。

回归验证第 6 层（`知识库/回归验证.md`）里不需要任何账号的那一半；要登录、会写库的验收用
`tools/miniprogram_acceptance.py`（要你自己的 token，且会在目标库里建数据）。

**只发 GET**（外加一个 HEAD），不带任何 token。检查六类：

  1. 版本    `/actuator/info` 报出构建时间与迁移号；迁移号要等于本地代码里的最大号；不许带连接串与口令；
             报了提交号（2026-10-18 起的包）时，打包时工作区要干净、给了 --expect-commit 就比对
  2. 探针    不存在的路径 404（旧包是 500）；志愿者证核验、文件分享免登录（未知令牌回业务错误，不是 401 / 5xx）；
             支付与快递100 回调路由没被登录拦截（GET 回 405，不是 401）
  3. 鉴权    代码里的**每一个** GET 端点匿名打一遍：`/v` `/a` `/e` 下除公开白名单外必须 401；白名单与其他公开路径不许 401
  4. 不崩    以上任何一次都不许 5xx
  5. 控制台  `/assets/api.js` 200、首页带着几十个页面脚本（只有个位数说明还是旧的 index.html）
  6. 文档    `/api/doc.html` 应是 403（不是就提示：nginx 还没加屏蔽规则）

端点清单从本地 `代码/` 的 Controller 解析（与 `tools/gen_knowledge_base.py` 同一套），所以**本地代码要与线上包是同一版**——
脚本会把线上的构建时间与提交号打出来，与发版手册第 1.1 步的值、本地 `代码/` 的 HEAD 对一下。公开白名单从 `SaTokenConfigure` 的 `notMatch` 里读。

用法（文档仓库根）：

    python3 tools/online_smoke.py --base http://<服务器>/api
    python3 tools/online_smoke.py --base http://<服务器>/api --expect-build-time 2026-10-17T07:49:47.599Z --report smoke.md
    python3 tools/online_smoke.py --base http://<服务器>/api --expect-commit "$(git -C 代码 rev-parse HEAD)"

退出码：0 = 全部符合；1 = 有不符合的；2 = 用法 / 连不上。
"""

from __future__ import annotations

import argparse
import json
import re
import socket
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_knowledge_base as gen  # noqa: E402
import verify_url_contract as contract  # noqa: E402

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

MISSING_ID = "999999999"
SECRETS = ("jdbc:", "password", "secret", "access-key")
# 默认直连、不走环境变量里的 HTTP_PROXY：本机挂着代理时，经代理每个请求要多 2–4 秒，280 个端点能拖到超时
# （2026-10-17 实测）。要走代理用 --use-proxy。
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
_NOT_MATCH = re.compile(r'SaRouter\.match\("(/[vae])/\*\*"\)\s*\.notMatch\(((?:\s*"[^"]+"\s*,?)+)\)', re.S)


def fetch(url: str, method: str = "GET", timeout: float = 20.0, retries: int = 1):
    """→ (http 状态, 业务 code 或 None, 正文前 160 字)。不带凭据。

    超时 / 连接断开记为状态 0（「没有应答」也是要报的结果，不能让一个慢接口把整轮冒烟弄崩），先重试一次。
    """
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "hengde-online-smoke/1"})
    try:
        with OPENER.open(req, timeout=timeout) as r:
            status, body = r.status, r.read(65536)
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read(65536)
    except (urllib.error.URLError, socket.timeout, ConnectionError, OSError) as e:
        if retries > 0:
            time.sleep(1.0)
            return fetch(url, method, timeout, retries - 1)
        return 0, None, f"没有应答（{type(e).__name__}: {e}）"
    text = body.decode("utf-8", errors="replace")
    code = None
    try:
        code = json.loads(text).get("code")
    except (ValueError, AttributeError):
        pass
    return status, code, text[:160].replace("\n", " ")


def public_paths(code_root: Path) -> set[str]:
    src = (code_root / "hengde-volunteer-api/src/main/java/com/hengde/api/config/SaTokenConfigure.java").read_text(
        encoding="utf-8")
    src = contract.strip_java_comments(src)
    out: set[str] = set()
    for m in _NOT_MATCH.finditer(src):
        out.update(re.findall(r'"([^"]+)"', m.group(2)))
    return out


def max_migration(code_root: Path) -> int:
    mig = code_root / "hengde-volunteer-common/src/main/resources/db/migration"
    return max(int(re.match(r"V(\d+)", f.name).group(1)) for f in mig.glob("V*__*.sql"))


class Run:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str, bool]] = []   # (检查, 对象, 结果, 是否符合)

    def check(self, what: str, target: str, result: str, ok: bool) -> None:
        self.rows.append((what, target, result, ok))
        if not ok:
            print(f"✗ {what}  {target}  {result}")

    @property
    def failures(self) -> list:
        return [r for r in self.rows if not r[3]]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--base", required=True, help="接口基址，含 context-path，如 http://host/api")
    ap.add_argument("--root", default=".", help="文档仓库根（读 代码/ 里的端点清单）")
    ap.add_argument("--delay", type=float, default=0.15, help="两次请求之间的间隔秒数（别压线上）")
    ap.add_argument("--expect-build-time", help="发版手册第 1.1 步打出来的 build.time，给了就逐字比对")
    ap.add_argument("--expect-commit", help="打包所用的后端提交号（完整或前缀），给了就与 /actuator/info 的 git.commit.id.full 比对")
    ap.add_argument("--report", help="把逐条结果写成 markdown")
    ap.add_argument("--use-proxy", action="store_true", help="走环境变量里的 HTTP_PROXY（默认直连）")
    args = ap.parse_args()
    if args.use_proxy:
        global OPENER
        OPENER = urllib.request.build_opener()
    base = args.base.rstrip("/")
    origin = re.sub(r"/api$", "", base)
    code_root = Path(args.root).resolve() / "代码"
    if not code_root.is_dir():
        print("找不到 代码/——请在文档仓库根运行或用 --root 指定", file=sys.stderr)
        return 2
    run = Run()

    # 1. 版本
    status, _, _ = fetch(base + "/actuator/info")
    if status == 0:
        print(f"连不上 {base}", file=sys.stderr)
        return 2
    try:
        info_text = OPENER.open(base + "/actuator/info", timeout=20).read().decode("utf-8")
        info = json.loads(info_text)
    except (urllib.error.URLError, OSError, ValueError):
        info_text, info = "", {}
    build_time = info.get("build", {}).get("time", "")
    schema = info.get("database", {}).get("schemaVersion", "")
    run.check("版本", "/actuator/info", f"HTTP {status}，build.time {build_time}，schemaVersion {schema}", status == 200)
    local_max = str(max_migration(code_root))
    run.check("版本", "迁移号 = 本地代码最大号", f"线上 {schema} / 本地 {local_max}", schema == local_max)
    if args.expect_build_time:
        run.check("版本", "build.time = 发版手册", f"线上 {build_time} / 期望 {args.expect_build_time}",
                  build_time == args.expect_build_time)
    # git 一节只有从带 .git 的目录打的包才有内容、否则是空的 {}（api 的 pom 里 git-commit-id 插件，2026-10-18 起）
    git = info.get("git") if isinstance(info.get("git"), dict) else {}
    commit_id = (git.get("commit") or {}).get("id") if isinstance(git.get("commit"), dict) else None
    commit = (commit_id.get("full") or "") if isinstance(commit_id, dict) else str(commit_id or "")
    if git:
        dirty = str(git.get("dirty", "")).lower()
        run.check("版本", "打包时工作区干净", f"git.dirty {dirty}（true 说明包里有没提交的改动）", dirty == "false")
    if args.expect_commit:
        run.check("版本", "提交号 = 期望", f"线上 {commit[:12] or '没报（打包时没有 .git，或是加这一项之前的包）'}"
                  f" / 期望 {args.expect_commit[:12]}", bool(commit) and commit.startswith(args.expect_commit))
    leaked = [s for s in SECRETS if s in info_text.lower()]
    run.check("版本", "/actuator/info 不带口令", "无" if not leaked else f"出现 {leaked}", not leaked)

    # 2. 探针
    probes = [
        ("/__no_such_endpoint__", lambda s, c: s == 404, "404（旧包是 500）"),
        ("/v/user/volunteer-cards/verify?token=probe", lambda s, c: s != 401 and s < 500, "免登录，业务错误"),
        ("/share/files/probe", lambda s, c: s != 401 and s < 500, "免登录，业务错误"),
        ("/callback/trade/wechat/pay", lambda s, c: s == 405, "路由未被登录拦截（GET 回 405）"),
        ("/callback/trade/wechat/refund", lambda s, c: s == 405, "同上"),
        ("/callback/logistics/kuaidi100", lambda s, c: s == 405, "同上"),
    ]
    for path, ok, expect in probes:
        s, c, body = fetch(base + path)
        run.check("探针", path, f"HTTP {s} code {c}（期望 {expect}）", ok(s, c))
        time.sleep(args.delay)

    # 3 + 4. 全部 GET 端点匿名打一遍
    public = public_paths(code_root)
    eps = gen.endpoints(code_root, contract.load_permission_constants(code_root))
    gets = sorted({p for rows in eps.values() for (verb, p, *_rest) in rows if verb == "GET"})
    swept = protected_ok = public_ok = 0
    slow: list = []
    for pattern in gets:
        url_path = re.sub(r"\{[^}]+}", MISSING_ID, pattern)
        t0 = time.monotonic()
        s, c, body = fetch(f"{base}{url_path}?page=1&size=10")
        took = time.monotonic() - t0
        if took > 3:
            slow.append((pattern, took, s))
            print(f"… 慢：{pattern} 用了 {took:.1f}s（HTTP {s}）", flush=True)
        swept += 1
        if s == 0:
            run.check("不崩", pattern, body, False)
        elif s >= 500 or (isinstance(c, int) and c >= 500):
            run.check("不崩", pattern, f"HTTP {s} code {c} {body}", False)
        elif pattern[:3] in ("/v/", "/a/", "/e/") and pattern not in public:
            ok = s == 401
            protected_ok += ok
            if not ok:
                run.check("鉴权", pattern, f"匿名得到 HTTP {s} code {c}（应 401）{body}", False)
        else:
            ok = s != 401
            public_ok += ok
            if not ok:
                run.check("鉴权", pattern, f"公开端点要求登录：HTTP {s}", False)
        time.sleep(args.delay)
    run.check("性能", "匿名请求超过 3 秒的", "无" if not slow else "；".join(f"{p} {t:.1f}s" for p, t, _ in slow),
              not slow)
    run.check("鉴权", f"匿名扫 {swept} 个 GET 端点", f"受保护的 {protected_ok} 个 401，公开的 {public_ok} 个放行，"
              f"其余 {swept - protected_ok - public_ok} 个见上面的明细", swept == protected_ok + public_ok)

    # 5. 控制台静态资源
    s, _, _ = fetch(origin + "/assets/api.js", method="HEAD")
    run.check("控制台", "/assets/api.js", f"HTTP {s}", s == 200)
    try:
        page = OPENER.open(origin + "/", timeout=15).read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, OSError):
        page = ""
    n = page.count("assets/page-")
    run.check("控制台", "首页页面脚本数", f"{n} 个", n >= 10)

    # 6. 接口文档
    s, _, _ = fetch(base + "/doc.html")
    run.check("文档", "/api/doc.html", f"HTTP {s}（应 403）", s == 403)

    total, bad = len(run.rows), len(run.failures)
    print(f"线上 {base}：build.time {build_time}，提交 {commit[:12] or '没报'}，迁移 V{schema}；匿名扫了 {swept} 个 GET 端点")
    print("✓ 全部符合" if not bad else f"✗ {bad} 项不符合（共 {total} 项汇总检查）")
    if args.report:
        lines = [f"# 线上只读冒烟 · {time.strftime('%Y-%m-%d %H:%M')}", "", f"基址 `{base}`", "",
                 "| 检查 | 对象 | 结果 | 符合 |", "|---|---|---|---|"]
        for what, target, result, ok in run.rows:
            lines.append(f"| {what} | `{target}` | {result.replace('|', '/')} | {'✓' if ok else '✗'} |")
        Path(args.report).write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"明细写在 {args.report}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
