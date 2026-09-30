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

---

## 附录 A：Java 重走移植注记（ADR-0006）

> 状态：**审定稿**（2026-09-30 用户审定：「没问题，继续」批量确认——A6.1 全部移植裁定 + A6.2 JD1–JD8 推荐口径 + A3 golden 方案全案；同日前置写作期两处起草级修正按 A3「执行时定稿」授权落定，逐字披露于 A3 修正注）。决策与词汇见 ADR-0006 与 CONTEXT.md（重走 / 移植注记 / 对照基准）。§1–§5 为 Python 口径的历史对照基线，不重写；本附录只记录 Java 重走侧的翻译点与增量。重走证据落 `docs/replication/evidence/L05-java.md`（原 `evidence/L05.md` 不重开）。上游现查（2026-09-30，本附录起草日）：`origin/main` = `7f67533`，与本讲对照基线一致（检查点 0002 后无新变更，冻结合同零变化），不触发检查点采纳；flowERP 现查（submodule pin `e0088d3`，与 Python 时代本讲现查同 pin）：`receiving_is_idempotent` 仍为客户 blocking 用例（`eval/harness.py` EVALS 登记在场），`inventory_events.event_key` 主键事实不变。

### A1 讲解：本讲重建什么

L05 是重走线**第三个有实质实现代码增量的讲**（增量重心从执行链转到 Eval 工具面）。Python 时代交付 `evals/l05/` 两件——缺陷基线构造器（`build_defect_baseline.py`：clone flowERP + 门面重放分支补丁 + 锚点守卫 + 自检）与收货场景驱动（`receiving_scenario_check.py`：预期独立计算 + 库存/流水双断言）；工作台命令面**零新增**（D3）。Java 侧重走 = 把这两件工具在 Java 线重建 + 按 C1–C7 重演证据链（两连红 / 同命令红绿链 / 冻结指纹 / 盲区留痕）。`evals/` 整目录是 Python 冻结面（对照基准），Java 线零触碰、不修改、不复用其实现——**Java 侧的 L05 份额 eval 能力（l05 两工具）在本讲建成**；`evals/harness.py` + `report_contract.py`（统一运行器与报告合同）是 L06-java 正题，本讲不提前做（Python L05 D3 同款拒绝：提前做与上游 L06 撞车）。

**本讲特有的结构翻译点（起草注记要回答的第一个问题）**：冻结的两件工具是**进程内 import 目标树 flowerp** 的 Python 脚本（`sys.path.insert(target)` + `from flowerp import ERPService`，构造器自检与场景驱动同款）。Java 无法同形（不能进程内 import Python 模块）——Java 工具必须经**子进程 python** 驱动客户实现（客户代码在子进程内跑原语、回报原始观察值，**预期计算与断言留在 Java 侧**，独立计算语义不变）。这是结构性翻译而非行为偏差：工具的对外契约（argv 参数面、stdout JSON 逐字节、退出码语义、锚点守卫、自检）与冻结 Python 件逐字同形，由 golden l05 字节锁证明；内部驱动缝差异（subprocess vs in-process import）如实入证据账，不伪装。

直接踩两条已建载体：① **Java V0 三命令已就位**（L04-java）——`workbench-task-run --mode verify --eval-command` 正是 C4 证据链与集成记录的账本缝，本讲零新增 CLI 命令、零 REGISTRY 改动、Ledger 零扩；② **GoldenReplay/Cli.normalize 数据驱动掩码 + setup 块已就位**（L04-java JD2）——l05 需一次行为零变化的最小扩展（场景可选主类，A3/JD5）。

**缺陷基线"先红后修"叙事在 Java 线的真实重演（handoff ③）**：两层红都真实、都不补拍——① 工具建设层：`mvn test` 起始红 = Java 侧 L05 工具能力缺失（合同测试 + golden l05 重放红点，commit 1 先于实现存在）；② 辨别力层：客户 blocking eval 对 **Java 构造器真实构造**的缺陷基线连跑两次真实失败（构造器自检真实通过 on_hand 翻倍、eval 真红非注入）——红来自基线非真实客户（R3 口径），"修"半边承袭 D1 增量重定位（客户已幂等，无产品修复 diff，出现即红旗）。Python 时代"无 unittest 起始红缝故未走 tdd"的口径在 Java 侧自然翻案：工具新代码有合同测试起始红，tdd 红绿成立（口径加严，方向合规）。

