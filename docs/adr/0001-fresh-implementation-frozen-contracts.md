---
status: accepted
date: 2026-09-25
---

# 0001 - AI-BizWorkbench 顶层全新实现，vendor 冻结的课程合同为测试基线

复刻 CodexFDE 时，我们在 AI-BizWorkbench 顶层全新实现工作台（包名与参考同名：`workbench/`、`eval/`、`agent/`），并把上游 `course_mainline.py` 的 16 讲 LESSONS 合同 vendor 进本仓库作为**冻结基线**的 TDD fixture——每讲先让合同测试红、再实现到绿。不使用课程原生的 `course-prepare`/`course-submit` 隔离候选机器。

**Why**: 两个上游仓库是 submodule，钉在上游 commit 上；fork 式地在其内部重建会把复刻成果困在 vendor 目录里，还得维护 GitHub fork 才能推送。合同 fixture 保住了"逐讲有机器可验合同"这一课程机器门闩的核心价值，丢掉的只是隔离工作区机制——其同形物由 ADR-0004 以候选分支重建。

**Considered**: (a) 在 vendors/CodexFDE 内 fork 式重建——原生工具链全保留，但成果与参考实现混同、无法独立推送；(b) 混合式（实现在本仓库、同时在 vendor 里养一套基线链当外部审计器）——双倍维护成本，收益存疑。

**Consequences**: 上游合同变更不会自动生效，必须走 ADR-0003 的显式采纳；"replicated against CodexFDE@58f4612" 是本仓库合同的声明基线。
