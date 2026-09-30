# L06 讲义：统一 Harness——分级、报告与退出码一致

- 状态：**定稿 v1**（D1–D6 已裁决，见 §5 裁决记录；执行期待用户显式 `/mattpocock-skills:implement`）
- 起草：2026-09-28；上游 pin `CodexFDE@7f67533`（起草日 fetch 现查未移动；当日新 tag `course-package-20260928` 即指向本 pin，无检查点动作）
- 合同来源：`workbench/course_contracts.py` LESSONS[number=6]（冻结合同，逐字引用不修）
- 上游参考：`vendors/CodexFDE/docs/courses/L06/`（辅导资料/实践操作手册/examples，只读对照）
- 本讲性质：**客户真理现查 + 工作台侧增量起步**——CLAUDE.md 待建设清单首项 `eval.harness` 在本讲落地为 `evals/` 侧统一运行器

## 1. 讲解

### 1.1 本讲要什么

L05 证明的是**单项检查的辨别力**：检查能对已知坏版本报红，且检查本身被冻结防"改标准凑绿"。L06 的问题是下一层：**检查一多，谁来说"这次能不能交付"？**——多项检查各自报红报绿不够，需要一个统一入口按登记清单跑完全部、分级汇总、给出唯一判决和一致的进程退出码。比"没有检查"更危险的是**假绿灯**：分项发现必须修复的错误，汇总却放行、退出码 0——它冒充可信。上游课程用两个植入缺陷教学（CSV 错写在库数 + 运行器漏算阻断失败），并钉死修复次序：<strong>先修汇总器，再修业务</strong>——中停点的"可信红"恰好证明汇总修好了；若跳过次序，最终变绿就说不清来自哪项改动。

### 1.2 业务案例：可用库存口径

在库（on_hand）是实际存放的数量，预占（reserved）是已留给订单但尚未出库的数量，可用（available）是还能供其他订单使用的数量。本讲无其他冻结/分仓因素，口径：<strong>可用 = 在库 − 预占</strong>。在库 8、预占 3，查询与 CSV 导出都应是 [8, 3, 5]——5 由业务规则独立算出，不能拿查询返回值当导出预期（两处同时算错也会互相通过）。预占改变分配，不表示货已离库。再请求预占 6（> 可用 5）必须拒绝且状态不变（FlowERP 边界 #1：可用库存不能为负）。迁移检查换数据 在库 13/预占 4 → 可用 9、请求 10 拒绝——排除写死 5 的实现。

### 1.3 客户真理现查（讲前 2026-09-28，`vendors/flowERP` `e0088d3` 只读）

1. **客户已有统一 Harness**：`eval/harness.py:47-104` `run_suite`——EVALS 登记 18 用例、blocking 分级、逐项 duration/evidence/error、schema 1.0 报告、`decision`、退出码 = `blocking_failed ? 1 : 0`。合同"统一 Harness、判决与退出码"的**客户侧对应物已存在**，且报告 schema 与上游 L06 教学运行器的目标 schema 同为 1.0；
2. 合同绑定 eval `stock_never_negative` **已是客户 blocking 用例**（`eval/cases.py:33`）：超额预占拒绝 + 查询面口径断言（available==5、reserved==0）；
3. **辨别力盲区（预计，探针执行期实证）**：客户 `inventory_export_is_stable`（blocking，`cases.py:55`）校验 CSV 表头/排序/门面-v2 同数，但其数据 **reserved=0**（`,3,0,3`/`,2,0,2`）——对「CSV available 误写 on_hand 且 reserved>0」**不可见**（reserved=0 时 available ≡ on_hand）。L05 盲区（账面重写）同款：检查没跑在缺陷可见的形状上；
4. **缺陷锚点可构造**：上游 prepare 与 `flowerp/service.py:110` 逐字吻合——`{row['available']}` 在 `export_inventory` CSV 模板中唯一命中，锚点唯一性校验后可植入（L05 `build_defect_baseline` 同款机制）。

**结论**：本讲增量如实转为——<strong>本仓库 `evals/` 侧统一运行器 + 报告合同</strong>（工作台侧"后续 Codex 交付的共同裁判"，L07 Hook 入口），用它收口业务检查；客户 harness 与客户 eval 照跑（回归 + 盲区探针），不删弱、不改写。执行期探针（盲区实证、假绿辨别力）先于采证，翻案走 §5 裁决记录。

### 1.3.1 因果交接（本讲为下一讲准备什么）

L07 用 Codex Hooks 把检查从"手动跑"升级为"生命周期时点自动跑"（LESSON_STORY[7]：Hook 复用同一 Harness）。本讲交出的统一入口就是那个"同一"：`evals/harness`（登记 → 分级执行 → 报告 → 退出码）+ `evals/report_contract`（报告与退出码一致性校验，review 缝）。报告 schema 1.0 与客户 harness 对齐，是 L08"本地/远端同一 Eval 身份"的铺垫。

## 2. 本讲合同（C 编号清单）

blocking / observing 标注；【现查】= 按客户真理调整后的口径。write_scope 映射（`course_contracts.py` 口径注）：上游 `flowerp/, eval/, tests/` → 本仓库 `evals/`、`tests/`、`.runtime/` clone 内 `flowerp/`（业务修改只发生在 clone，永不写回 vendors）。