**不重建**：`evals/harness.py`/`report_contract.py`（L06-java 正题）；无效探针 `narrow`（v1→v2 翻案现场，Python 证据账 §3 历史事实在案，按定稿 v2 合同走，不重演）；serve-workbench / course-submit / 根 FDE_SPEC.md（承袭不建）；S01/S02 支线（Python 时代完成件）。本讲无 B 票、无执行器会话（Python 时代同——无 `claude -p` 调用，模型/权限确认点不适用，如实声明）。

### A2 合同翻译（C1–C7）

| # | Python 口径（§2 原文） | Java 重走口径 | 变化类型 |
|---|---|---|---|
| C1 | 首次入库增加库存 | 客户 blocking 用例绿腿照跑 + **Java 场景驱动** 20/8/8（期初 20，A 收 8 → 28，`opening`/`receipt:A` 流水逐条断言）；驱动输出与冻结 Python 逐字节同形（golden l05 锁） | 重演（驱动换 Java 载体） |
| C2 | 同一幂等键重放不再次增加库存 | Java 驱动：A 重放 28 不变、账面快照前后相等（含流水）、B 新收 36、0/负数拒绝且状态不变 | 重演 |
| C3 | 缺陷基线两连红（R3 形状） | **Java 构造器**产 `.runtime/course/L05-defect-baseline-java/`（同锚点同补丁、锚点守卫、自检 JSON）；同一客户 eval 命令对基线从干净数据连跑两次均红（rc 1，`[BLOCK]`+`decision: block`，pwd 行证目标树）；定位口径三件套承袭（用例名级 blocking + observing 驱动数值级 28≠36 + requirement 逐字文本） | 重演（基线由 Java 工具构造） |
| C4 | 同命令红绿链入账 | 同一条 `sh -c` 命令串（`$L05_EVAL_TARGET` 切换目标树，pwd 行 + 封存 argv + 证据账三重披露），`tools/capture_evidence.py --submission-root lesson-05-submission/java` 采集 → **Java `ImportEvidence`** 导入 → Java 活账本 `CASE-WB-L05-JAVA-001`；`workbench-status --require-red-green-evidence` 链判定完整（JD7：001 装客户 eval 同命令链，与 Python 时代同形） | 重演（账本/导入器换 Java 载体） |
| C5 | 检查冻结不被偷偷改动 | 冻结 sha256：客户 `eval/harness.py`+`eval/cases.py`（基线/候选双树同指纹）+ 双树 `flowerp/service.py`（基线缺陷在场/候选干净佐证）+ **Java 两工具源文件**（对应 Python 时代的"本仓库驱动+构造脚本"）；初冻 + 收口复核前后逐字节一致；改动检查须单独说明并保留旧结果 | 重演（冻结对象含 Java 工具源） |
| C6 | 换数据迁移检查 | Java 驱动 `--opening 11 --receipt 3 --new 3` → [11,14,14,17] / 流水 [1,2,2,3]，预期独立计算 | 重演 |
| C7 | 边界留痕 + 盲区实证 | 盲区树由 **Java 构造器 `--defect blind` 模式**可复现构造（Python 时代 ad-hoc 手术无冻结脚本，脚本化是 Java 增量——JD4）；客户 eval 对盲区树绿×2（盲区复证）+ Java 驱动同树红（`replay-ledger` 步）；未覆盖边界（并发/进程崩溃/同键不同内容）如实入账；是否补流水断言仍留检查点显式采纳 | 重演 + 盲区构造脚本化增量 |
| — | （Python 时代无此项） | **golden 对照门**：golden l05 = 冻结 Python 工具采出预期、Java 工具重放字节级一致（A3）；重放测试 commit 1 红为本讲预期红点 | 重走线增量 |

### A3 对照基准（golden）方案

生成（审定后、implement 授权前；冻结 Python 侧仍可跑。工具 `tools/generate_golden_l05.py`，镜像 l04 生成器，只用标准库）：

