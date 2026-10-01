# L10 讲义：有停止条件的修复 Loop——预算、进展判定与安全停止

- 状态：**定稿 v1**（D1–D9 已裁决，见 §5 裁决记录；执行期起手 = 用户显式 `/mattpocock-skills:to-spec`，再 `/mattpocock-skills:implement`）
- 起草：2026-10-01；上游 pin `CodexFDE@406f7aa`（**检查点 0003**：`7f67533`→`406f7aa`，用户具名「没问题，继续」；L10 辅导资料重写版为起草底本，合同源零变动）
- 合同来源：`src/main/java/workbench/coursecontracts/CourseContracts.java` LESSONS[number=10]（冻结合同，逐字引用不修）：阶段 repair；workbench_increment「带预算与停止条件的修复 Loop」；erp_increment「交付合法订单状态迁移」；描述「用最多三轮的修复 Loop 阻断订单跳过前置状态直接发货」；demo_data `ORDER:COURSE-DEMO`；写集 `flowerp/ agent/ eval/ tests/`（课程路径口径，映射见 §2）；验收断言「合法状态迁移成功」「非法迁移被阻断且订单状态不变」「预算耗尽被记录为未收敛而非成功」；绑定 eval `illegal_transition_is_blocked`
- 上游参考：`vendors/CodexFDE/docs/courses/L10/` 五件套 + examples 三实验（loop_control_lab 十二模式 / order_transition_lab 五模式 / candidate_loop_lab 三轨迹）+ prompts 三件 + loop-stop-review Skill（四反例）+ `agent/loop.py`（只读对照）
- 本讲性质：**无 Python 先例第二讲**（agent 面第二件，重走线未覆盖；上游 `agent/loop.py` 参考实现在场但**有明示缺口**——本讲建设核心恰是补齐，见 1.3 第 5 条与 D4）；上游大纲合同验收命令 `python -X utf8 -m agent.loop --max-rounds 3` → 本仓库对应 `./bin/wb loop-run --max-rounds 3`（D1）

## 1. 讲解

### 1.1 本讲要什么

L09 交付了 **RepairMapper + 单轮受控修复**：报告 → 任务草案 → 人确认范围 → 修复 → 同命令绿。L09 的链是**人驱动的单轮**。L10 问下一层：第一次修改后检查没全过，还要不要再来一轮？由谁依据什么决定？——把「检查当前版本 → 形成修复任务 → 执行修改 → 检查新版本」有条件地重复执行，就是 **Loop（修复循环）**；人先规定**最多几轮、什么算进展、何时停止**，再让工作台组织修改和复验。

**计数约定**（上游辅导资料重写版钉死，全讲沿用）：一轮 = 一次 Harness 决策轮——检查当前候选；若有阻断失败且仍允许继续，才生成 Repair Task 并调用执行器；修改后的候选在**下一轮开头**接受检查。因此「最多三轮」= 最多两次修改机会 + 末轮修改可能落在最后一次检查之后（待复验）。每次决策按固定顺序：**报告有效性 → 达标即停 → 预算（轮/时/Token）→ 进展 → 授权范围**（实现细节五级表）。达标即停不把轮数用满；`converged` 只说检查通过，负责人接受是另一层。

**五类核心停止条件**：达标即停、轮数到限、无进展（失败签名相邻相同）、时间到限、Token 到限；另有**安全停止路径**（执行器异常 / 报告无效 / 需扩授权 → 交回人）。三预算**不可互换**：轮数管次数、时间管历时、Token 管模型用量；Token 是软边界（单次超支只能停后续调用，不能回退已发生消耗），缺正用量停止核算而非当零算。**Loop History** 是接续链：当前候选与最后已验证候选**分开保存**——没有后置检查时，结论只能写「已修改、待复验」，不能拿旧报告解释新代码。

### 1.2 业务案例：合法发货与三拒绝（入门 ERPService 主线）

