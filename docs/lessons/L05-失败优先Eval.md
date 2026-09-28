# L05 讲义：先设计失败，再编写 Eval

- 状态：**定稿 v2**（D1–D5 与执行期现查翻案 R1–R4 均已裁决，见 §5 两级裁决记录；用户审完才动手）
- 起草：2026-09-28；上游 pin `CodexFDE@7f67533`（起草日与裁决日两次 fetch 现查均未移动，无检查点动作）
- 合同来源：`workbench/course_contracts.py` LESSONS[number=5]（冻结合同，逐字引用不修）
- 上游参考：`vendors/CodexFDE/docs/courses/L05/辅导资料.md`（只读对照）
- 本讲性质：**客户真理重大现查**——讲义形状因此与上游课程有偏离，见 §1.3 与候选表 D1

## 1. 讲解

### 1.1 本讲要什么

L04 完成了"有检查、有人工决定"的库存导出交付。L05 的问题是下一层：<strong>你的检查器自己会错吗？</strong>——检查在正确程序上通过，只说明它没报错；要知道它能不能抓住错误，必须让它检查一个**已知带缺陷的版本**，看它是否真的报红。0004 课留的引子在这里接上。

### 1.2 业务案例：幂等收货

一批货 8 件，操作员页面没响应又点一次——系统会不会记成两批？识别重试靠**幂等键**：第一次用 A，重试仍用 A；真正的新一批才用 B。业务要求（FlowERP 边界 #2，CLAUDE.md 逐字）：<strong>同一个入库幂等键只能生效一次</strong>——既不重复加库存，也不重复留流水。预期独立计算（20+8=28，不是从程序返回值抄），这是 Eval 与"被检查者自己出答案"的分界线。

### 1.3 客户真理现查（讲前 2026-09-28；执行期探针补正同日，见 §5 裁决记录二）

按 ADR-0005 现查 `vendors/flowERP`（只读）：

1. `flowerp/inventory.py:102` `InventoryService.receive` **已实现幂等**：`event_key` 重放直接返回余额（`idempotent_replay: true`），不二次入账、不二次写 `stock_moves`；
2. 客户自己的 eval 套件里 `receiving_is_idempotent` **已是 blocking 用例**（`eval/harness.py:27`、`eval/cases.py:46`）；
3. 【执行期补正 R1】eval 的**实际被测路径**是门面 `ERPService.receive_stock`（`flowerp/service.py:150`）——独立的幂等实现（`inventory_events.event_key` 主键 + 事件短路，`stock`/`inventory_events` 表）。`inventory.py:102` 那层（`stock_moves`/`stock_balance`）也幂等，但 **eval 不经过该层**（探针实证：在该层注入缺陷，eval 不变色）；
4. 【执行期补正 R2 依据】`inventory_events.event_key` 是 **PRIMARY KEY**（`store.py:41`）——上游课堂同款窄缺陷「重试多写流水、返回值仍正确」在真实路径上**不可构造**：同键重复流水行被 schema 直接禁止，客户的幂等正是靠主键+短路实现的。

**结论**：冻结合同写的"先构造失败 Eval，再实现幂等"，其"实现"半边在真实客户里已存在。我们不删弱客户检查、不装作从零实现（那违背客户真理与诚实口径）；本讲增量如实转为——<strong>证明检查有辨别力（缺陷基线两连红），再证明真实候选绿（同命令），最后冻结检查防止"改标准凑绿"</strong>。上游辅导资料同样预见此情形："个人课程候选也可能已经能报红，不必为了复现'旧检查绿'而删弱原检查；课堂对照与个人起始状态分别记录。"执行期探针补正（R1–R4）：缺陷基线落在门面层「重试再次入账」（库存重复入账、流水未重复）；并实证客户 eval 的辨别力边界——对「账面重写」（删旧键写新键、库存返回正确）盲（绿×2），该边界如实入 C7 留痕，本讲不改客户文件。

### 1.3.1 因果交接（本讲为下一讲准备什么）

L06 用 Harness 把多项检查分级汇总（LESSONS[5] number=6：用 Harness 汇总证据和等级）。本讲留下的"业务 Eval + 工程约束 Eval + 冻结指纹"就是 L06 汇总的对象；eval 用例身份（`receiving_is_idempotent`，blocking）从本讲起固定，本地/远端同一身份是 L08 收口目标。

## 2. 本讲合同（C 编号清单）

blocking / observing 标注；【现查】= 按客户真理调整后的口径。

