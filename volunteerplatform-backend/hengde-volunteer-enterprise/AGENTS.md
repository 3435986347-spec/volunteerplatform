<!-- 生成物：由文档仓库的 tools/gen_agent_entries.py 生成，不要手改。 -->

# hengde-volunteer-enterprise

爱心企业账号、赞助商品、企业积分、企业帖、评价。

**改这个模块的任何文件之前，先读 `../../volunteerplatform-knowlegebase/模块/enterprise.md`**（本模块的现行要点、关键类、承重测试、踩过的坑）。Codex 不支持 `@` 导入，所以只能按路径去读；Claude Code 经同目录的 `CLAUDE.md` 自动读入同一页。

- 业务口径与功能状态：`../../volunteerplatform-knowlegebase/领域/`（按业务领域，不按模块）
- 本模块的接口清单：`../../volunteerplatform-knowlegebase/生成/接口/enterprise.md`（脚本生成）
- 跨模块的套路（并发与锁、积分口径、通知与处置、测试纪律）：`../../volunteerplatform-knowlegebase/横切/`
- 总入口（硬规矩、构建命令、URL 约定、鉴权白名单）：`../AGENTS.md`

改完这个模块后，在同一批里更新 `../../volunteerplatform-knowlegebase/模块/enterprise.md` 的现行要点与按批次的记录（`../AGENTS.md`「开工协议」第 5 条）。提交闸门（`../../tools/kb_gate.py`）会拒绝改了本模块 `src/main` 却没动这一页的提交；不要用 `--no-verify` 绕过。