教学数据（手册口径）：A 在库 10，OTHER 已预占 2，TARGET 已预占 4；发货前总预占 6、可用 4。TARGET 合法发货后：**在库 6 / 总预占 2 / 可用 4**（在库与预占各减 4），OTHER 不变，TARGET=shipped，一条 ship 流水（quantity=-4，reserved_delta=-4）——发货不是取消：发货**既消耗在库又撤销本订单占用**，只改状态标签证明不了这两笔。三种非法状态（draft / cancelled / 已 shipped 再发）必须拒绝且**四张表不变**（stock / sales_orders / sales_order_lines / inventory_events）。

教学错误 V0 = `if True:`（拒绝一切发货）：非法拍全过、合法拍失败——「全部拒绝」与「全部放行」都不是完整规则，**每轮沿用完整要求、重跑已通过项（回归检查）**是继续的纪律。收敛轨迹 = V0 红（两项失败）→ 修 → V1 检查（合法过、草稿仍放行）→ 剩余预算内再修 → V2 全绿 → converged；卡住形态 = 同签名重复（stopped_no_progress）与 A/B/A 振荡（修好这里弄坏那里）；到限形态 = 末轮修改后无检查机会（待复验）。`refuse-all` 实验模式是人为错误修复的反证面：连合法路径一起拒绝，Harness 必须红。

### 1.3 客户真理现查（讲前 2026-10-01，`vendors/flowERP` submodule 只读）

1. **入门面在场且正确**：`flowerp/service.py:285` `ship_order`——`:290` 状态守卫 `if order["status"] != OrderStatus.RESERVED: raise InvalidTransition`（**注入锚点 = 此行**，上游权威源见第 4 条）；合法路径逐行 `on_hand-=q, reserved-=q` + ship 流水 INSERT（event_key 幂等键 `ship:{order_id}:{sku}`）+ 状态转 SHIPPED，全程 `store.connect()` 事务；
2. **正式面在场**：`flowerp/sales.py` 迁移守卫族（`:225` 只有草稿可改、`:266` 只有草稿可确认、`:285` 预占状态机等 InvalidTransition 面）——L09 已收 `l09_sales_cancel_formal` 护栏；本讲绑定 eval 与注入面均在入门面，正式面靠既有护栏回归；
3. **绑定 eval 在场**（blocking）：`eval/harness.py:29` 注册 `illegal_transition_is_blocked`，`eval/cases.py:89` 用例体——draft 订单跳过预占直接 ship 应抛 InvalidTransition（临时库）；
4. **上游参考件在场且权威源升格**：`agent/loop.py`（114 行参考实现：`_signature` 阻断失败名排序 / 决策序 converged→no_progress→time→token→任务→执行 / 六停止状态名）；examples 三件**脚本本身在 pin 内可执行**——`candidate_loop_lab.py:24-25` 逐字给出注入锚点 GOOD/BAD 行（`if order["status"] != OrderStatus.RESERVED:` ↔ `if True:  # TEACHING: incorrectly reject every shipment`），**本讲注入 diff 有上游逐字权威源**（与 L09 纯缺席不同）；`agent/graph.py`、`agent/schedule.py`（L11+ 前瞻件，本讲不动）；prompts 三件 + loop-stop-review Skill 四反例（末轮未复验 / 同名失败原因变化 / 剩余为零掩盖超额 / 预设补丁冒充模型修复）；
5. **参考实现缺口**（上游附录 B 明示「本讲要求」补齐，D4 裁定承接面）：①执行器异常未捕获——`TimeoutExpired` 直接抛出无结构化结果；②空报告接口反例——`results` 为空时 `_signature` 得空元组 → 误判 `converged`（真实 Harness 会拒绝，但 Loop 自身不校验报告有效性）；③末轮待复验无显式状态——`stopped_max_rounds` 的 `remaining_failures` 来自修改前报告，无「当前候选 ≠ 最后已验证候选」的分开标记；④检查面无独立超时（时间预算只传执行器）；
6. **材料缺口**：loop-stop-review Skill 引用的 `assets/latest/candidate-last-repair/index.json` 等原始 JSON **pin 内缺席**（只有 html/png 渲染件）——三轨迹以脚本重跑为准（可执行、可复现），渲染件只作对照，如实记录。