- 工作区 `.runtime/golden-l05/`（raw 落 gitignore）；输出 `src/test/resources/golden/l05/`；canonical JSON 规则与 l01–l04 逐字相同；manifest 保留 normalization 块
- **golden 语义在本讲的适用范围划清（handoff ①）**：golden l05 锁的是**我方工具输出面**——生成侧用**冻结 Python 工具**（`evals/l05/` 两件，只执行不修改）在同一 setup 上采出预期，重放侧 Java 工具字节级一致。**有冻结 Python 输出可对照的场景**：构造器成功自检 JSON / 构造器两拒绝 / 驱动 pass×2 / 驱动 b1 树 fail / 驱动 target 拒绝。**Java 新建面（无冻结工具对照）**：盲区构造行为（Python 时代无构造脚本——生成器按 JD4 补丁规格**内联手术**产树采驱动预期，重放侧由 Java 构造器产树，树的盲区性质由驱动 fail 输出对照反向锁定）；以及 mvn 合同测试断言的工具内部行为。**不入 golden（任何侧）**：客户 eval 运行输出（客户真理非我方表面——其 pwd 行绝对路径与耗时 ms 不可复现，证据链以语义+rc 承载，与 Python 时代同口径）；工具 stderr 错误词面（`SystemExit` 中文消息 vs Java 异常词面，语言绑定按 JD6 如实偏差，stdout 空 + rc 对照）；Python traceback 形状
- **掩码：本套预期零新掩码**——l05 场景面无机器生成时间戳（工具不打印时间，无 l04 `_now()` 同秒漂移面），构造器输出的 `baseline` 字段用**固定相对路径**调用规避（生成器与重放 cwd 均为仓库根）；掩码集 = 三件套默认（`created_at`/`recorded_at`/`workbench_id` 声明保留），口径逐字写入 manifest `mask_note`（含"本套零扩展"说明）
- **setup 块（与被测实现无关的环境物化，双侧同规格）**：`ws/clean`（`git clone vendors/flowERP` 相对路径 run 步骤）+ `ws/no-flowerp`（普通目录）；盲区树**不进共享 setup**（生成期内联手术 / 重放测试自行清场后调 Java 构造器各自物化，见修正注②）
- **共享件行为零变化扩展（JD5，前置落 master）**：`GoldenReplay` 场景支持可选 `main` 字段（默认 `workbench.cli.Main`——l01–l04 四套 manifest 无该字段 → 输出逐字节不变）+ `Cli.runMain(main, args)` 独立方法（非重载——`run(String, String...)` 与既有 `run(String...)` 在字符串实参调用点两可，J2 编译失败现场见 §G；旧 `run` 委托默认主类）+ `GoldenReplay.deleteRecursively` 可见度放宽为 public（l05 重放测试跨包自行清场用，修正注②——首版包内不足，J4 现场见 §G）；以前置提交的 `mvn test` 84/84（含四套旧重放）为行为零变化证据
- 场景面 = L05 两工具行为语义（定稿 **7 场景**，全清单以 manifest 为准）：

| 组 | 场景 | 预期 rc | 覆盖面 |
|---|---|---|---|
| 构造器 | s01 `--baseline ws/b1` 成功（clone+补丁+自检 JSON：`defect_live:true`/`on_hand:10`/`events:1`） | 0 | C3 基线构造面 + 双侧树等价的第一重锁 |
| 构造器 | s02 基线已存在拒绝（s01 产物在场） | 1 | 防覆盖纪律 |
| 驱动 | s03 `--target ws/clean` 默认 20/8/8 pass | 0 | C1/C2 主干 |
| 驱动 | s04 同树 `--opening 11 --receipt 3 --new 3` pass | 0 | C6 迁移 |
| 驱动 | s05 `--target ws/b1` fail（`replay` 步 `{"on_hand":28}` vs `{"on_hand":36}` + requirement 逐字） | 1 | C3 定位（observing 数值级） |
| 驱动 | s06 `--target ws/blind` fail（`replay-ledger` 步：重放改变了账面） | 1 | C7 盲区（树性质反向锁） |
| 驱动 | s07 `--target ws/no-flowerp` 拒绝 | 2 | 参数校验面 |

**修正注（2026-09-30 前置写作期，按上表"执行时定稿"授权落定）**：

① **锚点拒绝场景从 golden 撤出**（草案曾列第 8 场景）：冻结 Python 构造器的 clone 源硬编码 `vendors/flowERP`、无注入缝，构造"锚点命中 2 次"的源必须改 vendors（铁律 1 禁止）——生成侧不可构造。锚点守卫改由 Java 合同测试经构造器 `--source` 参数（Java 新增面，默认 `vendors/flowERP`）注入变异源 discharge；Python 时代该守卫亦从未在证据链触发，属防护性代码，无对照损失。
② **盲区树物化时序**：`GoldenReplay.replay()` 首步清场会抹掉预物化夹具 → `GoldenL05ReplayTest` 自行清场（`GoldenReplay.deleteRecursively` 可见度放宽为包内，行为零变化）后调 Java 构造器 `--defect blind` 物化 `ws/blind`，再以**空 scratch 清单**调 `replay()`——生成侧对应物为生成器内联手术（同一补丁规格），两侧树的等价性由 s06 驱动 fail 输出反向锁定（原设计语义不变）。
③ **commit 1 红点口径**：s02（预期 rc 1 + 空 stdout）在 Java 工具缺失时与"主类不存在"（rc 1 + 空 stdout）偶然同形——该场景在 commit 1 可能不红，红点由 s01/s03–s07 承载，合同测试映射表如实披露。