| C | 验收项（来源） | 类型 | 怎么验 |
|---|---|---|---|
| C1 | 首次入库增加库存（合同 acceptance[0]） | blocking | 客户 blocking 用例（首次+重放主干）+ 驱动场景 20/8/8：期初 20，A 收 8 → 库存 28，新增 A 流水一条（驱动逐条断言流水） |
| C2 | 同一幂等键重放不再次增加库存（acceptance[1]） | blocking | 客户用例（A 重放不变）+ 驱动场景：A 重放 → 仍 28 且流水不增；B 新收 → 36（防"拦掉一切"）；0/负数拒绝且状态不变 |
| C3 | 缺陷基线两连红（上游通过标准："缺陷基线连续运行两次均失败"；【现查】红来自基线非真实客户）【R3 修订】 | blocking | 同一 eval 对已知坏候选（门面重放分支重执行入账：库存重复、流水未重复）从干净数据连跑两次，均失败且失败信息定位到具体要求（case 名 + `second["on_hand"]` 断言 → FlowERP 边界 #2）；上游课堂窄缺陷（同键多写流水）真实路径不可构造（event_key 主键），schema 事实入证据账 |
| C4 | 同命令红绿链入账（合同 acceptance[2]"保留修复前红灯和修复后绿灯"→ 本仓库信用内核口径） | blocking | 红与绿同一条 eval 命令（目标树经 `$L05_EVAL_TARGET` 切换，pwd 行+封存 argv+证据账三重披露），经 `import_evidence.py` 落 red/green 相位，`workbench-status` 链判定完整 |
| C5 | 工程约束：检查冻结不被偷偷改动（上游 §4"防止为了通过而改标准"） | blocking | 冻结 eval 文件 sha256（客户 `eval/harness.py`+`eval/cases.py`+本仓库驱动，双树同指纹一并核）；候选收口时复核指纹未变；改动检查须单独说明并保留旧结果 |
| C6 | 换数据迁移检查（上游 §5：11→14→14→17） | observing | 驱动场景 `--opening 11 --receipt 3 --new 3`：11→14→14→17 且流水 1→2→2→3，预期独立计算，排除"永远返回 28"式写死答案 |
| C7 | 边界留痕（上游 §5"把边界留在交接记录"：并发/进程崩溃/同键不同内容）【R4 增强】 | observing | 未覆盖边界如实写入证据账，不冒充已覆盖；实证盲区入账——账面重写（删旧键写新键、库存正确）下客户 eval 绿×2：检查不读流水是现行辨别力边界，驱动场景可抓（对照实验入账） |

无效数量（0/负数）拒绝场景：门面 `receive_stock` 已有 `quantity <= 0` 校验（service.py:152），并入驱动场景（C2 同包），不单列 C 项。

## 3. 实操流程 + Claude 简报

1. **候选分支 `lesson-05`**：commit 1 = 缺陷基线 + eval 驱动脚本 + **红证据**（C3 两连红）。
   基线位置：`.runtime/course/L05-defect-baseline/`（flowERP clone + `evals/l05/build_defect_baseline.py` 构造：门面重放分支重执行 `UPDATE stock`——重试再次入账，on_hand 5→10、流水不重复；构造脚本含缺陷生效自检，**永不合入**，仅作检查的靶子）。
2. **eval 接入工作台**：eval 命令经 `workbench-task-run --eval-command` 在候选 cwd 实跑客户 blocking eval（`python -m eval.harness --case receiving_is_idempotent`）；红绿经 vendor `import_evidence.py` 落账（C4；capture 命令列表红绿逐字节相同，目标树经 `$L05_EVAL_TARGET` 切换）。
3. **冻结与修复验证**：冻结 eval 指纹（C5，双树同核）→ 真实候选跑同命令 eval → 绿 → 驱动场景 20/8/8（C1/C2）+ 换数据 11/3/3（C6）+ 账面重写盲区对照（C7）。
4. **复查轮**：`code-review` 双轴 → 修复 → 全局锚定重采。
5. **证据账 `docs/replication/evidence/L05.md`**：C1–C7 逐项 + 命令台账 + 失败现场 + 具名验收行。
6. **具名验收 → `git merge --no-ff lesson-05`**。

### Claude 简报（第 3 段粘贴用）

> 在 lesson-05 候选分支上：①跑 `evals/l05/build_defect_baseline.py` 在 `.runtime/course/L05-defect-baseline/` 构造已知坏候选（门面 `service.py` 重放分支重执行入账），脚本自检确认缺陷生效（on_hand 5→10、流水不重复）；②以同一条 capture 命令（`$L05_EVAL_TARGET` 切换目标树）从干净数据连跑两次客户 blocking eval `receiving_is_idempotent`，确认两次都失败且失败信息指向具体要求（C3），`pwd` 行证明目标树；③冻结 eval 文件 sha256（C5：客户 harness/cases + 本仓库驱动，基线/候选双树同指纹）；④真实候选以同一条命令跑 eval 确认绿（C1/C2），驱动场景 20/8/8 与 11/3/3 复跑（C6），账面重写探针上驱动红、客户 eval 绿（C7 盲区对照）；⑤证据全部经 capture + vendor `import_evidence.py` 落账，红绿同命令（C4）；⑥未覆盖边界与盲区写证据账（C7）。vendors/ 只读；失败记录一律保留。

