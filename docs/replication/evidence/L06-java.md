# L06-java 重走证据账（lesson-06-java 前置与候选）

> ADR-0006 重走线第六讲（末讲）证据。本账只追加；Python 时代证据见 [L06.md](L06.md)，不重开。移植注记与 golden 方案见 [L06-Harness分级判决.md 附录 A](../../lessons/L06-Harness分级判决.md)（2026-09-30 用户审定，「没问题，继续」批量确认：A6.1 全部移植裁定 + A6.2 JD1–JD8 推荐口径 + A3 golden 方案全案）。

## G. 对照基准（golden）生成 —— 2026-09-30（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 的 evals 统一运行器面（`evals/` 冻结面零改动）：`evals/l06/` 三工具 + `evals/harness.py`/`report_contract.py` 经 `evals.l06.checks` 收口。本套场景面 = **工具 stdout + 统一运行器全量报告**——l01–l05 以来首次含运行器报告（s07/s08：checks 的 stdout 即报告全文；报告文件不入 golden，文件面/x 模式由 Java 合同测试锁——附录 A3/JD3）
- 生成工具：`tools/generate_golden_l06.py`（只用标准库；9 场景单会话按序实跑冻结 Python 工具；checks 经 `-m evals.l06.checks` 运行——其 main 内 `from evals.harness import run` 需包语境；Java 重放侧经共享件 main 字段驱动 `workbench.evals.l06.*` 三 main——工具不进 REGISTRY）
- 场景面 = 附录 A3 九场景：构造器成功自检 / 已存在拒绝 / 驱动 8/3 pass / 13/4 迁移 pass / 缺陷树 fail（AC-AVAILABLE）/ `--target` 拒绝 / checks 绿报告（clean 树 decision pass）/ checks 红报告（缺陷树 decision block——可信红面）/ 缺 env 拒绝
- 掩码：**本套扩展 2 键（l01–l05 首例扩展，机制 l04 起已按 manifest 声明数据驱动）**：`generated_at`→`<TS>`、`duration_ms`→`<MS>`（s07/s08 报告机器时间字段；其余场景无这些键，键级掩码无扰）；三件套声明保留（防御性）；口径逐字入 manifest `mask_note`
- **env 场景字段（本套首用，附录 A3/JD4）**：s07/s08 注入 `L06_EVAL_TARGET`（Checks 读环境变量是冻结 Python 语义，argv 面不含 `--target`）；共享件 `GoldenReplay` 场景可选 `env` + `Cli.runMainWithEnv`（**独立方法名**——J2 重载两可教训承袭；旧 `runMain` 委托 launch 核，行为不变）；l01–l05 五套 manifest 无 `env` 字段 → 子进程环境不变、行为逐字节不变（J1/J2 回归证据，下表）
- 缺陷树夹具无预物化问题：`ws/b1` 由 s01 场景自身构造（生成侧 = 冻结 Python 构造器，重放侧 = Java 构造器——双侧树等价的第一重锁），无 l05 盲区树式预物化，重放测试清场后直接 `replay()`
- 不入 golden（附录 A3/JD6）：客户 eval 直跑输出（客户真理非我方表面，盲区探针走证据链 observation）；工具 stderr 错误词面（`SystemExit` 中文消息 vs Java 异常词面，stdout 空 + rc 对照）；Python traceback 形状；报告文件；假绿变体（探针走证据链，变体永不入 git）
- 跨时代指纹互证：s08 报告内 `l06_frozen_checks` evidence 的客户件指纹前缀 `e2f526b65780`/`c8409488131e` 与 Python 时代 C6 冻结值（`eval/harness.py=e2f526b6…`、`eval/cases.py=c8409488…`）逐字一致——客户件三时代同指纹
- 上游现查（起草日 2026-09-30）：`origin/main` = `7f67533`，与本讲对照基线一致（检查点 0002 后无新变更，冻结合同零变化）→ 不触发检查点采纳；flowERP pin `e0088d3`（与 Python 时代本讲现查同 pin），`service.py` 锚点 `{row['available']}` 唯一命中 1 次（A0 预检）

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| J1 | `mvn test`（共享件扩展前基线） | 0 | **91/91 绿**，BUILD SUCCESS |
| A0 | 生成器内锚点逐字校验（生成器 ANCHOR vs 冻结构造器声明 vs `vendors/flowERP/flowerp/service.py`） | 0 | `anchor_identical: True；anchor_hits_in_service: 1`（G1 首行） |
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l06.py`（首跑） | 0 | **9/9 场景**按预期退出码通过；指纹 `f0e0ab77…` **首跑即留** |
| G2 | 同命令（第 2 跑，确定性比对） | 0 | 9/9 ok；指纹 `f0e0ab777e946e8bfa7d5bae2388377e0c9c649d2d3b3a3e671e8b885060f5a8` = G1 → **golden 字节级确定，采纳此指纹** |
| J2 | `mvn test`（共享件扩展后回归） | 0 | **91/91 绿**（含 golden l01–l05 五套重放）→ **行为零变化证据**（J1/J2 对照，env 字段扩展无扰） |
| G3 | 同 G1 命令（重生成，候选期修正轮） | 0 | 9/9 ok；指纹 `1465c87d…` |
| G4 | 同命令（修正后第 2 跑，确定性比对） | 0 | 9/9 ok；指纹 `1465c87d56b94e37e001fd3ae0c73e026d78312fbce9c2c6b1986f2374b3b05c` = G3 → **采纳此指纹** |

指纹命令：`cd src/test/resources/golden/l06 && find . -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`。

**修正记录（s09 面注记，候选期 2026-09-30）**：前置期的生成器 docstring / manifest note / 附录 A3 / 本账场景清单把 s09（checks 缺 env）误述为「stdout 空、与主类缺失偶然同形」——**捕获数据自始正确**（s09 期望 = 全项失败报告：四登记项 RuntimeError + 教学项 AssertionError 入报告、`decision: block`、stdout 即报告全文——冻结 checks 的 `_target()` 在登记项内逐项解析，失败走报告而非入口拒绝），错的是注记文字与 Java 首版实现/合同测试的形状假设。Golden 重放首跑（s01–s08 字节全过后）在 s09 抓获 Java 首版「入口拒绝」译法与冻结面的分歧——按 golden 修正：Checks 目标解析移入登记项 + `RuntimeError` 同名异常类（报告 error.type 字节保真）+ 合同测试改断言报告形状（非放宽，向冻结面对齐；披露于 L06EvalContractTest 注释）。随后重生成 golden：`git diff` 证 9 个 stdout 件**逐字节未动**、仅 manifest 的 s09 note 一行变化 → 指纹 f0e0ab77… → `1465c87d…`（G3/G4 双跑一致，原 G1/G2 记录保留不删）。

### 场景清单（9，单命令粒度，逐条见 `manifest.json` 的 scenarios 数组）

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 构造器 | s01 / s02 | CSV 缺陷成功（自检 JSON：`defect_live:true`/`query_available:5`/`csv_available:8`）/ 已存在拒绝（防覆盖，stdout 空） |
| 驱动 pass | s03 / s04 | 8/3 主干（query/csv 同 `[8,3,5]`、拒 6、四表状态不变）/ 13/4 迁移（`[13,4,9]`、拒 10——排除写死 5） |
| 驱动 fail | s05 | 缺陷树 `AC-AVAILABLE`：expected `[8,3,5]` / query `[8,3,5]` / csv `[8,3,8]` + requirement 逐字（与 Python 时代红 66 同形） |
| 驱动拒绝 | s06 | `--target` 下没有 flowerp/（rc 2，参数校验面） |
| 运行器 | s07 / s08 / s09 | 绿报告（四 blocking 绿 + 教学告警 WARN，decision pass）/ 红报告（stock 红 `rc=1: {AC-AVAILABLE …}`、error.type AssertionError、decision block——可信红面字节锁）/ 缺 env 全项失败报告（四项 RuntimeError + 教学项 AssertionError 入报告、decision block、stdout 即报告全文——目标树解析在登记项内，冻结 `_target()` 逐项调用同形） |

### 规范化与 setup（与五套旧 golden 的关系）

- canonical JSON 规则与 l01–l05 逐字相同：`sort_keys=True, ensure_ascii=False, indent=2` + 换行；非 JSON（含空 stdout）逐字保留（防御性）
- 掩码集按 manifest `normalization.mask_fields` 数据驱动：本套扩展 `generated_at`/`duration_ms` 2 键（首例扩展）；三件套防御性声明保留；占位符形状由重放测试断言
- `setup` 块：`ws/clean`（`git clone --quiet --no-hardlinks vendors/flowERP`，与被测实现无关）+ `ws/no-flowerp`（普通目录）；`ws/b1` 由 s01 场景自身构造——不进共享 setup
- 场景记录新增可选 `env` 字段：五套旧 manifest 无该字段 → 子进程环境不变 → 旧行为逐字节不变（J1/J2）

### 落位与继承

golden 前置生成，连同生成器、共享件 env 字段扩展、本 §G 与附录 A 审定稿随前置提交落 master（L01–L05 先例）；`lesson-06-java` 自 master 分出即继承。生成后永不手改，要变只能重生成并留证据。

### raw 现场

`.runtime/golden-l06/raw/`（gitignore，不入库）：9 组 stdout/stderr + ws 夹具（clean / b1 / no-flowerp）。重跑生成器即可复现，不依赖本机留存。

---

## R. 候选 lesson-06-java 证据（2026-09-30，implement 授权后）

### R0 环境与分支形状

- 分支 `lesson-06-java` 自 master@`ca95521` 分出（前置 golden + 附录 A 审定稿 + 共享件 env 扩展继承）；配合点 1 复认（to-spec 降级路径每次重新确认，2026-09-30「没问题，继续」）与配合点 2（用户显式 `/mattpocock-skills:implement`）在案；任务账具名经 AskUserQuestion 确认（owner = XianReallyHot-ZZH，verify 自举 actor = Claude，L05-java 先例，不代填）
- 冻结面硬检查：`git diff master...lesson-06-java -- evals workbench tests pyproject.toml tools/capture_evidence.py` = **0 字节**；vendors 双查恒空；pom.xml 零改动；`.runtime/course/L01-workbench/`（Python 账本）零写入

### R1 起始红（commit 1 = `2b0557f`）

- 采红前预检（不采集，L04/L05 纪律）：99 跑 8 红 0 编译错——`L06EvalContractTest` 7（失败信息全为「主类缺失」红点组：rc 错位/stderr 词面缺/json 解析空）+ `GoldenL06ReplayTest` 1（s01 退出码错位即败）；既有 91 绿
- **红封条**：`03-failure/20260930T071140516446Z-55efdec4/`（`mvn test` 全量，rc 1）

### R2 红转绿（commit 2 = `a520914`，另含其后 `Checks.repoRoot` 修正随 commit 3）

**实现六件**（写集 = 附录 A2；零新 CLI 命令、零 REGISTRY 改动、Ledger 零扩、pom 零改动）：`workbench/evals/` 根 `EvalHarness`（九项分级语义 / x 模式报告 / 退出码）+ `ReportContract`（11 处拒绝词面逐字）；`workbench/evals/l06/` 四件（`Checks` 五登记项收口 / `StockConsistencyCheck` 口径驱动 / `BuildDefectBaseline` CSV 缺陷基线构造器 / `StockProbe` 子进程探针 300s 超时）。随实现同落 `EvalHarnessContractTest`（九项进程内 + 文件面 + 客户报告交叉验证，编译绑定披露见 commit 1）。

**途中真实失败（保留不删）**：① 编译失败 1 起——`assertRejected` 两重载在 `null`/String 实参两可（J2 同源教训），改独立方法名 `assertRejectedSuite`；② golden 首跑在 s09 抓获**结构翻译分歧**——冻结 checks 的 `_target()` 在登记项内逐项解析（缺 env → 四项 RuntimeError + 教学项 AssertionError 入报告、`decision: block`、stdout 即报告全文），首版译成「入口拒绝」（stderr + 空 stdout）；按 golden 修正 `Checks`（目标解析移入登记项 + `RuntimeError` 同名异常类承载 error.type）与合同测试断言（向冻结面对齐非放宽），golden 重生成（9 个 stdout 件逐字节未动、仅 manifest note 一行，指纹 `f0e0ab77…`→`1465c87d…`，修正记录见 §G）；③ verify 集成两枚失败封条见 R3。

**转绿路径**：单类 `EvalHarnessContractTest` 10/10 → `L06EvalContractTest` 7/7 → `GoldenL06ReplayTest` 绿（**9 场景字节级一致**，s09 修正后一次通过）→ 全量 **109/109 绿**。

**链 A（工具建设链，同命令 `mvn test`，JD7 不入任务账）**：红 `55efdec4`（07:11:40，rc 1，99 跑 8 红）→ diff `04-diff/20260930T074343692089Z-17c4532b`（07:43:43，rc 0，暂存区全量——新增件为主如实披露）→ 绿 `05-green/20260930T074349632401Z-f10f642e`（07:43:49，rc 0，109/109）。

### R3 证据链重演（C4–C7，附录 A4 步骤 6；现场全用 `-java` 新根）

- **假绿探针**（D5 次序：合同绿后、可信红前）：工作树暂存换入 `EvalHarness` 的 `blocking_failed=0` 变体（永不提交）→ `mvn test -Dtest=EvalHarnessContractTest` 真实红 **2 失败**（`blockingIsNotMajorityVote` + `exceptionKeepsReasonAndContinues`——与 Python 时代 record 60 的 test_02/test_04 同位）→ `git restore` 还原 → 复原后 10/10 复绿；封条 `06-observations/…075138…-8afe887b`（rc 1）留痕
- **三树**：`.runtime/course/L06-defect-baseline-java/`（Java 构造器产物，自检 JSON `defect_live:true`/`query_available:5`/`csv_available:8`，封条 `90124b0d`）；`.runtime/course/L06-candidate-java/`（干净 clone @ `e0088d3`）
- **链 B（checks 同命令红绿链，任务账主链）**：同一条 `sh -c` 命令串逐字节（`echo "target: $L06_EVAL_TARGET"` + `exec java -cp … workbench.evals.l06.Checks --no-report`；红=缺陷基线-java / 绿=候选-java，`$L06_EVAL_TARGET` 注入，echo 行 + 封存 argv + 本账三重披露）——红 `03-failure/…075231…-fbaf0d96`（07:52:31，rc 1，`decision: block`、`l06_stock_consistency` evidence=`rc=1: {AC-AVAILABLE 查询 [8,3,5] / csv [8,3,8]}`）→ diff `04-diff/…075240…-1450f60a`（07:52:40，rc 0，CSV 一行缺陷补丁 `{row['available']}`→`{row['on_hand']}`——「修业务一行」的实证载体）→ 绿 `05-green/…075241…-7ef678db`（07:52:41，rc 0，`passed: 4` + 教学告警 WARN + `decision: pass`）
- **迁移**（C1）：Java 驱动 `--opening 13 --reserved 4` 对候选树 → `[13,4,9]`、拒 10、状态不变（封条 `c16a40f5`，排除写死 5）
- **盲区探针**（C7）：客户 `inventory_export_is_stable` 对**缺陷树**绿 ×2（rc 0，绝对解释器——Python 时代 rc 126 教训预先规避；封条 `67de1ff2`/`2ebd3475`）——「检查没跑在缺陷可见的形状上（reserved=0 数据）」现行边界如实留痕，本方检查同树红即链 B 红腿
- **指纹（C6）**：首冻 `eae2cd15`（12 路径）→ `Checks.repoRoot` 修正（见下）→ 终冻 `a9945a2d`（仅 Checks 源指纹变 `9cd339cc…`，余 11 路径与首冻逐字一致；首冻封条留 submission 不入账，如实记录）→ **收口复核 `8044864d` 与终冻逐字节一致**。客户件双树同指纹且**三时代一致**（`e2f526b65780`/`c8409488131e`）；候选 `service.py 6c372dcd…` 与 L05-java 候选树逐字节相同（干净树跨讲互证）
- **verify 集成（含两枚真实失败，保留不删不入账）**：① `7eea271c`（07:59:15，rc 2）——eval-command 漏 `--runtime-dir`（L05 台账摘录省略该参，真实 CLI 必填）；② `2c998901`（07:59:27，rc 1）——`task-run --workspace` 使 eval 子进程 cwd=候选树，`Checks.repoRoot()` 的 cwd 兜底（`basedir`/`.`）随之漂移：`.venv/bin/python` 默认值与 `vendors/flowERP` 均解析进 clone → 四 blocking 全 ToolFailure、`decision: block`（harness 如实入报告，信用无损）。**修正**：冻结 Python 的 `REPO_ROOT = Path(__file__).resolve().parents[2]` 本是文件位置锚定——Java 改 `Checks.class` 装载位置锚定（target/classes → 仓库根，不随 cwd 漂移），全量 109/109 复绿（含 golden 重放，封条 `ee0d2778`）；verify 重采成功 `8e4dc07e`（08:07:38，rc 0：`verify_completed`、`executor_command: null`、`changed_files: []`、`task_state: review`，eval-command 显式 `--python` 绝对路径）
- **交叉验证（C4/D3）**：`customerReportPassesOurValidation` 合同测试实跑 rc 0（封条 `1026f3b4`）——客户报告（其自身 `run_suite` 产出）过 Java `ReportContract`，schema 1.0 对齐机检在案
- **任务账** `CASE-WB-L06-JAVA-001` @ `.runtime/course/L01-workbench-java/`：建单 ok（owner = XianReallyHot-ZZH，AskUserQuestion 在案；requirement_id `REQ-COURSE-L06-JAVA`）；缺链预期失败也是证据（`0ec0e59d`，rc 1，`same_command_red_diff_green_missing`）；链 B 三封条导入（ImportEvidence 重算 SHA-256 验封全 rc 0）+ 观察类 13 枚导入（含 rc 1 的探针/缺链——观察相位如实入账）→ **共 16 条证据**；终态 status `9df7fb60` 与导入后终验 `0502a9f4` 均 `evidence_complete: true` + `acceptance: pending_human_review` + `flowerp_connected: false` 四词面齐

## 10. C1–C7 逐项结论表（Java 口径）

| # | 结论 | 证据 |
|---|---|---|
| C1 | ✓ | Java 驱动 8/3：查询与 CSV 同 `[8,3,5]`（预期 8−3 独立计算，链 B 绿腿内 `l06_stock_consistency` 绿 + golden s03 字节锁）；迁移 `c16a40f5`：`[13,4,9]`、拒 10、状态不变（排除写死 5） |
| C2 | ✓ | 驱动 AC-REJECT/AC-UNCHANGED（快照锚点=建单后，R1 终态照抄）；客户 blocking `stock_never_negative` 经链 B 绿腿 `l06_customer_stock_case` 绿（FlowERP 边界 #1） |
| C3 | ✓ | `EvalHarnessContractTest` 10/10（九项映射上游 `test_runner_contract.py`，Python 测试头同源）；golden s07 绿报告字节锁 |
| C4 | ✓ | `ReportContract` 11 处拒绝词面逐字 + 消费端矛盾双拒（test_09 同位）；假绿探针 `8afe887b`：`blocking_failed=0` 变体上合同套件真实红（2 失败，与 Python record 60 同位）；报告三态一致由 golden s08 红报告字节锁 + 链 B 红绿同串三态；交叉验证 `1026f3b4`（客户报告过 ReportContract） |
| C5 | ✓ | 链 B 同一条 `sh -c` 命令串（echo 行 + 封存 argv + 本账三重披露，L05 逐字承袭）；`workbench-status --require-red-green-evidence` → `evidence_complete: true`（`9df7fb60` + 终验 `0502a9f4`） |
| C6 | ✓ | 终冻 `a9945a2d` + 收口复核 `8044864d` 逐字节一致；客户件双树同指纹且三时代一致；冻结对象含 Java 六源件；L05 能力不回归 = 链 B 绿腿 `l05_receiving_regression` 绿 + mvn 全量 109/109（`ee0d2778`，含 golden l01–l05 五重放与 L05 合同） |
| C7 | ✓（留痕口径） | 盲区探针 `67de1ff2`/`2ebd3475`：客户 `inventory_export_is_stable` 对缺陷树绿 ×2 + 本方检查同树红（链 B 红腿）；未覆盖边界见 §12；补 reserved>0 客户用例留检查点显式采纳 |
| —（重走线增量） | ✓ | golden l06 9 场景双跑指纹 `1465c87d…` + Java 重放字节级一致（commit 1 红 → s09 修正后一次转绿）；工具建设链（mvn）另成链（R2），按 JD7 不入任务账 |

## 11. R 段命令台账

| 相位 | 命令 | 封条（时间戳-哈希后缀） | 退出码 |
|---|---|---|---|
| red（工具链） | `mvn test`（全量） | `03-failure/20260930T071140516446Z-55efdec4` | 1（99 跑 8 红） |
| diff（工具链） | `git diff --cached`（暂存区全量，新增件为主如实披露） | `04-diff/20260930T074343692089Z-17c4532b` | 0 |
| green（工具链） | `mvn test`（同 red 命令） | `05-green/20260930T074349632401Z-f10f642e` | 0（109/109） |
| observation | 假绿探针：`mvn test -Dtest=EvalHarnessContractTest`（变体 `blocking_failed=0` 换入；2 failures，与 Python record 60 同位） | `06-observations/20260930T075138141450Z-8afe887b` | 1 |
| observation | Java 构造器产缺陷基线（自检 JSON：defect_live/query 5/csv 8） | `06-observations/20260930T075221334409Z-90124b0d` | 0 |
| red（业务链） | `sh -c 'echo "target: $L06_EVAL_TARGET" && exec java -cp … workbench.evals.l06.Checks --no-report'`（`L06_EVAL_TARGET=…/L06-defect-baseline-java`） | `03-failure/20260930T075231634102Z-fbaf0d96` | 1（block/AC-AVAILABLE） |
| diff（业务链） | `git -C …/L06-defect-baseline-java diff`（CSV 一行缺陷补丁） | `04-diff/20260930T075240864923Z-1450f60a` | 0 |
| green（业务链） | 同 red 命令逐字节同串（`L06_EVAL_TARGET=…/L06-candidate-java`） | `05-green/20260930T075241045450Z-7ef678db` | 0（passed 4 + WARN + pass） |
| observation | Java 驱动迁移 `--opening 13 --reserved 4`（候选树） | `06-observations/20260930T075251825269Z-c16a40f5` | 0 |
| observation | 盲区探针 run1：客户 `inventory_export_is_stable` 对缺陷树（绝对解释器） | `06-observations/20260930T075307996911Z-67de1ff2` | 0 |
| observation | 盲区探针 run2：同 run1（绿 ×2） | `06-observations/20260930T075308534595Z-2ebd3475` | 0 |
| observation | 指纹首冻 12 路径（被 repoRoot 修正取代，留 submission 不入账） | `06-observations/20260930T075321937406Z-eae2cd15` | 0 |
| observation | 交叉验证：`mvn test -Dtest=EvalHarnessContractTest#customerReportPassesOurValidation` | `06-observations/20260930T075322924686Z-1026f3b4` | 0 |
| observation | 建单后缺链查询 `workbench-status --require-…-red-green-evidence` | `06-observations/20260930T075812156145Z-0ec0e59d` | 1（`same_command_red_diff_green_missing`） |
| observation（失败，不入账） | verify 首采（eval-command 漏 `--runtime-dir`；现场保留） | `06-observations/20260930T075915449033Z-7eea271c` | 2 |
| observation（失败，不入账） | verify 二采（cwd 漂移抓获 repoRoot 译法偏差：四 blocking ToolFailure、block——现场保留，修正见 R3） | `06-observations/20260930T075927414711Z-2c998901` | 1 |
| observation | verify 自举成功：`workbench-task-run … --mode verify --eval-command "java -cp <绝对> workbench.evals.l06.Checks --no-report --python <绝对>" --execution-timeout 900 --actor Claude` | `06-observations/20260930T080738028357Z-8e4dc07e` | 0（verify_completed/task_state review） |
| observation | 指纹终冻 12 路径（repoRoot 修正后；仅 Checks 源变） | `06-observations/20260930T080803718587Z-a9945a2d` | 0 |
| observation | 终态 status 四词面 | `06-observations/20260930T080810410238Z-9df7fb60` | 0 |
| observation | 观察类导入后终验复跑 status（`evidence_complete: true` 不受扰） | `06-observations/20260930T080855696935Z-0502a9f4` | 0 |
| observation | C6 收口复核（同 12 路径，与终冻 `a9945a2d` 逐字节一致） | `06-observations/20260930T080907051677Z-8044864d` | 0 |
| observation | repoRoot 修正后全量回归（109/109，含 golden 六套重放） | `06-observations/20260930T080946241116Z-ee0d2778` | 0 |