- s01 与 s05 构成顺序叙事（s01 的 Java 构造产物即 s05 被测树；生成侧同构：Python 构造器产物 + Python 驱动）；重放测试自行清场后调 Java 构造器 `--defect blind` 物化 `ws/blind`、再以空 scratch 清单调 `replay()`（执行被测产品件产夹具，非"测试自带文本"；时序见修正注②）
- 重放时长预算：7 场景 × JVM 子进程 + 3 次 clone（setup 干净树 / s01 基线 / 盲区树物化）+ 4 次 python 子进程场景 ≈ 二十至四十秒增量，可接受
- 幂等双跑，golden 目录 SHA-256 两次一致入 §G——**首跑即留指纹**（L03 教训）；golden + 生成器 + 共享件扩展 + §G 记录 + 本附录审定稿前置落 master（L01–L04 先例），候选分支自 master 分出即继承；生成后永不手改

### A4 实操流程（增量于 §3）

```bash
# 0. 审定后、动手前（前置落 master，L01–L04 先例）
#    - 本附录审定稿入讲义 → master
#    - GoldenReplay 场景 main 字段 + Cli.runMain 独立方法 + deleteRecursively public 可见
#      （行为零变化，mvn test 84/84 回归证据）
#      + tools/generate_golden_l05.py + golden/l05（双跑指纹，首跑即留）
#      + evidence/L05-java.md §G → master
# 1. git checkout -b lesson-05-java
# 2. 【配合点 1 复认】to-spec 降级：每次重新确认（L02/L03/L04-java 先例），起始红采前问
# 3. commit 1 = L05 Java 合同测试（L05EvalContractTest，文件头映射表；轻夹具：构造器
#    已存在拒绝/锚点拒绝用合成变异树，盲区构造后文本形状断言，驱动参数校验——真树
#    行为面由 golden 锁）+ GoldenL05ReplayTest + 起始红封条
#    （tools/capture_evidence.py --submission-root lesson-05-submission/java red -- mvn test）
# 4. 【配合点 2】implement：用户显式调用后动笔——workbench/evals/l05 两件
#    （BuildDefectBaseline：默认 b1 与冻结 Python argv/输出逐字兼容 + --defect blind 扩展模式；
#    ReceivingScenarioCheck：子进程 python 跑客户原语回报原始观察值，预期计算与断言在 Java）；
#    不进 REGISTRY、零新 CLI 命令、Ledger 零扩；自调 codebase-design（工具输出契约对照冻结件）、
#    tdd（红→绿）；遇障自调 diagnosing-bugs
# 5. diff + green：mvn test 同命令，observed_at 严格递增；采集根 lesson-05-submission/java（完整相对路径）
# 6. 证据链重演（C3–C7，现场全用 -java 新根勿混用）：
#    a. Java 构造器产 .runtime/course/L05-defect-baseline-java/（自检 JSON 终端留痕）
#    b. 客户 eval 两连红 ×2 capture（sh -c 同串逐字节，L05_EVAL_TARGET=基线-java 树，
#       venv python 绝对路径——跨 cwd 纪律；pwd 行证目标树）
#    c. diff 封条：git -C 基线树 diff（缺陷补丁正文）
#    d. 冻结 shasum observation（客户 harness/cases + 双树 service.py + Java 两工具源）
#    e. 绿：同命令同串（L05_EVAL_TARGET=.runtime/course/L05-candidate-java/ 干净 clone）rc 0
#    f. Java 驱动观察：20/8/8、11/3/3、基线树红（定位补充）、盲区树红；盲区树上客户 eval 绿×2
#       （盲区复证 observation）
#    g. 集成记录：./bin/wb workbench-task-run CASE-WB-L05-JAVA-001 --workspace <候选-java>
#       --mode verify --eval-command "<客户 eval 绝对路径裸命令，Python 时代 record 52 同形；
#       task-run --workspace 已绑定候选 cwd，无 $L05_EVAL_TARGET 注入缝>" --execution-timeout 900
#       --actor <向用户索取>
#    h. Java ImportEvidence 导入（red×2→diff→green 顺序；观察类绿后导入）→ status 密封
#       四词面（ok / evidence_complete / pending_human_review / flowerp_connected:false）
# 7. code-review 双轴自调（master...lesson-05-java）→ test-first 修复、旧证据保留 → 触点 3
#    具名验收 → git merge --no-ff lesson-05-java（合并信息含验收人）→ roadmap 重走线行
#    + 讲义导航 + CLAUDE.md 待建设清单口径收口（Java 侧 l05 两工具建成；统一运行器仍 L06-java 正题）
```