**结论**：本讲增量如实转为——①**Loop 控制器**（上游 `run_loop` 的 Java 对应物：决策序 + 五停止条件 + 三预算 + History，**补齐四缺口**）；②**发货面探针与统一入口**（五模式探针驱动客户入门实现 + 登记项收口）；③**两条真实轨迹**（一次收敛 + 一次未收敛，受控候选实验承载——演示结果合同）。

### 1.4 宿主映射（ADR-0002）：上游双仓库 → 本仓库单仓库 + submodule

| 上游（CodexFDE 课程） | 本仓库 |
|---|---|
| `agent/loop.py` `run_loop`（suite_runner/executor 进程内注入） | `src/main/java/workbench/loop/LoopRunner.java`（新子包，`repair/` 先例；纯核可注入缝 + REGISTRY `loop-run`——D1）；检查面经**子进程**统一入口（与 L09 复现命令同口径），不进程内直调 |
| `_run_codex`（`codex exec --json --sandbox workspace-write`） | 执行器三态（D6）：默认 **dry-run** 只生成任务（上游同形）；教学 **patch** = 预设补丁子进程（三轨迹，如实标注非模型修复）；真实执行器接线 L04 `execution/ProcessRunner`（ADR-0002 无头面），本讲不强制真实调用（上游 SUBMISSION 明示替身轨迹不得改名） |
| `build_repair_task` legacy（六洞版，loop.py 每轮体） | **复用 L09 `RepairMapper` 严格版**（不重蹈 legacy 洞——上游教学点正是它不严格；与上游 loop.py 的差异如实记录：严格三态在 Loop 内即报告有效性先行的部分承载） |
| `loop_control_lab.py` 十二模式（mock 注入合成报告/替身/假时钟） | Java 合同测试十二模式（合成报告 JSON + 替身 executor + 可注入时钟——`executor-error`/`empty-report` 两模式按 D4 补齐后行为断言） |
| `order_transition_lab.py` 五模式（patch harness 临时登记） | `workbench.evals.l10.ShipProbe` 五模式探针 + **正式登记项**（`ShipChecks` 收口——复现命令指向真实可跑的统一入口，L09 修复上游临时用例不可复现的先例承袭） |
| `candidate_loop_lab.py` 三轨迹（拷贝候选 + 重写 harness + 预设补丁 + 循环外审计） | 受控候选实验：`.runtime/course/L10-candidate/` 拷贝树 + 注入（锚点 = 1.3 第 1 条守卫行，**上游逐字源在场**）+ Loop 三模式真跑 + `after-loop-audit` 单独保存不计入轮次 |
| `python -X utf8 -m agent.loop --max-rounds 3`（验收命令） | `./bin/wb loop-run --max-rounds 3`（默认 dry-run；参数校验同上游：max_rounds 1..10 / budget>0 / timeout>0） |

映射的**不变量**（上游教学点逐条承接）：一轮 = 一次检查决策轮，修改发生在两次检查之间；达标即停不凑轮数；`stopped_*` ≠ 业务失败 ≠ 负责人接受（三层分开）；同名失败原因变化不自动等于无进展（签名是保守近似，A/B/A 振荡需全历史签名）；预算剩余归零不掩盖已超额；缺正用量 ≠ 免费；循环外审计单独计数不回填；替身执行与真实模型调用分开标注（铁律 3 证据面）；`human_review` 恒等人签（铁律 2）。

### 1.5 因果交接（本讲为下一讲准备什么）

L11 用 Codex 原生 Subagents 并行处理独立任务——Loop 是**同一任务的纵向重复**，Subagents 是**独立任务的横向分工**；`agent/schedule.py`（写集重叠检查）是 L11 参考件，本讲不动。Loop History 的最小上下文包（固定要求 + 当前候选 + 剩余失败 + 已试方案 + 剩余预算）是后续记忆复用的素材面——本讲只做事项内交接，不宣称跨事项记忆闭环（上游明示边界）。

## 2. 本讲合同（C 编号清单）

