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

- [L00-环境自检.md](L00-环境自检.md)（已验收 2026-09-25）
- [L01-工作台自举.md](L01-工作台自举.md)（已验收 2026-09-26）
- [L02-仓库规则.md](L02-仓库规则.md)（已验收 2026-09-27；规则文件口径 AGENTS.md→CLAUDE.md，证据账含 code-review 复查修复轮）
- [L03-可验收Spec.md](L03-可验收Spec.md)（已验收 2026-09-27；对照基线 `7f67533` 检查点 0002，解析器口径只查结构不判业务，证据账含复查修复轮与全局锚定重采）
- [L04-受控执行.md](L04-受控执行.md)（已验收 2026-09-27；换挡点：V0 受控执行 + flowERP 首驱交付，A/B 双票双验收门，证据账含三次真实使用缺陷修复与 eval 误报裁决）
- [L05-失败优先Eval.md](L05-失败优先Eval.md)（已验收 2026-09-28；对照基线 `7f67533` 检查点 0002，客户真理重大现查后增量重定位为辨别力证明+冻结，证据账含两级裁决记录与探针实证）
- [L06-Harness分级判决.md](L06-Harness分级判决.md)（已验收 2026-09-29；客户真理现查后增量落 evals/harness+report_contract 统一裁判，两次修复教学链+假绿辨别力探针+客户 eval 盲区实证，证据账含 R1 驱动快照修正与复查轮勘误）
- [L07-本地护栏Hook.md](L07-本地护栏Hook.md)（已验收 2026-09-30；**首个无 Python 先例的讲**——Codex Hooks→Claude Code Stop hook 映射（D1–D8 裁决），quality-gate 进 REGISTRY + 真实 Stop 事件红绿四拍 + hook_staging 待审投影与人审安装，证据账含三起过程失误如实披露与 actor 误填教训；证据账 [evidence/L07.md](../replication/evidence/L07.md)，候选 `lesson-07` 五 commit + 验收合并 `42a2b01`）
- [L08-远程复验与证据信封.md](L08-远程复验与证据信封.md)（已验收 2026-10-01；**阶段 4 质量链收官 + 第二个无 Python 先例的讲**——GitHub Actions 真实 CI（D1–D9 裁决），evals/l08 四件（SalesChecks 八登记项/AtomicProbe 探针/CiEvidence 信封 + REGISTRY ci-evidence/InjectDefect 教学注入）+ 工作流终态 + CI_GATE_SPEC，七次真实 Run 受控对照（A 假绿→B 可信红→C 可信绿 + 隔离 Run + PR merge 测试），五份 artifact 哈希对账一致（audit.py 复验脚本入库），to-spec 首次用户显式调用（spec 落 docs/specs/）；证据账 [evidence/L08.md](../replication/evidence/L08.md)，候选 `lesson-08` 十二 commit + 验收合并 `ae79bc7`；L08 检查点裁定：后半程逐讲）
- [L09-失败报告翻译成修复任务.md](L09-失败报告翻译成修复任务.md)（已验收 2026-10-01；**阶段 5 首讲 + 第三个无 Python 先例的讲**——报告到 Repair Task 确定性映射（D1–D9 裁决），`workbench/repair/RepairMapper` 严格三态 + REGISTRY `repair-map`（上游 `agent/repair.py` 对应物，L10 修复 Loop 前奏）+ evals/l09 三件（CancelProbe/CancelChecks 九登记项含正式面护栏 D4/InjectCancelDefect 替换式注入），受控修复链全走通（注入→红四项一因→草案→用户具名确认范围→恢复→绿，链闭合 + verify actor Claude），上游 candidate-* 原始 diff pin 内缺席如实记录注入形态自拟（D5），本讲不动 L08 CI 工作流（D8）；证据账 [evidence/L09.md](../replication/evidence/L09.md)，候选 `lesson-09` 六 commit + 验收合并 `54e6626`）
- [L10-有停止条件的修复Loop.md](L10-有停止条件的修复Loop.md)（已验收 2026-10-02；**无 Python 先例第二讲（agent 家族第二件）+ 检查点 0003 首讲**——带预算与停止条件的修复 Loop（D1–D9 裁决），`workbench/loop/LoopRunner` + REGISTRY `loop-run`（上游 `agent/loop.py` 对应物：一轮=一次检查决策轮、固定决策序、五停止条件、三预算不互换、Token 软边界；**上游四缺口全补 D4**——executor 异常结构化/空报告拒绝/待复验标记+两候选分开/检查独立超时）+ evals/l10 三件（ShipProbe 五模式/ShipChecks 六登记项——发货面源件全冻指纹/InjectShipDefect **上游逐字锚点** candidate_loop_lab.py:24-25），十二控制模式全数机检（oscillating 双用例忠实形态），三轨迹受控实验（converged 2/1 / no_progress 2/1 / max_rounds+待复验+循环外审计单独目录），红点三项一因（repeat 准备阶段走首次合法发货——上游明示口径，讲义预测误差如实入账）；claude 执行器接线未真实调用如实声明（D6）；CI 不动（D8）；证据账 [evidence/L10.md](../replication/evidence/L10.md)，候选 `lesson-10` 六 commit + 验收合并 `af00e0b`）
- [L11-独立写集并行调度.md](L11-独立写集并行调度.md)（已验收 2026-10-02；**agent 家族第三件（repair→loop→schedule）**——独立写集调度与串行集成（D1–D9 裁决），`workbench/schedule/Schedule` 纯核逐句对齐上游 `agent/schedule.py`（无 CLI 面如实不造——调用面 = `write_sets_reject_conflict` 登记项 + 合同测试）+ evals/l11 三件（PurchaseProbe 七模式/PurchaseChecks 九登记项默认门 + premature-stock 选跑项/InjectPurchaseDefect 插入式注入 + `--restore` 恢复——上游逐字锚点 integration_lab.py:87-88），十模式 + 三交集机检（undeclared-read 假安全断言），受控同版整合（三次检查 0/1/0 + 指纹断言 + 20/2/18 夹具真缺陷实录——SQL 直插修复后 17/2/15 上游口径），**真实并行双子代理**（两份合同 + 冲突声明被拒留痕 + Agent tool 甲乙重叠 3 分钟 + 写集回查干净 + 溯源三分类——R1–R5 已证实全部 receive_purchase 段 = L12 面移交 + 同版复验双绿）；起始红 10 → 全量 211/211，复查轮根语义真缺陷 test-first 修复（scope(".") 丢失根全匹配——十模式表无 `.` 场景绿测不出）；证据账 [evidence/L11.md](../replication/evidence/L11.md)，候选 `lesson-11` 八 commit + 验收合并 `fcd5f3e`）
- [支线-记忆系统最小闭环.md](支线-记忆系统最小闭环.md)（已验收 2026-09-28；L04 后串行插入：七命令记忆系统 + 四链真实走通，复查轮补跨项目治理拒绝与采用版本防漂移，证据账 S01.md）
- [L01-工作台自举.md](L01-工作台自举.md) 附录 A：**Java 重走移植注记**（2026-09-29 审定并执行完成——L01 重走已验收：31/31 绿，golden 字节级对照入验收门，自举四查通过；证据账 [evidence/L01-java.md](../replication/evidence/L01-java.md)，候选 `lesson-01-java` 四 commit + 验收合并 `7d2a017`）
- [L02-仓库规则.md](L02-仓库规则.md) 附录 A：**Java 重走移植注记**（2026-09-29 审定并执行完成——L02 重走已验收：40/40 绿，红点 = CLAUDE.md 缺 Java 重走线规则要素（`JavaLineRuleFactsTest` 三断言）、增量写入候选表 R1–R5 后转绿，N0/N1/边界/迁移四会话行为对照齐，golden l02 15 场景字节级对照；证据账 [evidence/L02-java.md](../replication/evidence/L02-java.md)，候选 `lesson-02-java` 六 commit + 验收合并 `d423e0a`）
- [L03-可验收Spec.md](L03-可验收Spec.md) 附录 A：**Java 重走移植注记**（2026-09-29 审定并执行完成——L03 重走已验收：59/59 绿，红点 = Java spec 能力缺失 + golden l03 重放（重走线首个"重放即目标能力"讲），六类拒绝词面与围栏语义逐字同形，golden l03 11 场景字节级对照（双跑指纹 f5c7a611…），三份输入复验三态齐 + SHA 只读对照，模板双载体机检锁定；复查轮 S-1 实测 U+2028 行界分歧 test-first 修复等七项全处置；证据账 [evidence/L03-java.md](../replication/evidence/L03-java.md)，候选 `lesson-03-java` 五 commit + 验收合并 `d6c05d2`）
- [L04-受控执行.md](L04-受控执行.md) 附录 A：**Java 重走移植注记**（2026-09-29 审定并执行完成——L04 重走已验收：84/84 绿，红点 = Java V0 受控执行能力缺失 + golden l04 重放（重放即目标能力），golden l04 47 场景字节级对照（双跑指纹 5b7ebbcdb44ca977…，掩码扩展 + setup 候选仓数据驱动），B 段由 Java V0 真实组织 claude -p 交付：probe 六场景 N0+终验双 rc 0、eval V2 权威账对照 rc 0、实测写集无越界，A/B 双票 + A-before-B 时序机器可证；复查轮 S-1–S-5 修 / S-6–S-7 澄清在案；证据账 [evidence/L04-java.md](../replication/evidence/L04-java.md)，候选 `lesson-04-java` 六 commit + 验收合并 `85155c3`）
- [L05-失败优先Eval.md](L05-失败优先Eval.md) 附录 A：**Java 重走移植注记**（2026-09-30 审定并执行完成——L05 重走已验收：91/91 绿，红点 = Java 侧 L05 工具缺失 + golden l05 重放；两工具建成于 `workbench/evals/l05/`（独立 main，经子进程 python 驱动客户实现——冻结件 in-process import 的结构翻译点，预期计算与断言留 Java），golden l05 7 场景字节级对照（双跑指纹 3d9b623c…，零新掩码；场景面 = 工具 stdout，l01–l04 以来首次），盲区构造脚本化（`--defect blind`，与生成器内联手术同规格）+ 客户 eval 绿×2 复证封条，客户 eval 红绿链/冻结指纹/任务账全链重演且 Java 建树与 Python 时代指纹互证；复查轮 S-1–S-3 修（零代码改动）/ T-1–T-7 搁置；证据账 [evidence/L05-java.md](../replication/evidence/L05-java.md)，候选 `lesson-05-java` 五 commit + 验收合并 `0b5db7b`）
- [L06-Harness分级判决.md](L06-Harness分级判决.md) 附录 A：**Java 重走移植注记**（2026-09-30 审定并执行完成——L06 重走已验收：109/109 绿，统一运行器根两件（EvalHarness/ReportContract，L07 Hook 入口）+ l06 三工具建成；golden l06 9 场景字节级对照（双跑指纹 1465c87d…，掩码首例扩展 generated_at/duration_ms + env 场景字段首用；场景面首次含运行器红绿报告 s07/s08 与缺 env 全项失败报告 s09），假绿探针 2 失败与 Python record 60 同位，链 B/盲区/指纹全量重演且客户件三时代同指纹、候选 service.py 与 L05-java 跨讲互证；途中三起真实失败保留（s09 结构翻译分歧由 golden 抓获并按冻结面修正、repoRoot cwd 偏差由 verify 集成抓获改类位置锚定、重载歧义编译失败）；复查轮 S-1 修（零代码改动）/ S-2–S-5 搁置；证据账 [evidence/L06-java.md](../replication/evidence/L06-java.md)，候选 `lesson-06-java` 六 commit + 验收合并 `4f6c0d6`；**重走线 L01–L06 全完成**）
