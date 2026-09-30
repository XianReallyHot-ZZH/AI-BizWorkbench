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
| 4 | 质量链：失败优先 Eval → Harness 分级 → Hook → CI | L05-L08 | 本地/远端同一 Eval 身份；L08 收口设上游检查点 | 进行中（L05 已完成：2026-09-28 验收，缺陷基线两连红+同命令绿+冻结指纹+盲区留痕，eval 用例身份 receiving_is_idempotent 已固定；证据账 [evidence/L05.md](evidence/L05.md)。L06 已完成：2026-09-29 验收，evals/harness+report_contract 统一裁判落地、双链红绿+假绿探针+客户 eval 盲区实证，schema 1.0 对齐客户为 L08 铺垫；证据账 [evidence/L06.md](evidence/L06.md)。L07 已完成：2026-09-30 验收，**首个无 Python 先例的讲（走新讲机制，无 golden）**——Codex Hooks→Claude Code Stop hook 映射（协议官方现查+claude 2.1.283 实证）、`quality-gate` 进 REGISTRY（D2 新先例）+ `workbench/evals/l07/` 三件（OrderChecks 六登记项统一入口/OrderProbe 探针/QualityGate 处理器）+ `hook_staging/` 待审投影，真实 Stop 事件红绿四拍入账（block 点名→续跑→重入跳过→真实绿），安装件 `.claude/settings.json` 入库（Stop 过闸生效中）；C1–C8 全达成，起始红 12/12→全量 122/122，复查轮双轴零硬违规；证据账 [evidence/L07.md](evidence/L07.md)） |
| 5 | 后半程协作与产品化（粒度在 L08 检查点定） | L09-L16 | 见 L08 检查点结论 | 未开始 |
| 重走线 | Java 全量重走 L01–L06（ADR-0006）：每讲候选分支 + 起始红 + 具名验收，golden 对照入验收门 | L01-L06 | 原讲合同逐条在 Java 重新满足 + 对照基准字节级一致 | 进行中（**L01 重走已完成：2026-09-29 验收**——31/31 绿（25 合同 + golden 22 场景字节级一致 + fixture 全字段投影机检），自举四查通过，ImportEvidence 拒收实证；证据账 [evidence/L01-java.md](evidence/L01-java.md)。**L02 重走已完成：2026-09-29 验收**——40/40 绿（31 既有 + L02 翻译件 5 + Java 线红点组 3 + golden l02 重放 1），CLAUDE.md 增量补 Java 重走线规则要素（候选表 R1–R5，红点三断言转绿），N0/N1/边界/迁移四会话行为对照齐（C7/C8/C9），golden l02 15 场景字节级一致（双跑指纹 ed8aad6c…）；证据账 [evidence/L02-java.md](evidence/L02-java.md)。**L03 重走已完成：2026-09-29 验收**——59/59 绿（40 既有 + L03 合同测试 18 + U+2028 行界用例 1），spec 解析器六类拒绝词面与围栏语义逐字同形（红点 = Java spec 能力缺失 + golden l03 重放），golden l03 11 场景字节级一致（双跑指纹 f5c7a611…），三份输入复验三态齐 + SHA 只读对照，模板双载体机检锁定（JD3），复查轮 S-1（U+2028 行界实测分歧 test-first 修复）等七项全处置；证据账 [evidence/L03-java.md](evidence/L03-java.md)。**L04 重走已完成：2026-09-30 验收**——84/84 绿（59 既有 + L04 合同 24 + golden l04 重放），V0 受控执行三命令建成（execution 包五件，词面与检查序逐位同形），golden l04 47 场景字节级一致（双跑指纹 `5b7ebbcdb44ca977…`，重放测试 commit 1 红 = 预期红点），B 段由 Java V0 真实组织 claude -p 交付（probe 六场景 N0+终验双 rc 0、eval 权威账对照 rc 0、实测写集无越界），A/B 双任务账四词面密封 + A-before-B 时序机器可证，复查轮 S-1–S-5 修 / S-6–S-7 澄清在案；证据账 [evidence/L04-java.md](evidence/L04-java.md)。**L05 重走已完成：2026-09-30 验收**——91/91 绿（84 既有 + L05 合同 6 + golden l05 重放 1），L05 两工具建成（`workbench/evals/l05/`：缺陷基线构造器 + 收货场景驱动，独立 main 经子进程 python 驱动客户实现——冻结件 in-process import 的结构翻译点，对外契约逐字同形由 golden 锁），golden l05 7 场景字节级一致（双跑指纹 `3d9b623c…`，零新掩码，共享件 main 字段/runMain 扩展行为零变化 84/84 互证），证据链全量重演（eval 两连红→diff→冻结→绿同命令红绿链、观察六枚与 Python 时代输出逐字节同形、盲区构造脚本化 + eval 绿×2 复证封条、任务账 14 条证据 `evidence_complete:true`），Java 建树与 Python 时代指纹互证（基线 `1a2f303c…` / 候选 `6c372dcd…`）；复查轮 S-1–S-3 修（文档侧零代码改动）/ T-1–T-7 搁置在案；证据账 [evidence/L05-java.md](evidence/L05-java.md)。**L06 重走已完成：2026-09-30 验收**——109/109 绿（91 既有 + L06 合同 17 + golden l06 重放），统一运行器建成（`workbench/evals/` 根两件 EvalHarness/ReportContract——待建设清单「L06-java 正题」清账，L07 Hook 入口；+ l06 四件 Checks/StockConsistencyCheck/BuildDefectBaseline/StockProbe），golden l06 9 场景字节级一致（双跑指纹 `1465c87d…`，掩码首例扩展 2 键 + 共享件 env 场景字段/runMainWithEnv 首用，行为零变化 J1/J2 91/91 互证；场景面首次含运行器红/绿/缺 env 报告），证据链全量重演（假绿探针 2 失败与 Python record 60 同位、链 B 同命令红绿、盲区 ×2、指纹终冻=收口复核且客户件三时代同指纹、任务账 16 条 `evidence_complete:true`；途中三起真实失败保留：s09 结构翻译分歧由 golden 抓获按冻结面修正、Checks.repoRoot cwd 偏差由 verify 集成抓获改类位置锚定、测试重载歧义编译失败），复查轮 S-1 修（零代码改动）/ S-2–S-5 搁置；证据账 [evidence/L06-java.md](evidence/L06-java.md)。**重走线 L01–L06 全完成（2026-09-30）——Python 载体退役已执行（ADR-0006 尾款，同日用户确认「退役，继续」：tag `python-carrier-final` @ `d4b77cb` + 移出工作树 `workbench/` `tests/` `evals/` `pyproject.toml`；保留 `tools/`（capture_evidence/golden 生成器/合同投影导出，执行经 tag checkout）、`lesson-*-submission/`、`docs/`、`.runtime/` Python 时代账本（只读封存）与 `.venv`；两测试护栏翻转锁定退役面不复活：L02WorkbenchRulesTest / SpecTemplateDualCarrierTest），L07 解锁**） |

原定向文档中的"阶段 1：重建基线链"已被 ADR-0001（vendor 冻结合同）与 ADR-0003（检查点跟进）取代，不再是一条路线阶段。

**重走线说明**（ADR-0006，2026-09-29 验收）：Python 实现冻结为对照基准直至重走完成（完成后 tag 退役）；L01–L06 的 Python 完成标注是历史事实，不回退；L07 顺延至重走完成后。重走证据落 `docs/replication/evidence/LNN-java.md`，原证据账不重开。退役已于 2026-09-30 执行（tag `python-carrier-final`，对照/再生经 tag checkout；退役提交见 master 日志）。

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
