---
status: accepted
date: 2026-09-25
---

# 0003 - 上游检查点驱动升级，合同 fixture 显式采纳

上游 CodexFDE 处于活跃开发期（2026-09 达 60 commits/月，日更 `docs(course)`）。本决定：submodule pin **只在阶段边界的检查点升级**，不在日常跟进；每次升级做三件事——① diff `course_mainline.py` 等合同，决定 vendor fixture 是否**显式采纳**（默认冻结，不静默跟随）；② 检查新材料/新 tag 是否解锁被推迟的决策（如 L09-L15 讲义、基线链）；③ 在 `docs/research/codexfde-orientation.md` 追加 changelog。

**Why**: 合同漂移是复刻的正确性风险（fixture 是 TDD 起点），材料上架是计划风险（解锁后半程粒度决策），两者都值得在检查点批量处理；每周定期升级的频率高过这两个风险的变化率，完全冻结则放弃免费获得的课程更新。

**Considered**: (a) 每周定期升级——开销与风险不匹配；(b) 冻结到项目结束——主动放弃上游演化（尤其 L09-L15 讲义可能上架）。

**Consequences**: 后半程（L09+）粒度在 L08 检查点按当时上游材料事实再定；每个检查点应留下记录，缺失记录视为未升级。