| C | 验收项（来源） | 类型 | 怎么验 |
|---|---|---|---|
| C1 | 可用库存按在手减预占计算（合同 acceptance[0]） | blocking | 口径交叉检查：查询与 CSV 同为 [8,3,5]（预期独立计算 8−3=5，不抄返回值），CSV 恰一条该 SKU 记录；迁移 13/4→[13,4,9]、请求 10 拒绝且状态不变（排除写死 5） |
| C2 | 超额预占拒绝且状态不变（绑定 eval `stock_never_negative`） | blocking | 客户 blocking 用例在目标 clone 照跑绿；口径检查内：请求 可用+1 拒绝，库存/流水/订单/明细前后相等（FlowERP 边界 #1） |
| C3 | 统一运行器分级语义（合同 workbench_increment；上游九项运行器合同映射） | blocking | `tests/test_l06_eval_harness.py` 九项语义：通过+报告落盘（generated_at 带时区、duration_ms）；blocking 一败即 block+退出 1（非多数表决）；observing 只记告警可 pass；异常保留类型+原因且继续跑后续项；observing 异常不升格；非法选择拒绝（空/未知/错 suite/重复名）；子集选择显式；报告不可覆盖旧证据；消费端拒绝汇总/退出码矛盾 |
| C4 | 阻断失败、报告 decision、进程退出码三者一致（合同 acceptance[1]） | blocking | `evals/report_contract.py` 校验（schema/用例身份/汇总/退出码任一矛盾即 RuntimeError，可信红报告照样通过 review）；辨别力探针：假绿运行器变体（blocking_failed 恒 0，上游同款缺陷）上合同套件**红**——证明合同抓得住假绿 |
| C5 | 同命令红绿链入账（本仓库信用内核口径） | blocking | 业务链红绿同一条 capture 命令（目标树 env 切换，L05 三重披露同款）；合同链起始红（harness 缺席）→ 实现绿；`workbench-status` `evidence_complete: true` |
| C6 | 检查冻结不被偷偷改动 + L05 能力不回归（上游 §4；L05 C5/C6 承接） | blocking | 客户 `eval/harness.py`+`eval/cases.py` sha256 双树同指纹（L05 机制）；客户 `receiving_is_idempotent` 在目标 clone 照跑绿（登记项之一） |
| C7 | 辨别力边界留痕（L05 C7 同款） | observing | 盲区探针实证入账：缺陷 clone 上客户 `inventory_export_is_stable` 绿、口径交叉检查红——"检查没跑在缺陷可见的形状上"是现行辨别力边界，如实留痕不冒充已覆盖 |

登记项构成（统一 Harness 收口对象，对应上游 ENTRIES 四项）：`l06_stock_consistency`（blocking，口径交叉检查）、`l05_receiving_regression`（blocking，客户 eval 子进程）、`l06_customer_stock_case`（blocking，客户 `stock_never_negative` 子进程）、`l06_frozen_checks`（blocking，指纹复核）、`teaching_observation`（observing，如实标注的教学告警，演示非阻断语义）——五项对上游四项，差异如实记录（上游 `l05_personal_scope` 是其 L05 私有文件，本仓库对应物为指纹复核）。

## 3. 实操流程 + Claude 简报

1. **候选分支 `lesson-06`**：commit 1 = `tests/test_l06_eval_harness.py`（C3 九项）+ `evals/l06/`（口径检查驱动 + 缺陷基线构造脚本）+ **起始红证据**（`evals/harness` 缺席，合同套件 collection error 红；capture + vendor `import_evidence.py` 落账）。
2. **实现转绿**：`evals/harness.py`（`run(entries, *, suite, names, report_path)`，x 模式写报告不覆盖）+ `evals/report_contract.py` → 合同套件绿。
3. **缺陷基线**：`.runtime/course/L06-defect-baseline/`（锚点唯一性校验植入 CSV `{row['available']}`→`{row['on_hand']}`；脚本自检：查询 5、CSV 8）+ 假绿辨别力探针（暂存换入 blocking_failed=0 变体 → 合同套件红 → 恢复；探针目录留痕不删，S02"实现暂存重采真红"先例）。
4. **可信红**：统一运行器收口登记项跑缺陷 clone → `decision: block`、退出 1、库存项定位「查询 5、CSV 8」（同命令 capture，红相位）。
5. **修业务**：clone 内 `flowerp/service.py` export 恢复正确口径（一行）→ diff 落账 → **同一条命令**绿：四 blocking 绿 + 教学观察告警 + `decision: pass`、退出 0。
6. **迁移 + 盲区探针**：13/4→9、10 拒（observation 相位，L05 C6 同款）；盲区探针（C7，客户 eval 绿×2、口径检查红）。
7. **收口**：全量 unittest 绿、`workbench-status` 复核、C6 指纹复核、`code-review` 双轴 → 修复轮（若有）→ **具名验收 → `git merge --no-ff lesson-06`** → roadmap。执行期经 `/mattpocock-skills:implement`（用户显式，到点停下提醒）。

### Claude 简报（第 3 段粘贴用）

> 在 lesson-06 候选分支上：①以 `tests/test_l06_eval_harness.py`（九项运行器合同）采起始红（`evals/harness` 缺席），capture + import 落账；②实现 `evals/harness.py` + `evals/report_contract.py` 转绿（报告 x 模式不覆盖、退出码 = blocking 失败决定）；③`evals/l06/build_defect_baseline.py` 在 `.runtime/course/L06-defect-baseline/` 构造 CSV 口径缺陷 clone（锚点唯一性校验，自检查询 5/CSV 8），假绿变体探针证明合同套件红（辨别力，C4）；④统一运行器收口五登记项跑缺陷 clone：decision block、退出 1、定位查询 5/CSV 8（可信红）；⑤clone 内修 export 一行转正确口径，同命令重跑：三 blocking 绿 + 观察告警 + pass + 退出 0；⑥迁移 13/4→9 与盲区探针（客户 eval 绿/口径检查红）落 observation 相位；⑦证据全部经 capture + vendor `import_evidence.py` 落账，红绿同命令、observed_at 严格递增（C5）。vendors/ 只读；失败记录一律保留。

## 4. 人审清单