链判定（任务账 001）：红 `fbaf0d96`（07:52:31）→ diff `1450f60a`（07:52:40）→ 绿 `7ef678db`（07:52:41），同命令、observed_at 严格递增、全局最新绿 = 成功绿；verify 自举 execution 在账不破坏链；观察类（含 rc 1 的探针/缺链）导入后终验复跑仍 `evidence_complete: true`（`0502a9f4`）。**任务账共 16 条证据**（链 3 + 观察 13，导入序如实）；`acceptance` 字段恒 `pending_human_review`——机器不代签，验收事实在具名验收行（铁律 2）。

## 12. 口径与限制

- **同命令异树口径（C5，逐字承袭 Python 时代/L05-java）**：链 B 红绿两条记录的命令串逐字节相同；目标树差异经 `$L06_EVAL_TARGET` 注入（红 = 缺陷基线-java，绿 = 候选-java），echo 行、封存 argv 与本账三重披露。「修业务一行」以两树间该一行的差异承载（diff 封条即该行），照 Python 台账形态（红 66/diff 65/绿 64），不对干净候选树另做编辑表演。
- **结构翻译差异（A1/JD2，如实披露不伪装）**：① 冻结 checks 的 stock 登记项经子进程再跑 driver——Java 进程内调用驱动逻辑（可调用面返回 (rc, 末行)，evidence/error 与子进程路径逐字节同形）；② 驱动/自检经 `StockProbe` 子进程（Python 为 in-process import）；③ `REPO_ROOT` 锚定：Python `Path(__file__).parents[2]` → Java 类装载位置（verify 集成抓获 cwd 兜底偏差后修正，两枚失败封条保留）；④ `--python` 默认 `.venv/bin/python` 相对 cwd（FlowerpProbe/JD2 先例）——verify 场景显式传绝对路径。对外契约（argv/stdout JSON/退出码/锚点守卫/自检/报告全文）由 golden l06 字节级锁定。
- **s09 修正轮（§G 修正记录）**：golden 抓获首版「入口拒绝」译法与冻结面分歧（目标树解析在登记项内、失败走报告），按 golden 修正；重生成仅 manifest note 一行变化，指纹 `f0e0ab77…`→`1465c87d…`。
- **已知行为边界（JD6）**：`generated_at` Java ISO 形状与 Python `isoformat` 微差（掩码内不入对照）；工具 stderr 错误词面逐字、traceback/errno 形状不复刻不伪装；工具崩溃路径 error.type 记 Java 类名（如 `ToolFailure`——冻结面同位为 Python 异常名，语言绑定如实偏差）；冻结检查**失配路径**（从未触发）的 evidence 词面有两处微偏差——半角冒号+空格（Python 全角「：」）与 `Map.of` 键序（Python dict 保序），JD2「词面逐字」按 golden 覆盖面理解，该死路径打磨搁置检查点（复查轮 S-2）。
- **golden s02 偶然同形（唯一）**：rc 1 + 空 stdout 在工具缺失时与「主类缺失」同形，commit 1 可能不红——红点由其余 8 场景（s09 期望报告 vs 空stdout 真红）与合同测试承载。
- **链入账口径（JD7）**：`CASE-WB-L06-JAVA-001` 装 checks 同命令链；mvn 工具建设链封条随分支 commit 1/2（不入任务账——避免多命令混链）；假绿探针/缺链查询作为观察相位入账（rc 如实，不参与链判定）。
- **失败与中间态保留**：verify 两枚失败封条（`7eea271c`/`2c998901`）、首冻被取代封条（`eae2cd15`）、编译失败现场（commit message + 会话记录）——一律保留不删。
- **tdd 口径**：`/mattpocock-skills:implement`（用户显式，2026-09-30）+ 模型自调 tdd（单文件→全量红绿循环）；codebase-design 面由附录 A 审定稿承载（JD1/JD2 接口缝随触点 1 定稿）；复查轮 code-review 见 §13。
- **未覆盖边界（C7，如实）**：并发预占、进程中断回滚、多 SKU/多仓叠加——本讲检查均为单商品单线程时序，不覆盖（上游同口径）；客户 `inventory_export_is_stable` 的 reserved=0 盲区是否补形状属客户仓库演进，留检查点显式采纳（ADR-0003）。
- **vendors 双查**：全程恒空（clone 只读源，缺陷只落 `.runtime/` clone）。
- `acceptance: pending_human_review` 恒等人签；**待审核 ≠ 已接受**。