纪律注：`.idea/` 与仓库根 `java/` 归置仍非本讲写集；源码字面 Unicode 显式转义（L03 S-1 教训）；`git add` 精确路径不用 `add -A`。

### A5 人审清单增量（叠加 §4）

- [ ] **Python 冻结面零触碰（硬检查）**：`git diff master...lesson-05-java -- evals workbench tests pyproject.toml tools/capture_evidence.py` 为空；`.runtime/course/L01-workbench/`（Python 账本）零写入；pom.xml 零改动（如偏离逐条说明）
- [ ] golden l05 双跑指纹在场（首跑即留）；共享件 main 字段扩展行为零变化（前置提交 84/84 回归 + 四套旧重放绿）；三套旧 golden 文件零触碰；l05 golden 非手改；重放测试 commit 1 红在场
- [ ] 红绿同命令：sh -c 命令串逐字节相同（`$L05_EVAL_TARGET` 切换 + pwd 行 + 封存 argv + 证据账三重披露）；红与绿均真实（基线由 Java 构造器真实构造、自检真实通过；无补拍无造红）
- [ ] 冻结与收口：初冻 + 收口复核 sha256 前后一致；冻结对象含 Java 两工具源与客户 harness/cases/双树 service.py；客户 eval 文件零改动
- [ ] Java 工具输出与冻结 Python 逐字节一致（golden 7 场景 + 实跑观察记录）；子进程驱动缝差异如实披露（不伪装 in-process）
- [ ] C4 链入 `CASE-WB-L05-JAVA-001`（owner/actor 用户具名，不代填）；mvn 工具建设链封条随分支 commit 1/2（JD7 口径，不入任务账）
- [ ] 非目标守住：不建 Java 统一运行器（L06-java 正题）、不起草盲区补流水断言（留检查点）、无效探针 narrow 不重演（历史在案）
- [ ] 红旗（新增）：为凑字节对照伪造 Python 词面/时间戳/路径；盲区树"手工弄好不落脚本"；工具失败被说成"自检通过"；驱动绿被说成 blocking 身份（blocking 恒为客户用例）；golden 手改；evals/ 被写入

### A6 裁定表（随触点 1 逐条裁定，未确认不动笔）

**A6.1 Python 时代裁定移植表**：

| 裁定 | 处置 | Java 口径 |
|---|---|---|
| D1 增量重定位（辨别力证明 + 冻结，不重写客户检查） | 移植 | 同口径：客户 blocking eval 照跑不删弱；候选内产品 diff 为空（出现"实现幂等"大 diff 即红旗） |
| D2 独立 clone 缺陷基线（永不合入） | 移植 | `.runtime/course/L05-defect-baseline-java/`（gitignored，路径 `-java` 后缀与 Python 时代现场分离）；候选 `.runtime/course/L05-candidate-java/` |
| D3 workbench 零新命令 | 移植 | Java 侧同样零新 CLI 命令、零 REGISTRY 改动；工具落 `workbench/evals/l05` 独立 main；统一运行器仍 L06-java 正题（提前做与上游 L06 撞车） |
| D4 沿用客户用例身份 | 移植 | blocking 身份恒为客户 `receiving_is_idempotent`（冻结指纹内）；Java 驱动是 observing 补充不冒充 blocking |
| D5 候选分支机制 | 移植 | `lesson-05-java`，单任务 `CASE-WB-L05-JAVA-001`（Python 时代同单任务无 B 票） |
| R1–R4 修订终态（门面层缺陷 / event_key 主键 schema 事实 / 定位口径三件套 / 盲区留痕增强） | 直接采用 | 定稿 v2 合同即重走口径；schema 事实与盲区边界照抄入 Java 证据账 |
| 复查轮终态（定位口径重写 / C5 收口复核 / 冻结产物命名打磨不采纳搁置检查点 / 双树 service.py 冻结超点名有利） | 终态承袭 | §4 裁决照抄；命名打磨继续搁置检查点 |
| 同命令异树三重披露（pwd / argv / 证据账） | 移植 | 逐字承袭（C4 口径段照抄） |
| tdd 技能未走（无 unittest 起始红缝） | 口径翻案（加严） | Java 侧工具新代码有合同测试起始红 → tdd 红绿成立（A1） |
| 无效探针 narrow（v1→v2 翻案现场） | 历史事实不重演 | Python 证据账 §3 在案；Java 按定稿 v2 走 |