blocking / observing 标注。write_scope 映射（合同四路径 → 本仓库）：`flowerp/` → `.runtime/course/L10-candidate/` 拷贝树（注入/恢复只发生在拷贝树，永不写回 vendors，铁律 1）；`agent/` → `src/main/java/workbench/loop/`；`eval/`、`tests/` → `src/main/java/workbench/evals/l10/` + `src/test/java/`。

| C | 验收项（来源） | 类型 | 怎么验 |
|---|---|---|---|
| C1 | 合法状态迁移成功（合同 acceptance[0]） | blocking | 探针 legal 模式：在库 10 / OTHER 预占 2 / TARGET 预占 4，发货后 (6,2,4)、OTHER 订单不变、恰好一条 ship 流水 (-4,-4)；独立预期 Java 算（不抄返回值） |
| C2 | 非法迁移被阻断且订单状态不变（acceptance[1]） | blocking | draft / cancelled / double-ship 三拍：`InvalidTransition` + 前后四表快照相等 + OTHER 保护；`refuse-all` 反证面 = 教学错误修复（合法路径失败），探针模式在场**不进默认登记项**（L09 leak 先例：必红项不进默认门） |
| C3 | 绑定 eval `illegal_transition_is_blocked`（合同 evalCases[0]） | blocking | 客户名原样子进程照跑（业务权威 ADR-0005；净树绿，注入树**绿**——本讲注入是过度拒绝（BAD=`if True:`），非法拍不受影响恰是隔离护栏，红点构成如实入账，与 L09 跳过释放形态相反） |
| C4 | Loop 控制器决策面：五停止条件 + 计数（workbench_increment） | blocking | 合同测试机检：决策序固定（有效性→达标→预算→进展→授权）；`converged` 达标即停不多改；`stopped_no_progress` 相邻签名相同；`stopped_max_rounds` 到限且 remaining 来自最后有效报告；`stopped_token_budget` 超支软边界（used=150>budget=100，remaining=0，不回退）；`stopped_time_budget` 轮前检查；`stopped_token_usage_unavailable` 缺正用量停核算；dry-run 只生成任务零执行；参数校验 1..10/>0/>0 |
| C5 | 十二控制模式全数机检（上游表格逐项对齐） | blocking | already-green 1/0、converge 2/1、repeated 2/1、changed-reason 2/1（同名原因变仍停——签名只看名）、oscillating 3/3 到限、last-repair 1/1 待复验、token-budget、missing-usage、time-budget 1/0（可注入时钟，不真等）、**executor-error 结构化停止**（D4 补齐：不再裸抛）、**empty-report 拒绝**（D4 补齐：报告有效性先行，不再误判 converged）、dry-run 2/0 |
| C6 | Loop History 与接续：两候选分开 + 运行隔离 | blocking | 每轮保存检查报告 / 任务 / 执行输出 / Diff / 用量 / 决定；loop-result 中**当前候选与最后已验证候选分开**，末轮修改后 remaining_failures 标注来源轮次 + 待复验标记；每次运行独立目录不覆盖旧证据（拷贝树 `open("x")` 同语义） |
| C7 | 两条真实轨迹（大纲合同演示结果：收敛一次 + 未收敛一次） | blocking | 受控候选实验承载：**收敛** = 注入 → Loop 真跑（检查红 → RepairMapper 任务 → patch executor 恢复 → 检查绿 → `converged`，2 检查/1 执行）；**未收敛** = no-change executor（2/1 → `stopped_no_progress`，候选未变如实）；last-repair 形态（1/1 → `stopped_max_rounds` + 待复验 + 循环外审计单独保存）作第三条对照；同命令红绿链 + observed_at 严格递增 + `workbench-status --require-red-green-evidence` |
| C8 | 检查冻结不被偷偷改动 + 旧能力不回归 | blocking | 客户 `eval/harness.py`+`eval/cases.py` 双树同指纹（L05–L09 机制承袭）+ 客户发货面源件（`flowerp/service.py`+`flowerp/sales.py`）指纹初冻与收口复核（注入 diff 即对账件）；本讲 Java 源件指纹初冻 + 收口复核；`mvn test` 全量绿（含 golden 六重放 + L07/L08/L09 合同） |
| C9 | 诚实性分层留痕（acceptance[2]「预算耗尽被记录为未收敛而非成功」的全扩展） | observing | `stopped_*` / 业务通过 / 负责人接受三层分列不互代；合成用量与替身执行如实标注（`usage_is_synthetic` 先例）；循环外审计不回填为内部轮次；预算超额不写成「控制在预算内」；两条轨迹的检查命令前后一致（红绿同面） |