- **看哪个 diff**：commit 1 = 合同测试 + 驱动 + 起始红证据（**无** harness 实现）；commit 2 = `evals/harness.py` + `evals/report_contract.py` + 绿证据；vendors/ 零改动；业务"修复"只是 `.runtime` clone 内 `service.py` 一行口径（若出现对本仓库 `flowerp` 业务的大 diff 即红旗——业务实现早已在客户仓库存在，违背客户真理现查）。
- **跑哪些门**：亲手跑 `tests/test_l06_eval_harness.py`；亲手对缺陷 clone 跑统一运行器（应 block/退出 1/定位 5 vs 8）；`workbench-status --require-red-green-evidence`；核对客户 eval 文件当前 sha256 与冻结值一致。
- **什么算作弊**：删/静默教学观察项凑"全绿"；改 report_contract 放过汇总与退出码矛盾；假绿探针只口头声称不入账；迁移用例写死 9；改客户 eval 文件或指纹；报告覆盖旧红报告；跳过"先修汇总"次序直接改业务后一次性采绿（丢失可信红中停点）。

## 5. 候选表（决策点，一次列全，等具名确认）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| D1 | 本讲增量重定位（客户真理现查） | **接受 §1.3 裁决**：增量 = 本仓库 `evals/` 侧统一运行器 + 报告合同（工作台侧共同裁判，L07 Hook 入口）；客户 harness/eval 照跑不重写 | 按上游字面在候选内重造 runner 后即弃（工作台零沉淀，违背合同"共同裁判"语义）；改写客户 harness（违背 ADR-0005） |
| D2 | 缺陷基线构造 | **独立 clone**（L05 D2 先例）`.runtime/course/L06-defect-baseline/`：锚点唯一性校验植入 CSV `available→on_hand`（上游 prepare 同锚点 `service.py:110`） | 改 vendors（铁律 1 禁止）；mock 层演示（测不到真 CSV 导出路径） |
| D3 | harness 落点与脚手架取舍 | **`evals/harness.py` + `evals/report_contract.py`**（报告 schema 1.0 对齐客户 harness，L08 同一 Eval 身份铺垫；报告 x 模式不覆盖）；上游 session/receipt 实践脚手架（`stock_practice.py` 封装）由本仓库 capture + 证据账承担，不复刻 | 落 `workbench/` 新 CLI 命令（L05 D3 先例：本讲场景收益薄）；落 `evals/l06/` 讲次私有（L07 复用要搬，撞上游 `eval.harness` 入口语义） |
| D4 | 登记项构成 | **五项**（§2 末清单）：三 blocking 业务/回归 + 指纹 blocking + 教学观察项 observing | 照抄上游四项名（`l05_personal_*` 名不副实，那是上游 L05 私有文件） |
| D5 | 教学链次序 | **照上游两次修复**：起始红（合同）→ 合同绿 → 假绿辨别力探针 → 可信红（缺陷 clone）→ 修业务一行 → 全绿 → 迁移 | 跳过可信红中停（丢失"先修汇总再修业务"教学点，最终绿说不清来源） |
| D6 | 候选分支机制 | 照旧：`lesson-06`，commit 1 = 合同测试 + 驱动 + 起始红证据 | 直接 master（违背 ADR-0004） |

> **裁决记录（2026-09-28）**：D1–D6 推荐方案**全部接受**。用户原话：「没问题，继续」（批量确认，逐字入账）。起草日现查：CodexFDE pin `7f67533` 未移动（新 tag `course-package-20260928` 即指向本 pin，无检查点动作）；flowERP `e0088d3` 客户真理事实核实成立——`eval/harness.py:47` `run_suite` 统一分级/报告/退出码在场、`stock_never_negative` blocking 用例在场、`inventory_export_is_stable` 数据 reserved=0（盲区预计，探针执行期实证）、`service.py:110` `{row['available']}` 锚点唯一命中。

## 附录 A：Java 重走移植注记（ADR-0006）

> 状态：**审定稿**（2026-09-30 用户审定：「没问题，继续」批量确认——A6.1 全部移植裁定 + A6.2 JD1–JD8 推荐口径 + A3 golden 方案全案）。分支内四处文档侧修正（A3 s09 面注记、「8 类拒绝」→11 处 ×2、A4 步骤 3 回写）均为行为零变化，逐项见证据账 §G 修正记录与 §13 复查轮呈报。决策与词汇见 ADR-0006 与 CONTEXT.md（重走 / 移植注记 / 对照基准）。§1–§5 为 Python 口径的历史对照基线，不重写；本附录只记录 Java 重走侧的翻译点与增量。重走证据落 `docs/replication/evidence/L06-java.md`（原 `evidence/L06.md` 不重开）。上游现查（2026-09-30，本附录起草日 fetch）：`origin/main` = `7f67533`，与本讲对照基线一致（检查点 0002 后无新变更，冻结合同零变化），不触发检查点采纳；flowERP 现查（submodule pin `e0088d3`，与 Python 时代本讲现查同 pin）：`run_suite` 统一分级/报告/退出码在场、EVALS 19 用例在场（`inventory_export_is_stable` / `stock_never_negative` / `receiving_is_idempotent` 均 blocking）、`flowerp/service.py` export_inventory CSV 模板 `{row['available']}` 锚点现查唯一命中 1 次。

### A1 讲解：本讲重建什么

L06 是重走线**最后一讲**，也是增量最重的一讲：Python 时代交付 `evals/harness.py`（统一运行器）+ `evals/report_contract.py`（报告合同，review 缝）+ `evals/l06/` 三件（五登记项收口 `checks` / 口径交叉检查驱动 / CSV 缺陷基线构造器）——CLAUDE.md 待建设清单点名的「统一运行器（L06-java 正题）」在本讲清账。Java 侧重走 = 在 Java 线**建成**全部对应物（`workbench/evals/` 根两件 + `workbench/evals/l06/` 四件）+ 按 C1–C7 重演证据链（含假绿辨别力探针与教学链次序，不补拍）+ golden l06 字节锁。`evals/` 整目录是 Python 冻结面，Java 线零触碰、不复用其实现。