## 13. 复查轮（code-review 双轴自调，2026-09-30）

双轴子代理并行复查 `master...lesson-06-java`（Standards 轴：CLAUDE.md/CONTEXT.md 标准 + Fowler 坏味道基线 + l05 同位先例形态；Spec 轴：讲义 §2 + 附录 A 审定稿 + 本账事实核对——封条 meta 抽查、sqlite 只读查账、shasum 复算 golden 与 12 路径指纹、`git log -S` 查变体、mvn 独立复跑 **109/109 exit 0**；报告全文见会话记录，此处存目与处置）。**处置原则：行为零变化修正、披露补记、旧证据全保留。本轮零代码改动**（六源件已入终冻 `a9945a2d`——冻结产物打磨不采纳，Python 时代/L05 复查轮同款裁定）。

| # | 轴 | 发现 | 处置 |
|---|---|---|---|
| S-1 | 双轴 | A4 步骤 3 字面把九项合同测试排在 commit 1，实际因 Java 编译绑定随实现落 commit 2（commit message/§R2/测试 javadoc 三处已披露，附录未回写） | ✅ 已修（**行为零变化回写**）：A4 步骤 3 改为工具面 + golden 随 commit 1、九项随 commit 2 的实际口径 |
| S-2 | Standards | 冻结检查**失配路径**（从未触发）evidence 词面两处微偏差：半角冒号（Python 全角「：」）+ `Map.of` 键序（Python dict 保序）——JD2「词面逐字」在该路径轻微超称 | 不采纳（搁置检查点）：死路径 + 六源件已冻，重冻+重演成本＞收益（L05 T-1/T-6 同款）；§12 已补记披露 |
| S-3 | Standards | `intOption` 7 行助手在 l06 两新件逐字重复（l05 第三份已冻同形） | 不采纳（澄清）：工具文件自足先例（l05 形态承袭），抽共享件动冻结节 |
| S-4 | Standards | 测试 `git()` 夹具在两合同测试逐字重复 | 不采纳（澄清）：测试夹具自足同款（L05EvalContractTest 同形） |
| S-5 | Standards | 微瑕：ReportContract 同条件 `stringSet` 重算 2–3 次；测试 `basedir` 裸取与 `Cli.repoRoot()` 惯用不一 | 不采纳（澄清）：冻结核内打磨同 S-2 口径；测试化妆无行为收益 |
| 呈报 | Spec | 审定稿（用户批准工件）分支内共改 4 行：A3 s09 面注记、「8 类」→11 处 ×2、A4 回写（本轮）——均文档侧零行为，修正链在案（§G 修正记录 + G3/G4） | ✅ 显式呈报：附录状态行加修正注；验收人按本表核对 |

**复查轮结论**：Standards 轴**无硬违规**（冻结面 0 字节、vendors 恒空、golden 永不手改经生成器重生成在案、_Avoid_ 零命中、Unicode 纪律、测试口径 S6 承袭、`--skip-self-check` 参数面齐）；Spec 轴抽查**全相符**（假绿变体不入 git、链 B argv 逐字节同串 rc 1→0、任务账 16 条且 mvn 链不在账、首冻不入账、verify 失败+成功两枚 execution 在账、终冻=收口复核、客户件三时代同指纹、D5 封条时序无补拍、独立复跑 109/109）。S-1 文档修正随 commit 4 提交；其余搁置/澄清留档。
