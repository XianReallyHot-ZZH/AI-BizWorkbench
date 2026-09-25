---
status: accepted
date: 2026-09-25
---

# 0002 - Claude Code 无头执行器，交付合同形状与课程同形

课程参考实现用 Codex CLI（`codex exec --json --sandbox workspace-write`）作为执行器。本复刻改用 Claude Code 无头模式（`claude -p` + JSON 输出），但执行器受约束的**合同形状保持同形**：写集、预算、JSON 事件流、沙箱边界、退出码。

**Why**: 这门课教的是"执行器被合同约束"，不是 Codex 本身；用户日常主力是 Claude Code，复刻出的工作台要为这个环境服务。Codex CLI（0.147.0）虽已安装，但仅用于对照行为。

**Considered**: (a) 沿用 Codex CLI——参考实现零改动，但复刻产物日常用不上；(c) Executor 抽象层 + 双适配器——在只有一个真实执行器需求时是过早抽象。

**Consequences**: `--sandbox workspace-write` 与写集检查需映射到 Claude Code 的权限机制（`--permission-mode` / hooks），语义对齐是复刻中第一个非平凡适配点；L10-L12 的 Loop/Graph 讲依赖此适配先行落地。