登记项构成（`ShipChecks` 收口，全 blocking，六件）：`l10_ship_legal`（C1）、`l10_ship_draft` / `l10_ship_cancelled` / `l10_ship_repeat`（C2 三拍——上游 candidate 四件同名面）、`illegal_transition_is_blocked`（C3，客户名原样）、`l10_frozen_checks`（C8 指纹复核）。**旧域回归不重复收 l07–l09 登记项**（全量 `mvn test` + CI SalesChecks 双轨承载，L09 D3 已立机制）；C4/C5 是控制器机检，落 `src/test/java/`（上游同分工：业务面 order_transition_lab / 控制器面 loop_control_lab），不进 ShipChecks。探针 = `workbench.evals.l10.ShipProbe`（子进程 python 驱动 target 树客户入门 `ERPService`，临时库 + 四表快照回报原始观察不判对错；断言全留 Java——`CancelProbe` 同款缝）。

## 3. 实操流程 + Claude 简报

1. **前置（裁决后、动手前）**：讲义定稿入 master（D1–D9 裁决记录补 §5）。无对照基准（无 Python 先例第二讲）；回归门 = 全量 `mvn test`。
2. **候选分支 `lesson-10`**：【配合点·to-spec】`/mattpocock-skills:to-spec` 用户显式（L08/L09 先例：spec 落 `docs/specs/`；未调用则降级口径重新确认，起始红采前问一次）。commit 1 = 合同测试起始红（`LoopRunner` 缺席 / `bin/wb loop-run` 未注册 / `ShipChecks` main 缺席三面——Java 编译不绑实现类，L06-java 教训承袭）+ 红证据（capture + `ImportEvidence` 落账）。
3. **实现转绿**（用户显式 `/mattpocock-skills:implement` 后动笔）：`workbench/loop/LoopRunner`（决策序 + 五停止 + 三预算 + History + **D4 四缺口补齐**：executor 异常结构化停止 / 报告有效性先行（复用 L06 `ReportContract` 校验面）/ 两候选分开 + 待复验标记 / 检查子进程超时）+ REGISTRY `loop-run`（默认 dry-run；`--suite-command` 检查命令注入、`--executor dry-run|patch|claude`、`--runtime-dir` 每运行新目录——D1/D2/D6）；`workbench/evals/l10/` 两件——`ShipProbe`（五模式探针）、`ShipChecks`（登记项收口，`--target/--python/--report-path` 同先例面）+ 注入装置（守卫行替换，锚点唯一性机检 + 幂等护栏，只写拷贝树——上游逐字源在场，D5）；合同测试十二模式转绿。
4. **受控候选实验三轨迹**（C7）：`.runtime/course/L10-candidate/` 净树（建树指纹入账 + git 起点封存——L09 教训③）→ 注入（GOOD 行→BAD 行，diff 落账 rc 0）→ `ShipChecks --target` 红 capture（红点构成如实入账：业务面红点仅 `l10_ship_legal`——隔离护栏；指纹项红点构成实现时按冻结面口径如实记录）→ Loop 三模式各新运行目录真跑：**repair**（patch executor 恢复 → 检查绿 → `converged`）、**no-change**（executor 空转 → `stopped_no_progress`）、**last-repair**（`--max-rounds 1` → `stopped_max_rounds` + 待复验 + 循环外审计 `after-loop-audit` 单独保存）。
5. **verify 集成**：`./bin/wb workbench-task-run CASE-WB-L10-001 --workspace .runtime/course/L10-candidate --mode verify --eval-command "<ShipChecks 绝对路径命令，本地展开>" --execution-timeout 900 --actor Claude`（L08/L09 先例同形；actor 填实际执行者；eval-command 绝对路径——L08 教训）→ `workbench-status` 密封。
6. **收口**：`code-review` 双轴（master...lesson-10）→ 修复轮（若有）→ 用户按 §4 具名验收 → `git merge --no-ff lesson-10`（合并信息含验收人与结论）→ roadmap 阶段 5 行推进 + 讲义导航 + CLAUDE.md（架构行 loop/ 子包与 evals l10 两件、待建设清单）；收口后提请用户 push（遇 HTTP 400 分段推送，先例记忆在案）。