**本讲特有的结构翻译点（与 L05 方向相反的翻译 + 一处纯库直译）**：① 统一运行器本体（`harness.py`/`report_contract.py`）是**纯库、无子进程依赖**——Java 直接同形移植为 `EvalHarness`/`ReportContract`（进程内库语义，L07 Hook 入口对应物，报告 schema 1.0 与键序逐字）；② `checks` 的 stock 登记项在 Python 经**子进程再跑 driver**（`sys.executable driver.py`），Java 改为**进程内调用**驱动逻辑（JVM 不自嵌子进程）——与 L05 的「in-process import → 子进程」方向相反，但 evidence/error 格式（失败时 `AssertionError("rc=1: " + 末行[:2000])`、evidence=异常消息、`error.type` 记 `AssertionError`）与 Python 子进程路径**逐字节同形**，如实披露不伪装；③ 口径驱动与构造器自检沿用 L05 的子进程 python 探针模式——新 `StockProbe`（单方法缝，`FlowerpProbe` 同款先例），**不动 C5 已冻的 l05 三源件**；④ 客户用例登记项子进程照跑 `python -m eval.harness --case X --no-report`（cwd=目标树，末行原样入 evidence）。

**假绿辨别力探针在 Java 线真实重演（handoff ③）**：上游教学缺陷「运行器漏算阻断失败（`blocking_failed` 恒 0）」由**暂存换入 Java `EvalHarness` 的变体**（工作树临时修改、永不提交）真实注入 → 合同套件真实红（失败用例名留痕）→ 恢复 → 探针封条留痕不删。教学点「合同抓得住假绿」在 Java 载体重证；教学链次序（D5）逐段真实落账：起始红 → 合同绿 → 假绿探针 → 可信红（缺陷树 `decision: block` / 退出 1 / 定位 查询 5 vs CSV 8）→ 绿（候选树四 blocking 绿 + 观察告警 `pass` / 退出 0）→ 迁移。

**完成即重走线收口**：L06-java 具名验收后，Python 载体按 ADR-0006 尾款退役（git tag + 移出工作树）。退役作为**验收后的独立收口动作**单独确认执行（细则见 A6.2 JD8），不混入本讲候选分支写集。

**不重建**：`course-status` 等上游命令（待建设清单另行推进）；客户 harness / 客户 eval 的任何改写或补弱（客户真理照跑）；报告 schema 变更（1.0 对齐客户为 L08 铺垫，不动）；上游 `stock_practice.py`/session 脚手架（D3 已裁：由 capture + 证据账承担，承袭）。本讲无 B 票、无执行器会话（Python 时代同——无 `claude -p` 调用，如实声明）。

### A2 合同翻译（C1–C7）

| # | Python 口径（§2 原文） | Java 重走口径 | 变化类型 |
|---|---|---|---|
| C1 | 可用 = 在手 − 预占（查询与 CSV 同口径） | **Java 口径驱动** 8/3：查询与 CSV 同为 `[8,3,5]`（预期由 8−3 在 Java 独立计算，不抄返回值）、CSV 恰一条该 SKU 记录；迁移 13/4 → `[13,4,9]`、请求 10 拒绝且状态不变（排除写死 5）；驱动输出与冻结 Python 逐字节同形（golden l06 锁） | 重演（驱动换 Java 载体） |
| C2 | 超额预占拒绝且状态不变（绑定 eval `stock_never_negative`） | 驱动 AC-REJECT / AC-UNCHANGED（四表快照锚点=建单后，R1 终态照抄）；客户 blocking 用例经 Checks 登记项 `l06_customer_stock_case` 子进程照跑绿 | 重演 |
| C3 | 统一运行器分级语义（九项运行器合同） | `EvalHarness` 九项语义合同测试（映射源 = 上游 `test_runner_contract.py`，与冻结 Python 测试头同源）：通过+报告落盘、blocking 一败即 block+退出 1、observing 只告警、异常保留类型+原因且继续、observing 异常不升格、非法选择拒绝、子集显式、x 模式不覆盖、消费端拒绝矛盾 | 重演（载体 Java；进程内直调 `run()`——Python 时代复查轮 S6 同款口径：合同面限 eval.harness 缝，非 workbench.cli 缝） |
| C4 | 阻断失败、decision、退出码三者一致 + 假绿辨别力 | `ReportContract` 校验（11 处拒绝词面逐字）；假绿探针：`blocking_failed=0` 变体换入 → 合同套件真实红（探针封条留痕）；报告三态一致由 golden s07/s08 **字节级**锁定（含 `decision: block` 红报告全文） | 重演 |
| C5 | 同命令红绿链入账 | 同一条 `sh -c` 命令串（`$L06_EVAL_TARGET` 切换目标树，echo 行 + 封存 argv + 证据账三重披露，L05 逐字承袭），`capture_evidence.py --submission-root lesson-06-submission/java` 采集 → Java `ImportEvidence` 导入 → `CASE-WB-L06-JAVA-001`；`workbench-status --require-red-green-evidence` 链判定完整 | 重演（账本/导入器 Java 载体） |
| C6 | 检查冻结不被偷偷改动 + L05 能力不回归 | 冻结 sha256：客户 `eval/harness.py`+`eval/cases.py` 双树同指纹（与 Python 时代冻结值一致）+ 双树 `flowerp/service.py`（缺陷在场/候选干净，L05-java 先例超点名）+ **Java 六源件**（EvalHarness/ReportContract/Checks/StockConsistencyCheck/BuildDefectBaseline/StockProbe）；初冻 + 收口复核前后逐字节一致；L05 回归 = Checks 登记项 `l05_receiving_regression`（客户 `receiving_is_idempotent` 子进程照跑）+ mvn 全量既有合同与 golden l01–l05 五重放持续在场 | 重演（冻结对象含 L06 Java 工具源） |
| C7 | 辨别力边界留痕（盲区实证） | 盲区探针：客户 `inventory_export_is_stable` 对**缺陷树**绿 ×2（rc 0 封条）+ 本方检查同树红（链 B 红即证）——「检查没跑在缺陷可见的形状上（reserved=0 数据）」如实留痕；未覆盖边界（并发预占/进程中断/多 SKU 多仓）照 Python §3.3 口径入账；是否补 reserved>0 形状的客户用例留检查点显式采纳 | 重演 |
| — | （Python 时代无此项） | **golden 对照门**：golden l06 = 冻结 Python 工具采出预期、Java 工具重放字节级一致（A3）；重放测试 commit 1 红为本讲预期红点；**golden 面首次含完整运行器报告**（掩码扩展首例）与 **env 注入场景字段**（共享件扩展） | 重走线增量 |

