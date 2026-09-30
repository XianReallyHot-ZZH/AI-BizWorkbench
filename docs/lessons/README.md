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
- [支线-记忆系统最小闭环.md](支线-记忆系统最小闭环.md)（已验收 2026-09-28；L04 后串行插入：七命令记忆系统 + 四链真实走通，复查轮补跨项目治理拒绝与采用版本防漂移，证据账 S01.md）
- [L01-工作台自举.md](L01-工作台自举.md) 附录 A：**Java 重走移植注记**（2026-09-29 审定并执行完成——L01 重走已验收：31/31 绿，golden 字节级对照入验收门，自举四查通过；证据账 [evidence/L01-java.md](../replication/evidence/L01-java.md)，候选 `lesson-01-java` 四 commit + 验收合并 `7d2a017`）
- [L02-仓库规则.md](L02-仓库规则.md) 附录 A：**Java 重走移植注记**（2026-09-29 审定并执行完成——L02 重走已验收：40/40 绿，红点 = CLAUDE.md 缺 Java 重走线规则要素（`JavaLineRuleFactsTest` 三断言）、增量写入候选表 R1–R5 后转绿，N0/N1/边界/迁移四会话行为对照齐，golden l02 15 场景字节级对照；证据账 [evidence/L02-java.md](../replication/evidence/L02-java.md)，候选 `lesson-02-java` 六 commit + 验收合并 `d423e0a`）
- [L03-可验收Spec.md](L03-可验收Spec.md) 附录 A：**Java 重走移植注记**（2026-09-29 审定并执行完成——L03 重走已验收：59/59 绿，红点 = Java spec 能力缺失 + golden l03 重放（重走线首个"重放即目标能力"讲），六类拒绝词面与围栏语义逐字同形，golden l03 11 场景字节级对照（双跑指纹 f5c7a611…），三份输入复验三态齐 + SHA 只读对照，模板双载体机检锁定；复查轮 S-1 实测 U+2028 行界分歧 test-first 修复等七项全处置；证据账 [evidence/L03-java.md](../replication/evidence/L03-java.md)，候选 `lesson-03-java` 五 commit + 验收合并 `d6c05d2`）
- [L04-受控执行.md](L04-受控执行.md) 附录 A：**Java 重走移植注记**（2026-09-29 审定并执行完成——L04 重走已验收：84/84 绿，红点 = Java V0 受控执行能力缺失 + golden l04 重放（重放即目标能力），golden l04 47 场景字节级对照（双跑指纹 5b7ebbcdb44ca977…，掩码扩展 + setup 候选仓数据驱动），B 段由 Java V0 真实组织 claude -p 交付：probe 六场景 N0+终验双 rc 0、eval V2 权威账对照 rc 0、实测写集无越界，A/B 双票 + A-before-B 时序机器可证；复查轮 S-1–S-5 修 / S-6–S-7 澄清在案；证据账 [evidence/L04-java.md](../replication/evidence/L04-java.md)，候选 `lesson-04-java` 六 commit + 验收合并 `85155c3`）
- [L05-失败优先Eval.md](L05-失败优先Eval.md) 附录 A：**Java 重走移植注记**（2026-09-30 审定并执行完成——L05 重走已验收：91/91 绿，红点 = Java 侧 L05 工具缺失 + golden l05 重放；两工具建成于 `workbench/evals/l05/`（独立 main，经子进程 python 驱动客户实现——冻结件 in-process import 的结构翻译点，预期计算与断言留 Java），golden l05 7 场景字节级对照（双跑指纹 3d9b623c…，零新掩码；场景面 = 工具 stdout，l01–l04 以来首次），盲区构造脚本化（`--defect blind`，与生成器内联手术同规格）+ 客户 eval 绿×2 复证封条，客户 eval 红绿链/冻结指纹/任务账全链重演且 Java 建树与 Python 时代指纹互证；复查轮 S-1–S-3 修（零代码改动）/ T-1–T-7 搁置；证据账 [evidence/L05-java.md](../replication/evidence/L05-java.md)，候选 `lesson-05-java` 五 commit + 验收合并 `0b5db7b`）