### Claude 简报（第 3 段粘贴用）

> 在 lesson-10 候选分支上：①以合同测试（LoopRunner 缺席 / loop-run 未注册 / ShipChecks main 缺席三面）采起始红，capture + ImportEvidence 落账；②实现 `workbench/loop/LoopRunner`（一轮=一次检查决策轮；决策序 有效性→达标→预算→进展→授权；五停止条件 + 三预算不互换 + Token 软边界 + 缺正用量停核算；补齐上游四缺口：executor 异常结构化停止 / 空报告拒绝不误判 converged（ReportContract 先行）/ 当前候选与最后已验证候选分开 + 末轮待复验标记 / 检查子进程超时；每轮任务复用 L09 RepairMapper 严格版）+ REGISTRY loop-run（默认 dry-run）+ `workbench/evals/l10/` 两件（ShipProbe 五模式探针——子进程 python 驱动客户入门 ERPService 临时库四表快照回报不判对错，断言留 Java；ShipChecks 六登记项收口——四发货拍 + 客户 illegal_transition_is_blocked 原名照跑 + 冻结指纹）+ 注入装置（守卫行替换 + 锚点唯一 + 幂等，只写拷贝树，上游逐字源 candidate_loop_lab.py:24-25），合同测试十二模式转绿；③受控候选实验三轨迹：L10-candidate 净树建指纹 + git 起点封存 → 注入 diff 落账 → ShipChecks 红 capture → Loop 真跑 repair（converged 2/1）/ no-change（stopped_no_progress 2/1）/ last-repair（stopped_max_rounds 1/1 + 待复验 + 循环外审计单独保存）；④verify 集成 CASE-WB-L10-001（actor 实际执行者，eval-command 绝对路径）+ status 密封。vendors/ 只读；失败记录一律保留；stopped/业务通过/负责人接受三层分列；替身执行与合成用量如实标注不冒充模型修复；循环外审计不回填。

## 4. 人审清单

- **看哪个 diff**：commit 1 = 三面起始红（无实现）；commit 2 = `workbench/loop/` + `workbench/evals/l10/` + cli 注册缝 + 注入装置 + 绿证据。注入/恢复各只动拷贝树 `flowerp/service.py` 守卫行一行——恢复 diff 与注入 diff 互逆（上游逐字源：`candidate_loop_lab.py:24-25`），出现其他文件改动即红旗。`LoopRunner` 决策序与上游 `agent/loop.py` 逐句对照（补齐的四处应以独立分支/状态名可指认）。
- **跑哪些门**：亲手 `mvn test`（全量含六重放 + L07–L09 合同 + 本讲十二模式）；亲手对注入树跑 `ShipChecks`（应 block/rc 1/红点仅合法拍）；亲手对净树跑（应全绿 rc 0）；抽一个模式亲手跑 `./bin/wb loop-run --max-rounds 3`（dry-run：只生成任务零执行，任务 JSON 里 scope=红点项）；核对三轨迹运行目录互不覆盖、`after-loop-audit` 单独存放；`workbench-status --require-red-green-evidence`；核对客户 eval 双树与发货面源件指纹。
- **什么算作弊**：把 `stopped_*` 写成修复失败或业务通过（三层混装）；末轮修改后宣称 converged；`no-change` 轨迹的候选未变被写成「尝试过修复」；合成用量（patch executor 占位值）当真实 Token 核算；循环外审计回填为 Loop 内部轮次；empty-report 被判 converged（本讲已补齐应拒绝——若仍误判即 D4 未落实）；红绿检查不同命令/不同目标树；恢复动了守卫行以外文件；删弱客户 eval 或改指纹；把 patch 替身执行写成模型自动修复。