### A3 对照基准（golden）方案

生成（审定后、implement 授权前；冻结 Python 侧仍可跑。工具 `tools/generate_golden_l06.py`，镜像 l05 生成器，只用标准库）：

- 工作区 `.runtime/golden-l06/`（raw 落 gitignore）；输出 `src/test/resources/golden/l06/`；canonical JSON 规则与 l01–l05 逐字相同；manifest 保留 normalization 块
- **golden 语义适用面（handoff ①，起草时定）**：本套锁**我方工具输出面**——生成侧用冻结 Python `evals/` 工具（只执行不修改）采出预期，重放侧 Java 对应物字节级一致。**报告文件不入 golden**：checks 的 stdout 即报告全文（Python 两处 `json.dumps/report` 同字节），stdout 面已完整承载报告内容；文件面（x 模式拒覆盖、file==stdout、旧证据不波及）由 Java 合同测试锁（Python test_01/test_08 同位）——`GoldenReplay` 无文件对照缝，为其扩展共享件收益为负
- **掩码：本套扩展 2 键**（l01–l05 首例扩展，机制 l04 起已按 manifest 数据驱动）：`generated_at` → `<TS>`、`duration_ms` → `<MS>`（s07/s08 报告场景的机器时间字段；其余场景无这些键，键级掩码无扰）；三件套默认声明保留，口径逐字写入 manifest `mask_note`
- **setup 块（双侧同规格）**：`ws/clean`（git clone vendors/flowERP 相对路径）+ `ws/no-flowerp`（普通目录）；缺陷树 `ws/b1` 由 s01 场景自身构造（生成侧 = 冻结 Python 构造器，重放侧 = Java 构造器——双侧树等价的第一重锁），**无 l05 盲区树式的预物化夹具**，重放测试清场后直接 `replay()` 即可
- **场景面（定稿 9 场景，全清单以 manifest 为准）**：

| 组 | 场景 | 预期 rc | 覆盖面 |
|---|---|---|---|
| 构造器 | s01 `--baseline ws/b1` 成功（自检 JSON：`defect_live:true`/`query_available:5`/`csv_available:8`） | 0 | C7 缺陷基线构造面 + 双侧树等价第一重锁 |
| 构造器 | s02 基线已存在拒绝（s01 产物在场，stdout 空） | 1 | 防覆盖纪律 |
| 驱动 | s03 `--target ws/clean` 默认 8/3 pass（`[8,3,5]`、拒 6、状态不变） | 0 | C1/C2 主干 |
| 驱动 | s04 同树 `--opening 13 --reserved 4` pass（`[13,4,9]`、拒 10） | 0 | C1 迁移 |
| 驱动 | s05 `--target ws/b1` fail（`AC-AVAILABLE`：query `[8,3,5]` / csv `[8,3,8]` + requirement 逐字） | 1 | C1 定位（数值级） |
| 驱动 | s06 `--target ws/no-flowerp` 拒绝 | 2 | 参数校验面 |
| 运行器 | s07 checks（env `L06_EVAL_TARGET=ws/clean`）`--no-report`：五项报告（四 blocking 绿 + 教学告警，`decision: pass`） | 0 | **C3/C4 统一运行器绿面**（报告全文字节锁） |
| 运行器 | s08 checks（env `L06_EVAL_TARGET=ws/b1`）`--no-report`：`l06_stock_consistency` 红（evidence=`rc=1: {AC-AVAILABLE …}`）+ `decision: block` | 1 | **C4 可信红面**（红报告全文字节锁） |
| 运行器 | s09 checks 无 env：全项失败报告（stdout 即报告全文） | 1 | `L06_EVAL_TARGET` 必设面——目标树解析在登记项内（冻结 `_target()` 逐项调用同形）：四项 RuntimeError 入报告 + 教学项 AssertionError，`decision: block`；commit 1 真实红（期望报告 vs 主类缺失空 stdout） |