**A6.2 Java 时代新决策**（候选，推荐口径随触点 1 批量裁定）：

| # | 决策 | 推荐 | 备选 |
|---|---|---|---|
| JD1 工具落位 | 新包 `src/main/java/workbench/evals/l05/`：`BuildDefectBaseline` + `ReceivingScenarioCheck` 两 main（子进程助手包内自带最小实现，不复用 `execution/ProcessRunner`——那是执行记录落账语义，工具失败 = 自身 rc 非零，语义不同）；不进 REGISTRY。参数面：构造器 `--baseline`（默认 `.runtime/course/L05-defect-baseline-java`——偏离 Python 默认路径防跨时代冲撞，如实记录）+ `--defect b1\|blind`（默认 b1）+ `--source`（默认 `vendors/flowERP`，修正注①）+ `--python`（默认 `.venv/bin/python`——子进程自检的结构性必需，JD2 默认值口径；复查轮 S-2 补记）；驱动 `--target` 必填 + `--opening/--receipt/--new` 默认 20/8/8 | 塞 bootstrap/tools 包（分层失真 / 与 ImportEvidence 语义混置，不可取） |
| JD2 驱动缝 | 子进程 python 跑客户原语（add_product/receive_stock/流水查询）回报**原始观察值 JSON**，预期计算与断言留 Java（独立计算语义保持：28 由 Java 用 20+8 算出，不抄子进程返回）；`--python` 默认 `.venv/bin/python`（相对 cwd，内部 resolve 绝对——跨 cwd 纪律），manifest 与实跑同参数面 | Java 内嵌整段 Python 复刻 driver（判断落在被测侧，违背独立计算，不可取） |
| JD3 golden l05 方案 | A3 全案：7 场景（修正注①后定稿）+ 零新掩码（相对路径规避）+ setup 干净 clone + s01→s05 顺序叙事 + 客户 eval/stderr 词面不入 golden + 首跑即留指纹 | 缩面无 golden（工具输出无字节锁，回归防护降级） |
| JD4 盲区构造脚本化 | 构造器 `--defect blind` 扩展模式（默认 b1 与冻结 Python argv/输出逐字兼容；blind 为 Java 增量，无冻结工具对照，由 s07 反向锁 + 合同测试 discharge）；补丁规格：门面重放分支改为**删既有 event 行、以 `-rewritten` 后缀新键重插同内容**、库存与返回值不变；生成器按同一规格内联手术产 golden 预期 | 手工补丁（不可复现，Python 时代 ad-hab 无脚本——Java 侧倒退，不可取）；独立第三工具（表面膨胀） |
| JD5 golden 共享件扩展 | `GoldenReplay` 场景可选 `main` 字段 + `Cli.run(main, args)` 重载，行为零变化（旧四套 manifest 无字段 → 逐字节不变），前置落 master 带 84/84 回归证据 | GoldenL05 自带重放循环（重复逻辑，l04 JD2 已否决同款） |
| JD6 已知行为边界 | 工具 stderr 错误词面（`SystemExit` 中文 vs Java 异常）不入 golden、如实记录偏差；客户 eval 输出 pwd 行/耗时不入 golden；驱动缝差异如实披露 | 在 Java 侧拼装 Python 词面（伪装同形，不可取） |
| JD7 链入账口径 | `CASE-WB-L05-JAVA-001` 装**客户 eval 同命令链**（C4 字面，与 Python 时代同形）；mvn 工具建设链红/diff/green 封条随分支 commit 1/2 入 submission，不入任务账（避免多命令混链；Python 时代工具建设本无账本链——同形且不放宽） | 双链混装一任务（链判定多命令语义未验证，风险不可取） |
| JD8 收口范围 | roadmap 重走线行 + 讲义导航 + CLAUDE.md 待建设清单口径更新（Java 侧 l05 两工具建成，统一运行器/`evals/harness` 对应物仍 L06-java 正题）；`.idea/` 与仓库根 `java/` 归置仍留后续讲次 | — |
