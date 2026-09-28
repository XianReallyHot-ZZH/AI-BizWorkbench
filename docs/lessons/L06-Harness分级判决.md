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
