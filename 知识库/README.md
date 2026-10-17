# 恒德志愿者平台 · 知识库（给 coding agent 看的）

雷州市恒德爱心公益协会志愿者管理平台（微信小程序 + 后台控制台 + Spring Boot 4 模块化单体）的知识库：业务口径、协会决策、需求原文、模块的代码不变式与踩坑、从代码生成的接口 / 权限点 / 表 / 迁移清单。

- **源头**在文档仓库（GitHub `volunteerplatform`）的 `知识库/` 目录；**发布**为独立仓库 Gitee `huang-mingzhang/henge-volunteerplatform-knowlegebase`（每次发布一个快照提交，见文末）。在发布仓库里直接改的内容要回到源头，否则下次发布会被覆盖。
- 页里写的 `代码/…`、`文档/…`、`tools/…`、`部署/…` 路径**相对文档仓库根目录**；单独克隆本仓库时它们指向另外几个仓库。

## 目录

| 目录 / 页 | 放什么 | 怎么维护 |
|---|---|---|
| [`领域/`](领域/) | 12 个业务领域各一页：现行规则、功能与状态、协会决策、需求出处、接口与代码、已知限制与待办 | 手写；**按业务说的任务从这里进** |
| [`模块/`](模块/) | 14 个后端模块各一页：**现行要点**（现在是什么）在上，**按批次的记录**（为什么、撞过什么）在下 | 手写；改代码前读 |
| [`决策/协会决策.md`](决策/协会决策.md) | 协会五次答复与需方拍板，统一编号 **XH01…**，附「原编号 → XH 号」对照表 | 手写；新答复追加新号 |
| [`需求/需求表.md`](需求/需求表.md)、[`需求/原型.md`](需求/原型.md) | 需求表按 Row、原型 PDF 按页转出的文字（手机号已打码） | `tools/convert_requirements.py` 生成 |
| [`生成/`](生成/) | 迁移、权限点、表、按模块的接口索引 | `tools/gen_knowledge_base.py` 生成，**不手改** |
| [`横切/`](横切/) | 跨模块套路：并发与锁、积分与排行口径、通知与处置、测试纪律、依赖与部署、编码约定 | 手写 |
| [`跨端/后台控制台.md`](跨端/后台控制台.md) | 控制台仓库的组件坑与联调方式 | 手写 |
| [`变更日志.md`](变更日志.md) | 涉及多个模块的批次记录 | 手写；新批次追加在最上面 |

## 各 agent 怎么加载（Claude Code 与 Codex 都有入口）

**每个入口目录都成对放 `AGENTS.md` 与 `CLAUDE.md`**：`AGENTS.md` 写内容（Codex、Cursor 等读它），`CLAUDE.md` 给 Claude Code，用 `@` 导入 `AGENTS.md` 或知识库页——内容只写一处，两种 agent 读到的一致。

| 在哪儿开 agent | Claude Code 读到 | Codex 读到 |
|---|---|---|
| 文档仓库根 `volunteerplatform/` | `CLAUDE.md` → `@AGENTS.md`（总入口：硬规矩 + 路由） | `AGENTS.md`（同一份总入口） |
| 后端仓库 `代码/`（自身是 git 根） | 上级 `../CLAUDE.md` 已导入总入口；`代码/CLAUDE.md` 只补一句单独克隆时怎么办 | `代码/AGENTS.md`：总入口按后端路径改写的**生成副本**（Codex 以 git 根为项目根，读不到上级那份） |
| 后端某个模块 `代码/hengde-volunteer-<m>/` | 模块 `CLAUDE.md` 一行 `@../../知识库/模块/<m>.md`，**动到这个模块时自动读入** | 模块 `AGENTS.md`：写明「改这个模块前先读 `../../知识库/模块/<m>.md`」（Codex 不支持 `@` 导入；按 AGENTS.md 约定，改到某目录下的文件时要遵守该目录的 AGENTS.md） |
| 小程序 `volunteerplatform-miniprogram/`、控制台 `volunteerplatform-back/` | `CLAUDE.md` → `@AGENTS.md` | `AGENTS.md`：仓库自己的要点 + 指向知识库的哪几页 |
| 知识库本身（`知识库/` 或发布仓库根） | `CLAUDE.md` → `@AGENTS.md` | `AGENTS.md`：维护知识库的规矩 |

Codex 的几点：

- 它把 git 根到当前目录这一路上的 `AGENTS.md` 依次拼起来；默认总长上限 32 KiB（`project_doc_max_bytes`），所以总入口控制在 16 KB 左右，正文都放知识库页里按需读。
- 不支持 `@路径` 导入：入口里写「先读 `…/xxx.md`」，它会按路径去读。
- 每个入口目录都已有 `AGENTS.md`，**不需要**在 `~/.codex/config.toml` 里配 `project_doc_fallback_filenames = ["CLAUDE.md"]`（配了也只会多读一份只含导入行的文件）。

## 目录约定（单独克隆时）

```
<工作区>/
├── volunteerplatform/                文档仓库：知识库/ 就在这里（完整工作区，推荐）
│   └── 代码/                         后端仓库
├── volunteerplatform-miniprogram/    其 AGENTS.md 找 ../volunteerplatform/知识库/，找不到再找 ../henge-volunteerplatform-knowlegebase/
└── volunteerplatform-back/           同上
```

- 只克隆了**后端**：把本仓库克隆到与后端仓库同级、名为 `知识库` 的目录（`git clone <本仓库地址> 知识库`），模块入口里的 `../../知识库/模块/…` 才成立。
- 只克隆了**小程序 / 控制台**：把本仓库克隆到同级的 `henge-volunteerplatform-knowlegebase/`（默认目录名）即可。

## 维护（在文档仓库根运行）

```bash
python3 tools/gen_knowledge_base.py          # 重写 生成/（--check 只比对）
python3 tools/convert_requirements.py        # 需求原件变了时重转 需求/
python3 tools/gen_agent_entries.py           # 根 AGENTS.md 改了之后，重新生成后端仓库里的入口（--check 只比对）
python3 tools/verify_knowledge_base.py       # 页里点名的类 / 方法 / 路径 / 配置键 / 权限点 / 迁移号 / XH 号 / 页内链接是否都还在
tools/publish_knowledge_base.sh              # 校验 + 敏感信息扫描通过后，把 知识库/ 作为一个快照提交推到 Gitee 知识库仓库
```

**不进知识库的**：密钥与 AppSecret、服务器地址与登录方式、线上安全状况（如《线上升级核对》）、真实手机号与身份证号。这些留在文档仓库或部署人员手里。
