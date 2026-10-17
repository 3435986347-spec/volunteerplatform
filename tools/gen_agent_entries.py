#!/usr/bin/env python3
"""生成后端仓库里给 agent 的入口文件：`代码/AGENTS.md`、`代码/CLAUDE.md`，以及 14 个模块目录各一对 `AGENTS.md` / `CLAUDE.md`。

为什么要生成而不是手写：
  · Codex 以 git 根为项目根。`代码/` 自己是一个 git 仓库，Codex 在里面工作时**读不到上级的 `../AGENTS.md`**，
    所以 `代码/AGENTS.md` 必须是一份完整的总入口——但手抄一份迟早和根上那份各说各的。这里从根 `AGENTS.md`
    改写路径生成（`知识库/` → `../知识库/`，`代码/hengde-volunteer-parent` → `hengde-volunteer-parent`……）。
  · Claude Code 支持 `@路径` 导入，模块目录的 `CLAUDE.md` 一行导入对应的知识库模块页，动到这个模块时自动读入；
    Codex 不支持导入，模块目录的 `AGENTS.md` 只能写明「先读哪一页」。两份都从同一张模块表生成。
  · `代码/CLAUDE.md` 不导入任何东西：完整工作区里上级 `../CLAUDE.md` 已导入总入口，再导一份同样的内容只是重复；
    单独克隆后端时它让 Claude Code 去读同目录的 `AGENTS.md`。

用法（在文档仓库根运行）：

    python3 tools/gen_agent_entries.py           # 写入
    python3 tools/gen_agent_entries.py --check   # 只比对；有过期的退出码 1

写进的是后端仓库（`代码/`），改完要在后端仓库与文档仓库各提交一次。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

MODULES = {
    "common": "公共基础设施、全项目唯一的迁移序列",
    "auth": "三套登录态、实名、PII、处置表与闸门、通知短信入口、注销冷静期",
    "organization": "子账号与权限点、小组、分队、组织架构、管理团队、问卷引擎、临时负责人考试",
    "publicity": "轮播图、公告、文件下载",
    "activity": "活动、报名、签到、时长、名单公示、相册、积分账本",
    "trade": "微信支付收付、退款、对账（未真实调用过）",
    "donate": "积分商城、捐书与物资流转、微心愿、结对、众筹、捐款与发票、物流",
    "user": "志愿者管理、我的资料、地址、年级、志愿者证",
    "honor": "排行榜、勋章与榜样、电子证书、奖惩与申诉",
    "social": "社区、审核与风控、举报、禁言、私信",
    "enterprise": "爱心企业账号、赞助商品、企业积分、企业帖、评价",
    "data": "数据看板与汇总、投诉建议",
    "system": "操作日志、水印、菜单排序、编号、文件网盘",
    "api": "启动与全局配置、鉴权路由、Jackson、上传、webhook 控制器",
}

# 根 AGENTS.md 里的路径 → 相对 `代码/` 的路径。只改行内代码与命令里的写法，工作区示意图（代码块）原样保留。
REWRITES = [
    ("`知识库/", "`../知识库/"),
    ("`文档/", "`../文档/"),
    ("`部署/", "`../部署/"),
    ("`tools/", "`../tools/"),
    ("python3 tools/", "python3 ../tools/"),
    ("`代码/hengde-volunteer-parent/`", "`hengde-volunteer-parent/`"),
    ("cd 代码/hengde-volunteer-parent", "cd hengde-volunteer-parent"),
    ("经根 `CLAUDE.md` 的 `@AGENTS.md` 读到同一份", "在完整工作区里经文档仓库根 `CLAUDE.md` 读到同一份"),
    ("本文件只在规矩或路由变了时才改", "文档仓库根 `AGENTS.md` 只在规矩或路由变了时才改"),
]

GENERATED = "<!-- 生成物：由文档仓库的 tools/gen_agent_entries.py 生成，不要手改。{what} -->\n\n"

BACKEND_NOTE = """> **这一份是后端仓库的入口**：由文档仓库根 `AGENTS.md` 改写路径生成（Codex 以本仓库为项目根时读不到上级那份）。
> 路径都相对本目录：`../知识库/` 是知识库，`../tools/`、`../文档/`、`../部署/` 只在完整工作区（本仓库位于文档仓库的 `代码/` 下）里有。
> **单独克隆后端时**，把知识库仓库（Gitee `henge-volunteerplatform-knowlegebase`）克隆到与本仓库同级、名为 `知识库` 的目录。

