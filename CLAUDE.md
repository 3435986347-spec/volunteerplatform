# CLAUDE.md

给 Claude Code 的入口。**规矩与路由只写在 `AGENTS.md` 一处**（Codex 等其他 agent 读的也是它），这里整份导入，两边不会各说各的：

@AGENTS.md

## 只对 Claude Code 成立的

- **模块页自动导入**：`代码/hengde-volunteer-*/CLAUDE.md` 各有一行 `@../../知识库/模块/<模块>.md`，动到某个模块的文件时会自动读入那一页。Codex 不支持 `@` 导入，所以同目录另有一份 `AGENTS.md` 写明「先读哪一页」。
- 改了 `AGENTS.md` 不用同步本文件；只有「仅对 Claude Code 成立」的内容才写在这里。
- 知识库目录里的 `知识库/CLAUDE.md` 同理导入 `知识库/AGENTS.md`（维护知识库本身的规矩）。
