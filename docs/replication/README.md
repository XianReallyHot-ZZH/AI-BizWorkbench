# 复刻总览

本仓库渐进式复刻 [CodexFDE](../../vendors/CodexFDE)（个人研发工作台 + 课程仓库），目标是建成**自己日常可用的工作台**；课程逐讲内容是路径，不是终点。词汇表见根目录 [CONTEXT.md](../../CONTEXT.md)，关键决策见 [docs/adr/](../adr/)。

- **对照基线**：以 [docs/research/codexfde-orientation.md](../research/codexfde-orientation.md) §10 检查点 changelog 为唯一事实源（当前 pin 与历次裁决均见该节，此处不另存快照）；上游变更走检查点显式采纳，见 ADR-0003
- **推进单位**：讲（L00-L16）。每讲一篇复刻版讲义：[docs/lessons/](../lessons/)
- **交付机制**：候选分支 + 具名验收（ADR-0004）。待审核 ≠ 已接受。

## 路线与状态

| 阶段 | 内容 | 对应讲 | 完成标志 | 状态 |
|---|---|---|---|---|
| 0 | 双仓库环境自检 + 版本台账 | L00 | `environment-check` 与 `--product` 双 `ok:true` | 已完成（2026-09-25 验收） |
| 1 | 工作台自举：任务账、命令证据账、项目登记、状态检查 | L01 | 同一测试红转绿 + 自举记录 | 已完成（2026-09-26 验收，F8/F10/F15 裁决在证据账） |
| 2 | 仓库规则 + 六段式 Spec 解析器 | L02-L03 | 歧义 Spec 被拒；越界请求被规则判拒 | 已完成（L02、L03 均于 2026-09-27 验收：规则入 CLAUDE.md，`workbench.cli spec` 结构闸门建成） |
| 3 | V0 受控执行：写集、Diff 摘要、最小 Eval；登记 flowERP（换挡点） | L04 | 候选分支走完具名验收；客户真理可调 | 已完成（2026-09-27 验收：A/B 双票，真实 claude -p 交付，probe 六场景 rc 0；证据账 C1–C15） |
| 支线 | 记忆系统最小闭环（L04 后串行插入；六步口径对齐上游，memory 类全链） | — | 召回→采用→复验→失效证据链走通一次 | 已完成（2026-09-28 验收：15 条合同测试，四链真实任务走通，主账完成五表迁移；证据账 [evidence/S01.md](evidence/S01.md)，方案见 [docs/lessons/支线-记忆系统最小闭环.md](../lessons/支线-记忆系统最小闭环.md)） |
| 支线二 | 工作台看板——验收人的观察窗（L06 前插入；观察窗定位：零 POST、零验收按钮、四屏只读） | — | 起服务真账可读；只读三层实测（mode=ro + 仅 GET + 起停 sha256 一致） | 已完成（2026-09-28 验收：合同 C1–C6 十二用例，复查轮双轴 + 验收期链语义三态修复（真账抓获），94/94 全量绿；证据账 [evidence/S02.md](evidence/S02.md)，方案见 [docs/lessons/支线-工作台看板.md](../lessons/支线-工作台看板.md)） |
| 4 | 质量链：失败优先 Eval → Harness 分级 → Hook → CI | L05-L08 | 本地/远端同一 Eval 身份；L08 收口设上游检查点 | 进行中（L05 已完成：2026-09-28 验收，缺陷基线两连红+同命令绿+冻结指纹+盲区留痕，eval 用例身份 receiving_is_idempotent 已固定；证据账 [evidence/L05.md](evidence/L05.md)。L06 已完成：2026-09-29 验收，evals/harness+report_contract 统一裁判落地、双链红绿+假绿探针+客户 eval 盲区实证，schema 1.0 对齐客户为 L08 铺垫；证据账 [evidence/L06.md](evidence/L06.md)） |
| 5 | 后半程协作与产品化（粒度在 L08 检查点定） | L09-L16 | 见 L08 检查点结论 | 未开始 |
| 重走线 | Java 全量重走 L01–L06（ADR-0006）：每讲候选分支 + 起始红 + 具名验收，golden 对照入验收门 | L01-L06 | 原讲合同逐条在 Java 重新满足 + 对照基准字节级一致 | 进行中（**L01 重走已完成：2026-09-29 验收**——31/31 绿（25 合同 + golden 22 场景字节级一致 + fixture 全字段投影机检），自举四查通过，ImportEvidence 拒收实证；证据账 [evidence/L01-java.md](evidence/L01-java.md)。下一讲 L02 重走待启动） |

原定向文档中的"阶段 1：重建基线链"已被 ADR-0001（vendor 冻结合同）与 ADR-0003（检查点跟进）取代，不再是一条路线阶段。

**重走线说明**（ADR-0006，2026-09-29 验收）：Python 实现冻结为对照基准直至重走完成（完成后 tag 退役）；L01–L06 的 Python 完成标注是历史事实，不回退；L07 顺延至重走完成后。重走证据落 `docs/replication/evidence/LNN-java.md`，原证据账不重开。

## 检查点（阶段边界执行，ADR-0003）

1. 升级 submodule pin，diff 合同 → 显式采纳或保持冻结，记录决定
2. 检查上游新材料/新 tag 是否解锁被推迟的决策
3. 在 `docs/research/codexfde-orientation.md` 追加 changelog

**L08 检查点额外裁定**：上游 L09-L15 讲义是否上架 → 决定后半程逐讲 or 按阶段。

## 证据纪律

- 每讲证据落 `docs/replication/evidence/LNN.md`：命令、完整输出摘要、退出码、失败后权威状态、范围内 Diff 摘要
- AI 自述、绿色截图不构成证据；缺失只能标"待建设"
- 证据文件末尾附**具名验收行**（验收人/日期/结论）；L00 等无代码增量的讲直接落主线，其余随候选分支合入
- vendor 目录必须保持干净：`git -C vendors/CodexFDE status` 随时可查，任何复刻产物不写回上游