"""


def backend_agents(root_agents: str) -> str:
    lines = root_agents.split("\n")
    out, in_fence = [], False
    for line in lines:
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
        if not in_fence or "cd 代码/" in line:
            for old, new in REWRITES:
                line = line.replace(old, new)
        out.append(line)
    text = "\n".join(out)
    # 标题下第一段之后插入说明
    head, rest = text.split("\n\n", 1)
    return (GENERATED.format(what="源头是文档仓库根 AGENTS.md。") + head + "\n\n" + BACKEND_NOTE + rest)


BACKEND_CLAUDE = GENERATED.format(what="") + """# CLAUDE.md（后端仓库）

- **完整工作区里**（本仓库是文档仓库的 `代码/`）：上级 `../CLAUDE.md` 已经导入了总入口 `../AGENTS.md`，这里不重复导入。
- **单独克隆后端仓库时**：上级没有总入口——开始任何任务前先读同目录的 `AGENTS.md`（总入口按本仓库路径改写的副本），
  并把知识库仓库克隆到同级的 `知识库/` 目录。
- 动到某个模块时，那个模块目录的 `CLAUDE.md` 会自动导入 `../../知识库/模块/<模块>.md`。
"""


def module_claude(module: str, desc: str) -> str:
    return (GENERATED.format(what="") + f"# hengde-volunteer-{module}\n\n"
            f"{desc}。本模块的现行要点与按批次的记录在知识库 `知识库/模块/{module}.md`，下面这行会自动导入"
            f"（完整工作区里成立；单独克隆后端时要把知识库仓库克隆到与后端同级的 `知识库/` 目录）：\n\n"
            f"@../../知识库/模块/{module}.md\n")


def module_agents(module: str, desc: str) -> str:
    return (GENERATED.format(what="") +
            f"# hengde-volunteer-{module}\n\n"
            f"{desc}。\n\n"
            f"**改这个模块的任何文件之前，先读 `../../知识库/模块/{module}.md`**（本模块的现行要点、关键类、承重测试、踩过的坑）。"
            f"Codex 不支持 `@` 导入，所以只能按路径去读；Claude Code 经同目录的 `CLAUDE.md` 自动读入同一页。\n\n"
            f"- 业务口径与功能状态：`../../知识库/领域/`（按业务领域，不按模块）\n"
            f"- 本模块的接口清单：`../../知识库/生成/接口/{module}.md`（脚本生成）\n"
            f"- 跨模块的套路（并发与锁、积分口径、通知与处置、测试纪律）：`../../知识库/横切/`\n"
            f"- 总入口（硬规矩、构建命令、URL 约定、鉴权白名单）：`../AGENTS.md`\n\n"
            f"改完这个模块后，按 `../AGENTS.md`「维护规则」更新 `知识库/模块/{module}.md` 的现行要点与按批次的记录。\n")


def generate(root: Path) -> dict[Path, str]:
    code = root / "代码"
    files: dict[Path, str] = {}
    files[code / "AGENTS.md"] = backend_agents((root / "AGENTS.md").read_text(encoding="utf-8"))
    files[code / "CLAUDE.md"] = BACKEND_CLAUDE
    for m, desc in MODULES.items():
        d = code / f"hengde-volunteer-{m}"
        files[d / "CLAUDE.md"] = module_claude(m, desc)
        files[d / "AGENTS.md"] = module_agents(m, desc)
    return files


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    if not (root / "AGENTS.md").exists() or not (root / "代码").is_dir():
        print("请在文档仓库根目录运行（要有 AGENTS.md 与 代码/）", file=sys.stderr)
        return 2
    missing = [m for m in MODULES if not (root / "代码" / f"hengde-volunteer-{m}").is_dir()]
    if missing:
        print(f"模块目录不存在：{missing}——新增 / 改名模块时同步本脚本的 MODULES", file=sys.stderr)
        return 2
    files = generate(root)
    stale = [p for p, t in files.items() if not p.exists() or p.read_text(encoding="utf-8") != t]
    if args.check:
        for p in stale:
            print(f"过期：{p.relative_to(root)}")
        if stale:
            print("✗ agent 入口与根 AGENTS.md 不一致，跑一次 python3 tools/gen_agent_entries.py")
            return 1
        print(f"✓ {len(files)} 个 agent 入口文件是最新的")
        return 0
    for p, t in files.items():
        p.write_text(t, encoding="utf-8")
    print(f"写了 {len(files)} 个文件（{len(stale)} 个有变化）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