- **共享件行为零变化扩展（JD4，前置落 master）**：`GoldenReplay` 场景支持可选 `env` 字段（map；s07/s08 用——Checks 读环境变量是冻结 Python 语义，加 `--target` 参数即偏离 argv 面）+ `Cli.runMainWithEnv`（**独立方法名**，J2 重载两可教训；旧 `runMain` 委托不变）；l01–l05 五套 manifest 无 `env` 字段 → 输出逐字节不变，前置提交 `mvn test` 91/91（含五套旧重放）为行为零变化证据
- s01 与 s05/s08 构成顺序叙事（s01 构造产物即 s05/s08 被测树；生成侧同构：Python 构造器产物 + Python 驱动/checks）
- **不入 golden（任何侧）**：客户 eval 直跑输出（客户真理非我方表面；盲区探针走证据链 observation）；工具 stderr 错误词面（`SystemExit` 中文消息 vs Java 异常词面按 JD6 语言绑定如实偏差，stdout 空 + rc 对照）；Python traceback 形状；假绿变体（探针走证据链，变体永不入 git）
- 重放时长预算：9 场景 × JVM 子进程 + 3 次 clone（setup / s01 / 无）+ s07/s08 各含 3 次 python 子进程与 1 次探针 ≈ 三十至六十秒增量，可接受
- 幂等双跑，golden 目录 SHA-256 两次一致入 §G——**首跑即留指纹**（L03 教训）；golden + 生成器 + 共享件扩展 + §G 记录 + 本附录审定稿前置落 master（L01–L05 先例），候选分支自 master 分出即继承；生成后永不手改

### A4 实操流程（增量于 §3）

```bash
# 0. 审定后、动手前（前置落 master，L01–L05 先例）
#    - 本附录审定稿入讲义 → master
#    - GoldenReplay 场景 env 字段 + Cli.runMainWithEnv（行为零变化，mvn test 91/91 回归证据）
#    - tools/generate_golden_l06.py + golden/l06（双跑指纹，首跑即留）
#    - evidence/L06-java.md §G → master
# 1. git checkout -b lesson-06-java
# 2. 【配合点 1 复认】to-spec 降级：每次重新确认（L02–L05-java 先例），起始红采前问
# 3. commit 1 = L06 Java 合同测试（L06EvalContractTest 工具面：构造器拒绝/锚点拒绝
#    （--source 变异源 discharge，l05 修正注① 同款）/驱动参数校验/checks 缺 env/
#    输入校验）+ GoldenL06ReplayTest + 起始红封条——九项合同与 ReportContract 拒绝面、
#    客户报告交叉验证因 Java 编译绑定随实现落 commit 2（对应面起始红由 golden
#    s07–s09 工具级承载；Python 同位面为 collection error 红；复查轮 S-1 回写，§R2 披露）
#    （tools/capture_evidence.py --submission-root lesson-06-submission/java red -- mvn test）
# 4. 【配合点 2】implement：用户显式调用后动笔——workbench/evals 根两件
#    （EvalHarness：分级/报告/x 模式/退出码；ReportContract：11 处拒绝词面）+ l06 四件
#    （Checks/StockConsistencyCheck/BuildDefectBaseline/StockProbe）；不进 REGISTRY、
#    零新 CLI 命令、Ledger 零扩、pom 零改动；自调 codebase-design + tdd；遇障自调
#    diagnosing-bugs
# 5. diff + green：mvn test 同命令，observed_at 严格递增；采集根 lesson-06-submission/java
# 6. 证据链重演（C4/C6/C7，现场全用 -java 新根勿混用）：
#    a. 假绿探针：暂存换入 EvalHarness blocking_failed=0 变体（不提交）→ 合同套件红
#       （失败用例名留痕）→ 恢复 → 探针封条 06-observations 保留
#    b. Java 构造器产 .runtime/course/L06-defect-baseline-java/（自检 JSON 终端留痕）
#       + .runtime/course/L06-candidate-java/（干净 clone）
#    c. 链 B：红（L06_EVAL_TARGET=缺陷-java 树：decision block / 退出 1 / 查询 5 vs
#       CSV 8）→ diff（git -C 缺陷树 diff，CSV 缺陷补丁——与 Python 时代 record 63/65
#       同形）→ 绿（同串，L06_EVAL_TARGET=候选-java 树：四 blocking 绿 + 告警 pass / 0）
#    d. 迁移 observation：Java 驱动 --target 候选-java --opening 13 --reserved 4
#    e. 盲区探针 ×2：客户 inventory_export_is_stable 对缺陷树（rc 0 封条 ×2）
#    f. 指纹初冻（C6 12 路径：双树客户件 ×2 + 双树 service.py ×2 + Java 六源件）
#    g. 交叉验证 observation：客户报告过 Java ReportContract（合同测试方法实跑）
#    h. 集成记录：./bin/wb workbench-task-run CASE-WB-L06-JAVA-001 --workspace 候选-java
#       --mode verify --eval-command "<java Checks 命令，Python record 74 同形；
#       env 经父进程注入>" --execution-timeout 900 --actor <向用户索取>
#    i. Java ImportEvidence 导入（红→diff→绿顺序；观察类绿后导入）→ status 密封四词面
#    j. C6 收口复核（同 12 路径逐字节一致）；全量回归即绿命令本体（mvn test）
# 7. code-review 双轴自调（master...lesson-06-java）→ test-first 修复、旧证据保留 → 触点 3
#    具名验收 → git merge --no-ff lesson-06-java（合并信息含验收人）→ roadmap 重走线行
#    + 讲义导航 + CLAUDE.md 待建设清单口径收口（统一运行器建成；course-status 等仍待建）
# 8. （验收后、独立收口）Python 载体退役（ADR-0006 尾款）——JD8，单独确认再执行
```

纪律注：`.idea/` 与仓库根 `java/` 归置仍非本讲写集；`git add` 精确路径不用 `add -A`；源码不可见 Unicode 一律显式转义（探针脚本 BOM 处理用显式转义 `U+FEFF`，可见 CJK 字面有 L04/L05 先例背书）；L05 现场勿混用（`.runtime/course/L05-*-java/` 已占用，本讲全用 `L06-*-java` 新根）；退出码采集纪律（L05 J2 教训）：命令重定向落日志 + `echo $?`，勿以管道尾部值判 rc，失败现场一律保留。

### A5 人审清单增量（叠加 §4）

