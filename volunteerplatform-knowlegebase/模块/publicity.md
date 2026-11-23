# publicity 模块 · 信息公示

> 代码：`代码/hengde-volunteer-publicity/`。本页由根 `CLAUDE.md` 的「已完成」表下沉而来（2026-10-17）；
> 上半部分是**现行要点**，下半部分是按批次的原始记录（为什么这样做、撞过什么）。两者冲突时以上半部分与代码为准，并顺手修正下半部分。

## 职责

轮播图、公告、文件下载。

## 现行要点

- 志愿者端只读已上架 / 已发布的；公告详情按 `status=1` 过滤，防草稿泄露。
- 管理端权限点：`pub:banner` / `pub:announcement` / `pub:file`。
- 内置文件的「定时开放」在 system 模块的网盘里（V83），不往 `publicity_file` 同步。

## 按批次的记录

**已完成**：轮播图/公告/文件下载（`PublicityService`）。管理端（`/a/publicity`，`@SaCheckPermission` pub:banner/announcement/file）增改删 + 排序 + 开关下载；志愿者端（`/v/publicity`）只读已上架/已发布（公告详情按 `status=1` 过滤防草稿泄露）。表在 V6；权限点在 V2 种子。无领域内迁移——脚本集中 common
