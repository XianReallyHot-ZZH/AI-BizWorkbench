# 讲义导航

每讲一篇复刻版讲义，命名 `LNN-标题.md`。讲义在**讲前 just-in-time 产出**，不预写全量；上游参考讲义（`vendors/CodexFDE/docs/courses/LNN/`）始终作为延伸阅读链接，但其口径是 Codex CLI + 参考仓库路径 + `course-submit` 命令链，与本仓库决策（ADR-0002/0004）有差，以本文档结构产出的复刻版为准。

## 讲义四段结构

1. **讲解**：本讲要什么、为什么现在做、因果交接（本讲为下一讲准备了什么）。改编自参考辅导资料，适配本仓库流程。
2. **本讲合同**：从合同 fixture 引出的本讲验收项（C 编号清单）与绑定 Eval；blocking / observing 标注。
3. **实操流程 + Claude 简报**：候选分支步骤、具体命令（macOS 口径）、预期输出；末尾附一段可直接粘贴给 Claude 的任务简报。
4. **人审清单**：具名验收人看什么——哪个 diff、跑哪些门、什么算作弊（红旗清单）。

## 与课程结构的差异

| 参考仓库 | 本仓库 |
|---|---|
| 每讲 5 件套（README/辅导资料/手册/行动卡/prompts） | 一篇四段讲义（prompts 并入第 3 段，行动卡并入第 4 段） |
| `course-prepare`/`course-submit` 隔离候选 | `lesson-NN` 候选分支（ADR-0004） |
| `--execute-code` 调 Codex | Claude Code 无头执行（ADR-0002） |
| 学习证据（可独立/需提示/尚未达成） | 工程证据（合同测试红绿 + 退出码）+ 具名验收行 |
| slides（不入 Git） | 无；机制图按需用 archify 产 SVG 入讲义 |

## 每讲技能编排

讲义各环节与 Claude Code 技能的对应（到点调用，不预载）。技能分两类：**模型可自调**（我到触发点直接调用）；**需用户显式调用**（`disable-model-invocation: true`，我清单里不可见，你敲 `/mattpocock-skills:<名>` 才生效）。

| 讲义环节 | 技能 | 调用方式 | 说明 |
|---|---|---|---|
| 第 2 段·本讲合同 | `to-spec` | 用户显式 | 把本讲讨论合成正式 spec（含接缝确认 + 用户故事）；发布目标是 issue tracker，本仓库未配置——需先 `/setup-matt-pocock-skills`，或改为落 `docs/`。未用它之前由合同 fixture + 讲义合同段承担 |
| 第 3 段·执行（L01 起） | `implement` | 用户显式 | 按本讲合同/票执行实现 |
| 第 3 段·核心循环 | `tdd` | 模型自调 | 起始红 → 实现转绿是每讲核心循环 |
| 第 3 段·模块设计 | `codebase-design` | 模型自调 | 任务账、写集检查器、执行器合同等接口设计 |
| 执行遇障 | `diagnosing-bugs` | 模型自调 | eval 失败、环境异常的诊断循环 |
| 第 4 段·人审 | `code-review` | 模型自调 | 具名验收前对候选 diff 的双轴复查 |
| 检查点（阶段边界） | `research` + `improve-codebase-architecture` | 前者自调 / 后者用户显式 | 调研上游材料现状；对自建代码扫描深化机会 |
| 讲次过渡（换会话时） | `handoff` | 用户显式 | 会话压缩成交接文档，替代裸 `/clear` |
| 深入理解某讲机制 | `teach` | 用户显式 | 互动式教学，补充讲义 | 
| L02 讲 | `writing-for-agents` | 模型自调 | 给工作台写 AGENTS.md |

注：`to-tickets` / `triage` / `wayfinder` 依赖 issue tracker，配置 tracker 前不进入编排。

## 已发布

- [L00-环境自检.md](L00-环境自检.md)