- [ ] **Python 冻结面零触碰（硬检查）**：`git diff master...lesson-06-java -- evals workbench tests pyproject.toml tools/capture_evidence.py` 为空；`.runtime/course/L01-workbench/`（Python 账本）零写入；pom.xml 零改动（如偏离逐条说明）
- [ ] golden l06 双跑指纹在场（首跑即留）；共享件 env 字段扩展行为零变化（前置提交 91/91 回归 + 五套旧重放绿）；五套旧 golden 文件零触碰；l06 golden 非手改；重放测试 commit 1 红在场
- [ ] 红绿同命令：`sh -c` 命令串逐字节相同（`$L06_EVAL_TARGET` 切树 + echo 行 + 封存 argv + 证据账三重披露）；假绿探针真实（变体换入前后合同套件红绿对照、封条留痕、变体不入 git、无补拍）
- [ ] 冻结与收口：初冻 + 收口复核 sha256 前后一致（12 路径：双树客户件 + 双树 service.py + Java 六源件）；客户 eval 文件零改动；教学观察项在场未被删凑全绿（绿腿 `observing_failed: 1, decision: pass`）
- [ ] Java 工具输出与冻结 Python 逐字节一致（golden 9 场景 + 实跑观察记录）；结构翻译差异（checks 进程内调驱动 / 探针子进程 / stderr 词面）如实披露不伪装
- [ ] 链入 `CASE-WB-L06-JAVA-001`（owner/actor 用户具名，不代填）；mvn 工具建设链封条随分支 commit（JD7 口径，不入任务账）
- [ ] 非目标守住：不改客户 eval/不补盲区用例（留检查点）、`course-status` 等上游命令不在本讲、Python 退役不在候选分支内做（JD8 独立收口）
- [ ] 红旗（新增）：为凑字节对照伪造 Python 词面/时间戳/路径；报告覆盖旧红报告（x 模式被绕过）；假绿探针只口头声称不入账；删/静默教学观察项凑「全绿」；跳过「先修汇总再修业务」次序直接采绿；golden 手改；`evals/` 被写入

### A6 裁定表（随触点 1 逐条裁定，未确认不动笔）

**A6.1 Python 时代裁定移植表**：

| 裁定 | 处置 | Java 口径 |
|---|---|---|
| D1 增量重定位（客户已有统一 harness；增量 = 本仓库统一运行器 + 报告合同） | 移植 | Java 线建成对应物（待建设清单 L06-java 正题清账）；客户 harness/eval 照跑不删弱不改写 |
| D2 独立 clone 缺陷基线（CSV `available→on_hand`，锚点 `service.py` 唯一） | 移植 | `.runtime/course/L06-defect-baseline-java/`（-java 后缀防跨时代冲撞）；候选 `.runtime/course/L06-candidate-java/` |
| D3 harness 落点与脚手架取舍（`evals/` 根两件；不进 workbench CLI；`stock_practice` 不复刻） | 移植 | `workbench/evals/` 根两件（L07 Hook 入口对应物）；不进 REGISTRY、零新 CLI 命令、Ledger 零扩；脚手架仍由 capture + 证据账承担 |
| D4 五登记项（对上游四项，差异如实记录） | 移植 | 同名同级五项；`l05_receiving_regression` = 客户 `receiving_is_idempotent` 子进程照跑 |
| D5 教学链次序（先修汇总再修业务；可信红中停） | 移植 | Java 真实重演：起始红 → 合同绿 → 假绿探针 → 可信红 → 绿 → 迁移，逐段落账不补拍 |
| D6 候选分支机制（`lesson-06`） | 移植 | `lesson-06-java`，单任务 `CASE-WB-L06-JAVA-001` |
| R1 快照锚点修正（AC-UNCHANGED 拍在建单后） | 终态直接采用 | 冻结驱动已是修后形态，Java 照抄；失败现场不重演（Python 证据账 §3.1 在案） |
| 复查轮终态（S3 命名 / S4 必传参） | 终态承袭 | Java 对应物直接以终态形态落（`expected_available`/`expected_triple` 命名、`entries` 必传 `opening`/`reserved`） |
| 报告 x 模式不覆盖旧证据 | 移植 | `CREATE_NEW` 同语义（`FileAlreadyExistsException` 即拒绝）；写失败不波及旧文件 |
| 「修业务一行」链形态 | 口径澄清 | 照 Python 台账逐位同形：红=缺陷树 / diff=缺陷补丁（该业务行）/ 绿=候选树；不对干净候选树另做编辑表演（凭空制造账面 diff 污染证据） |
| 九项测试进程内直调 `run()`（复查轮 S6 口径） | 承袭 | Java 合同测试同款进程内直调（eval.harness 缝单独列名，非 workbench.cli 缝） |
| tdd 口径 | 承袭 | Python 时代已有 unittest 起始红（链 A）；Java 合同测试起始红同形，tdd 红绿成立 |

**A6.2 Java 时代新决策**（候选，推荐口径随触点 1 批量裁定）：

