---
status: accepted
date: 2026-09-25
---

# 0004 - 每讲候选分支 + 具名验收，复刻 course-submit 的 review 门

课程机制：`course-prepare`（隔离候选）→ 实现 → `course-submit`（停在 `review` 态，待审核 ≠ 已接受）→ 具名人工审核。本复刻的同形物：每讲开 `lesson-NN` **候选分支**，commit 1 只放合同测试（起始红）与证据，commit 2 放实现（后绿）；全部验收门通过后产出证据摘要与 diff，由用户**具名验收**后 merge 回 master。合并信息即验收记录。

**Why**: 复刻主体是"Claude 实现、用户具名验收"（对应课程的 HITL 人审）。没有独立的候选载体，review 会退化成事后追认的橡皮图章；课程的红线"AI 自述完成 ≠ 完成"需要一个机器可验证的流程载体（前红 commit → 后绿 commit 的历史就是证据链）。

**Considered**: (a) 主线直推 + 证据账——最省事但丢失待审核状态；(c) GitHub PR 流——评审记录更正式，但 17 讲 17 个 PR 对单人仓库偏重。

**Consequences**: L08 落 CI 时可平滑升级为 PR 流（分支→PR 只换发布方式，机制不变）；L00 这类无代码增量的讲不走候选分支，证据直接落主线 `docs/replication/evidence/`。