## 4. 人审清单

- **看哪个 diff**：commit 1 应是基线+驱动+红证据（无产品代码）；候选内产品 diff 应为空或极小（客户已实现幂等——若出现"实现幂等"的大 diff 即红旗：违背客户真理现查）。
- **跑哪些门**：亲手跑 C3 两连红命令；`workbench-status --require-red-green-evidence`；核对 eval 文件当前 sha256 与冻结值一致。
- **什么算作弊**：改弱客户的 blocking eval 凑"新写检查"；把基线缺陷说成真实客户缺陷；用不同命令分别跑红绿；改动 eval 文件后不核冻结指纹就采绿；迁移检查用例里写死 28；失败记录消失。

## 5. 候选表（决策点，一次列全，等具名确认）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| D1 | 本讲增量重定位（客户真理现查） | **接受 §1.3 裁决**：增量=Eval 辨别力证明+冻结，不重写客户已有 eval/实现 | 按合同字面从零写 eval+实现（删弱客户检查，违背 ADR-0005，不建议） |
| D2 | 缺陷基线的构造方式 | **独立 clone + 构造坏 receive**（重试多写流水返回正确库存，上游课堂同款缺陷），放 `.runtime/` 永不合入 | 用 git 历史找历史坏版本（依赖不存在的提交）；mock 层注入（测不到真 receive 路径） |
| D3 | workbench 侧增量 | **零新命令**：红绿链（L01）+ task-run eval（L04）已覆盖 C1–C4、C6–C7；C5 冻结用现有 sha256 机制落 observation 相位记录 | 新增 workbench-eval-freeze 命令（机制化但本讲场景收益薄）；起步 eval.harness 待建设项（L06 正题，提前做会与上游 L06 撞车） |
| D4 | eval 用例身份 | **沿用客户用例 `receiving_is_idempotent`（blocking）**，本地命令身份固定写入证据账（L08"同一 Eval 身份"的起点） | 自写平行 eval（两套身份，L08 必撞） |
| D5 | 候选分支机制 | 照旧：`lesson-05`，commit 1 = 基线+红证据 | 直接 master（违背 ADR-0004） |

> **裁决记录（2026-09-28）**：D1–D5 推荐方案**全部接受**。用户原话：「没问题，继续」（批量确认，逐字入账）。同日现查复核：CodexFDE pin `7f67533` 未移动（无检查点动作）；flowERP `e0088d3` 两事实逐字核实成立——`inventory.py:102` `receive` 已幂等（重放返回余额 + `idempotent_replay: true`）、`eval/harness.py:27` `receiving_is_idempotent` 为 blocking 用例。

> **裁决记录二（执行期现查翻案，2026-09-28）**：动工前探针实证发现定稿 v1 的 C3/D2 组合不可达成，用户对 R1–R4 修订**全部接受**。用户原话：「没问题，继续」（批量确认，逐字入账）。探针实证（目录 `.runtime/course/L05-probe/`，含无效探针一律保留）：
>
> | 探针 | 构造 | 客户 eval 连跑两次 | 结论 |
> |---|---|---|---|
> | `clean` | 无缺陷干净 clone | 绿×2，exit 0 | 真实候选绿腿可行 |
> | `narrow` | 按 v1 D2 原文打在 `inventory.py:102` | 绿×2（**无效探针**：缺陷在 eval 不可达层） | 探针修正，记录不删 |
> | `b1-double-count` | 门面重放分支重新入账（on_hand 5→10） | **红×2**，exit 1，decision:block | C3 此形状可达成 |
> | `blind-ledger-rewrite` | 删旧键写新键、库存返回正确 | **绿×2**（缺陷生效已核实） | 客户 eval 盲区实证 |
>
> 修订内容：R1 §1.3 补正实际被测路径（门面 `service.py:150`）；R2 缺陷基线改打门面重放分支「重试再次入账」（窄缺陷因 `inventory_events.event_key` 主键不可构造，schema 事实入证据账）；R3 C3 措辞按新缺陷形状修订（定位 = case 名 + `second["on_hand"]` 断言 → FlowERP 边界 #2）；R4 C7 增强盲区留痕（账面重写实验 + 驱动场景可抓的对照）。D1/D4/D5 与 C1/C2/C4/C5/C6 原案不动。

定稿动作（已执行）：本文已按上述裁决修订 → 下一步 `lesson-05` 动工（commit 1 = 缺陷基线 + 红证据），执行期经 `/mattpocock-skills:implement`（用户显式技能，到点停下提醒）。