## 5. 候选表（决策点，一次列全，等具名确认）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| D1 | Loop 控制器落点与 CLI 形态 | `src/main/java/workbench/loop/LoopRunner.java`：**新子包**对应上游 `agent/loop.py`（`repair/` 先例——agent 家族逐件独立建包，loop 是第二件）；纯核（决策序 + 停止状态 + History）+ 可注入缝（suite 命令执行器 / executor / 时钟）+ REGISTRY **`loop-run`**（quality-gate/ci-evidence/repair-map 先例）；参数对齐上游：`--max-rounds`（默认 3，校验 1..10）`--token-budget`（默认 30000）`--timeout`（默认 900 秒），另加 `--suite-command`（检查命令，绝对路径）`--executor dry-run\|patch\|claude`（默认 dry-run）`--runtime-dir`（每运行新目录）——上游参考 CLI 无 `--runtime-dir` 是其已知局限（手册明示本人新增须先实现验证，本仓库即实现验证） | 落 `workbench/evals/l10/`（控制器不是 eval 工具，层属错位不取）；独立 main 不进 REGISTRY（丢 wb 统一调用面） |
| D2 | 检查面注入缝 | Loop 每轮经**子进程**跑统一入口（`ShipChecks --target 拷贝树`，绝对路径命令）回报 schema 1.0 报告 JSON → LoopRunner 解析提取阻断失败（与 L09 复现命令同口径；上游 candidate_loop_lab 同为新进程检查）；检查子进程带独立超时（D4 缺口④） | 进程内直调 `EvalHarness.run`（绕真实 CLI 面，客户真理面丢失，不取） |
| D3 | 发货面探针与登记项构成 | `workbench.evals.l10.ShipProbe` 五模式（legal/draft-ship/cancelled-ship/double-ship/refuse-all，场景与断言对齐上游 order_transition_lab：10/2/4 布局、四表快照、refuse-all 反证），临时库回报不判对错；`ShipChecks` 独立 main 挂 `EvalHarness` 六件全 blocking：四发货拍（l10_ship_legal/draft/cancelled/repeat）+ 客户 `illegal_transition_is_blocked` 原名 + `l10_frozen_checks`（客户 eval 双树 + 发货面源件 + 本讲 Java 源件指纹）；refuse-all 不进默认门（必红项，L09 leak 先例）；旧域回归双轨承载（L09 D3 机制已立） | 扩展 l09 CancelProbe（已收口讲次职责混装不取）；收 l07–l09 回归项（膨胀不取）；C4/C5 进 ShipChecks（控制器机检与业务面分层，上游同分工） |
| D4 | 上游四缺口补齐面（本讲建设核心） | **全补**（上游附录 B「本讲要求」）：①executor 异常捕获 → `stopped_executor_error` 结构化结果（保存已启动事实/最后报告/Diff/待复验，不再裸抛）；②报告有效性先行——空 `results`/协议无效拒绝进入达标判定，不再误判 `converged`（复用 L06 `ReportContract`；对齐 L09 RepairMapper invalid_report 面）；③loop-result 增**当前候选与最后已验证候选**分开字段 + 末轮修改后 `remaining_failures` 标注来源轮次 + 待复验标记（上游无 `pending_verification` 状态是明示缺口）；④检查子进程独立超时。四处与上游参考实现的差异逐条如实记录（检查点 changelog 同款纪律） | 照抄上游缺口（附录 B 明示「必须在历史中明确标记并补齐交回」，不取）；只补部分（缺口②是 empty-report 反例的根因，缺它 C5 十二模式断不过，全补成本已摊薄） |
| D5 | 注入形态与三轨迹 | 锚点 = `service.py:290` 守卫行，**上游逐字源在场**（`candidate_loop_lab.py:24-25`：`if order["status"] != OrderStatus.RESERVED:` → `if True:  # TEACHING: incorrectly reject every shipment`——按上游原文逐字，含注释措辞）；红点构成 = 过度拒绝（业务面红点仅合法拍 `l10_ship_legal`，非法三拍与客户 eval 绿——隔离护栏恰与注入形态互证）；三轨迹 repair/no-change/last-repair 各新运行目录；`after-loop-audit` 循环外单独保存 | 改锚点为放行错误（`if False:`——阻断拍红，与上游 BAD 形态不符，不取）；复用 L09 注入装置不动（锚点/语义不同，参数化扩展或新件实现时定） |
| D6 | 执行器合同与「真实」口径 | 三态：`dry-run` 默认（只生成任务零执行，上游同形）；`patch` = 预设补丁子进程（教学执行器，三轨迹承载，`usage_is_synthetic` 如实标注——上游 candidate_loop_lab 同款「预设补丁不是 Codex」）；`claude` = 接线 L04 `execution/ProcessRunner` 无头面（ADR-0002），**本讲接线完成但不强制真实调用**（真实收敛/未收敛两轨迹由 patch 承载并如实标注；上游 SUBMISSION 明示「替身轨迹不能改名为实际模型修复」——本讲如实记录真实执行器待首次真实使用） | 本讲强制真实无头调用（会话外递归执行链证据面复杂，超本讲合同；留后续讲次/检查点）；不接线 claude 态（执行器合同断层，L04 面白建，不取） |
| D7 | 分支/证据/任务/账本命名 | 分支 `lesson-10`；证据账 `docs/replication/evidence/L10.md`；任务 `CASE-WB-L10-001` @ `.runtime/course/L01-workbench-java/`；采集根 `lesson-10-submission/`；讲义本文件（L07–L09 D7 直承） | 任何 -java 后缀；复用 lesson-09-submission（跨讲混链） |
| D8 | CI 面：本讲不动 L08 工作流 | **保持 `.github/workflows/l08-eval.yml` 终态不动**（L09 D8 直承——CI_GATE_SPEC 是 L08 合同写集映射件，跨讲改动留检查点统一裁量）；本讲 CI 覆盖 = push lesson-10 触发现有 SalesChecks Run（旧门照跑）+ `mvn -q process-test-classes` 构建面顺带编译 L10 新类；ShipChecks 与 loop-run 远程复验缺口如实声明（本地全量门承载） | 本讲扩工作流（跨讲合同变更混进业务讲，不取） |
| D9 | 无对照基准的验收门替代 | ①合同测试（三面工具缺席起始红 + 十二模式全数 + 决策序断言）②三轨迹受控实验（converged/stopped_no_progress/stopped_max_rounds+待复验，计数与上游表格逐项对齐）③指纹冻结对账（客户 eval 双树 + 发货面源件 + 本讲 Java 源件初冻/收口复核）④全量 `mvn test`。如实声明无字节级对照基准（L07 D8 / L08 D9 / L09 D9 直承） | 现在补建对照基准（无 Python 先例可采，硬造即自造对照，不取） |

> **裁决记录（2026-10-01）**：D1–D9 推荐方案**全部接受**。用户原话：「没问题，继续」（批量确认，逐字入账）。起草日现查：CodexFDE pin `406f7aa`（**检查点 0003**，`7f67533`→`406f7aa` 同日裁决同日入账，commit `6c555a7`）；客户真理五条成立——入门 `service.py:285` ship_order（`:290` 守卫行 = 注入锚点）与正式 `sales.py` 守卫族在场且正确；绑定 eval `illegal_transition_is_blocked` 在场（blocking）；上游 `agent/loop.py` + examples 三件 + prompts + Skill 在场，**注入锚点逐字源在场**（`candidate_loop_lab.py:24-25`）；上游参考实现四缺口（executor 异常裸抛 / 空报告误判 converged / 末轮待复验无显式状态 / 检查面无独立超时）——D4 全补；Skill 引用的 `assets/latest/candidate-last-repair/index.json` 原始件 pin 内缺席（只有渲染件），三轨迹以脚本重跑为准，如实记录。