| # | 决策 | 推荐 | 备选 |
|---|---|---|---|
| JD1 落位与包结构 | `workbench/evals/` 根：`EvalHarness` + `ReportContract`（跨讲统一运行器，L07 入口）；`workbench/evals/l06/`：`Checks`（五登记项收口 main）+ `StockConsistencyCheck`（口径驱动 main）+ `BuildDefectBaseline`（CSV 缺陷基线构造器 main）+ `StockProbe`（探针，单方法缝——`FlowerpProbe` 同款先例，**不动 C5 已冻的 l05 三源件**）。参数面：Checks `--report-path/--no-report/--opening 8/--reserved 3` + env `L06_EVAL_TARGET`（词面逐字）+ `--python`（默认 `.venv/bin/python`，结构性必需，l05 JD1/S-2 同款）；驱动 `--target` 必填 + `--opening/--reserved` + `--python`；构造器 `--baseline`（默认 `-java` 路径）+ `--skip-self-check` + `--source`（默认 `vendors/flowERP`，锚点守卫可测缝——l05 修正注① 先例）+ `--python`。不进 REGISTRY、pom 零改动 | 塞 bootstrap/tools 包（分层失裕/语义混置）；Checks 兼收 `--target` 参数（偏离冻结 argv 面，env 语义逐字优先） |
| JD2 驱动缝与结构翻译 | StockProbe 单方法：stock 场景一次回报全部原始观察值（查询三元组 / CSV 该 SKU 行数与三元组 / 超额预占是否拒绝 / 四表快照前后），**判断与预期计算留 Java**（8−3=5 独立算出）；探针脚本 BOM 处理显式 `U+FEFF`（Unicode 纪律）；子进程超时 300s（对齐 `SUBPROCESS_TIMEOUT`；冻结 `FlowerpProbe` 无超时——不动它，新缝自带）。Checks 的 stock 登记项**进程内调用**驱动逻辑（Python 为子进程再跑 driver，方向与 L05 相反的结构翻译）：驱动可调用面返回 (rc, 末行)，失败 → `AssertionError("rc=1: "+行[:2000])`——evidence/error 与 Python 子进程路径逐字节同形，如实披露。客户用例登记项：子进程 `python -m eval.harness --case X --no-report`（cwd=目标树），末行原样入 evidence（客户汇总行无时间字段，字节稳定），rc≠0 → 同款 AssertionError。指纹登记项：Java MessageDigest 直算（无子进程），词面逐字（`冻结检查指纹一致: …=12 位前缀`；失配 `冻结检查被改动: {json}`）。教学观察项：`AssertionError("教学观察项：模拟非阻断提示，不代表实际缺陷")` 逐字 | Checks 子进程再起 java 跑驱动（JVM 自嵌，重而无增益）；Java 内嵌 Python 复刻判断（违背独立计算，不可取） |
| JD3 golden l06 方案 | A3 全案：9 场景（含运行器绿/红报告面 s07/s08 + 缺 env s09）+ 掩码扩展 2 键（`generated_at`/`duration_ms`——l01–l05 首例扩展，机制已数据驱动）+ setup 干净 clone + s01→s05/s08 顺序叙事 + 报告文件不入 golden（stdout 即报告字节，文件面由合同测试锁）+ 客户 eval 直跑/stderr 词面/traceback/假绿变体不入 golden + 首跑即留指纹 + 前置落 master | 缩面不锁运行器报告面（本讲主交付物无字节锁，回归防护降级，不可取）；golden 含报告文件对照（共享件无文件对照缝，扩展收益负） |
| JD4 golden 共享件扩展 | `GoldenReplay` 场景可选 `env` 字段（map，s07/s08 用）+ `Cli.runMainWithEnv`（**独立方法名**——J2 重载两可教训；旧 `runMain` 不变）；l01–l05 五套 manifest 无该字段 → 行为逐字节不变；前置落 master 带 91/91 回归（含五套旧重放）证据 | GoldenL06 自带重放循环（重复逻辑，l04 JD2 已否决同款）；Checks 加 `--target`（偏离冻结面） |
| JD5 假绿探针机制 | 工作树暂存换入 `EvalHarness` 的 `blocking_failed=0` 变体（**永不提交**）→ `mvn test -Dtest=<L06 合同类>` 真实红（失败用例名留痕）→ 恢复 → 探针封条 06-observations 保留（Python record 60 同形） | 仅测试内构造假绿桩（弱化「真运行器被合同防住」的教学叙事，不取） |
| JD6 已知行为边界与格式口径 | raw 报告打印 = `PyJson.dumps`（indent=2、插入序，`json.dumps(indent=2)` 同形）；`generated_at` 的 Java ISO 形状与 Python `isoformat` 微差（尾缀/小数位）——掩码内不入对照、如实披露；九项合同测试进程内直调 `run()`（S6 口径承袭）；工具 stderr 错误词面语言绑定偏差不入 golden（stdout 空 + rc 对照）；Checks 缺 env / 探针失败等异常路径 rc 1 + stderr 词面逐字、traceback 形状不复刻 | 在 Java 侧拼装 Python 时间戳/词面（伪装同形，不可取） |
| JD7 链入账口径 | `CASE-WB-L06-JAVA-001` 装 **checks 同命令链**（C5 字面：`sh -c 'echo "target: $L06_EVAL_TARGET" && exec java -cp … workbench.evals.l06.Checks --no-report'`，红缺陷树/绿候选树）；观察类（假绿探针/迁移/盲区 ×2/指纹/交叉验证/verify/status）绿后导入；mvn 工具建设链红/diff/green 封条随分支 commit 1/2，不入任务账（l05 JD7 同款：避免多命令混链） | 全部混装一任务（链判定多命令语义未验证，不可取） |
| JD8 收口与 Python 退役 | 收口三件：roadmap 重走线行（L06 完成 = 重走线全完成）+ 讲义导航 + CLAUDE.md 待建设清单口径（统一运行器建成除名；`course-status` 等仍待建设）。**Python 载体退役（ADR-0006 尾款）为验收后独立收口动作**：tag 冻结基线 → 移出工作树；范围 = Python 实现冻结面（`workbench/`、`tests/`、`evals/`、`pyproject.toml`）；保留 = `tools/capture_evidence.py`（Java 线采集仍在用，仅标准库自足）、`lesson-*-submission/`（永不移动）、`docs/`（只追加）、`.runtime/` Python 时代账本（只读封存）、vendors/ 不动；CLAUDE.md 常用命令段同步；退役细则与 tag 名随验收后单独确认再执行 | 退役随候选分支一并做（写集混装、验收粒度失控，不取）；退役后拖不定期（ADR-0006 明文到期退役，双轨纪律本意） |
