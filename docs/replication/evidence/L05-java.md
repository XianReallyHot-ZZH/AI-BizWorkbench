# L05-java 重走证据账（lesson-05-java 前置与候选）

> ADR-0006 重走线第五讲证据。本账只追加；Python 时代证据见 [L05.md](L05.md)，不重开。移植注记与 golden 方案见 [L05-失败优先Eval.md 附录 A](../../lessons/L05-失败优先Eval.md)（2026-09-30 用户审定，「没问题，继续」批量确认：A6.1 全部移植裁定 + JD1–JD8 推荐口径 + golden 方案；前置写作期两处起草级修正按附录 A3 修正注①②逐字披露，③为红点口径披露）。

## G. 对照基准（golden）生成 —— 2026-09-30（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 的 `evals/l05` 两工具（`evals/` 冻结面零改动）。本套场景面 = **工具 stdout 而非 workbench CLI**——l01–l04 以来首次（附录 A1 结构翻译点的对照面：工具对外契约逐字同形，内部驱动缝 subprocess vs in-process import 如实差异）
- 生成工具：`tools/generate_golden_l05.py`（只用标准库；7 场景单会话按序实跑冻结 Python 工具；Java 重放侧经共享件 main 字段驱动 `workbench.evals.l05.*` 两 main——工具不进 REGISTRY）
- 场景面 = L05 两工具行为语义（附录 A3 七场景）：构造器 b1 成功自检 / 已存在拒绝 / 驱动 20/8/8 pass / 11/3/3 pass / b1 树 fail（replay 步 28≠36）/ 盲区树 fail（replay-ledger 步）/ `--target` 拒绝
- 掩码：**本套零新掩码**——工具 stdout 无机器时间戳（无 l04 `_now()` 同秒漂移面）与机器绝对路径（构造器 `baseline` 字段以固定相对路径调用规避，驱动输出不含路径）；三件套声明保留（防御性，本套输出无这些键），口径逐字入 manifest `mask_note`
- 共享件行为零变化扩展（JD5，本前置提交落 master）：`GoldenReplay` 场景可选 `main` 字段（默认 `workbench.cli.Main`）+ `Cli.runMain(main, args)` 独立方法（**非重载**——`run(String, String...)` 与既有 `run(String...)` 在字符串实参调用点两可，J2 编译失败现场见下表；旧 `run` 委托默认主类）+ `deleteRecursively` 可见度放宽为 **public**（l05 重放测试在 workbench.golden 跨包自行清场用，修正注②；首版放宽为包内不足——跨包不可达，J4 编译失败现场见下表）；l01–l04 四套 manifest 无 `main` 字段 → 行为逐字节不变（J1/J3 回归证据）
- 盲区树：生成器内联手术（JD4 补丁规格：门面重放分支删旧 event 行、以 `-rewritten` 后缀新键重插同内容、库存与返回值不变——客户 eval 对此绿、驱动红）；锚点与冻结构造器逐字同一且对 vendors service.py 唯一命中（预检 A0）；重放侧将由 Java 构造器 `--defect blind` 物化同一规格，两侧树的等价性由 s06 驱动 fail 输出对照反向锁定
- 不入 golden（语言绑定或非确定，附录 A3/JD6）：客户 eval 运行输出（客户真理非我方面——pwd 行绝对路径与耗时 ms 不可复现，证据链以语义+rc 承载，与 Python 时代同口径）；工具 stderr 错误词面（`SystemExit` 中文消息 vs Java 异常词面，stdout 空 + rc 对照）；Python traceback 形状
- 上游现查（起草日 2026-09-30）：`origin/main` = `7f67533`，与本讲对照基线一致（检查点 0002 后无新变更，冻结合同零变化）→ 不触发检查点采纳；flowERP pin `e0088d3`（与 Python 时代本讲现查同 pin），`receiving_is_idempotent` 仍为客户 blocking 用例

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| J1 | `mvn test`（共享件扩展前基线） | 0 | **84/84 绿**，BUILD SUCCESS（5:57） |
| A0 | 锚点逐字校验（生成器 ANCHOR vs 冻结构造器 ANCHOR vs `vendors/flowERP/flowerp/service.py`） | 0 | `anchor_identical: True`；`anchor_hits_in_service: 1`（盲区补丁可安全施打） |
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l05.py`（首跑） | 0 | **7/7 场景**按预期退出码通过；指纹 `3d9b623c…` **首跑即留** |
| G2 | 同命令（第 2 跑，确定性比对） | 0 | 7/7 ok；指纹 `3d9b623c654bff89b88d45a716768a506812db46b176e9edf2c8c77977c8e576` = G1 → **golden 字节级确定，采纳此指纹** |
| J2 | `mvn test`（共享件扩展后首跑，`Cli.run(String, String...)` 重载形态） | **非零（编译失败）** | **失败现场**：`[ERROR] 方法 run(java.lang.String...) 和 run(java.lang.String,java.lang.String...) 都匹配`——重载在字符串实参调用点两可；后台任务外壳的 `J2-exit=0` 系 zsh 管道尾部值，权威状态以 Maven `[ERROR]/BUILD FAILURE` 为准。处置 = 新方法改名 `runMain(String, String...)`（独立方法名，零歧义），失败记录保留不删 |
| J3 | `mvn test`（runMain 修正后；含 golden l05 落树——尚无测试引用，纯落位不破坏现状，一跑双证） | 0 | **84/84 绿**（含 golden l01–l04 四套重放）→ **行为零变化证据** |
| J4 | `mvn test`（lesson-05-java 采红前预检首跑，commit 1 测试件就位后） | **非零（编译失败）** | **失败现场**：`GoldenL05ReplayTest`（workbench.golden 包）跨包访问包内 `deleteRecursively` 不可达；处置 = 前置跟进提交（master）放宽为 public（与 testsupport 的 `Cli` 公开面同例，行为零变化），分支自跟进提交重开。失败记录保留不删（本行即现场；正式红封条见候选段 R1） |

指纹命令：`cd src/test/resources/golden/l05 && find . -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`。

### 场景清单（7，单命令粒度，逐条见 `manifest.json` 的 scenarios 数组）

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 构造器 | s01 / s02 | b1 成功（自检 JSON：`defect_live:true`/`on_hand:10`/`events:1`，`baseline` 相对路径零掩码）/ 已存在拒绝（防覆盖，stdout 空） |
| 驱动 pass | s03 / s04 | 20/8/8 主干（C1/C2：on_hand [20,28,28,36]、流水 [1,2,2,3]、0/负数拒绝）/ 11/3/3 迁移（C6：[11,14,14,17]） |
| 驱动 fail | s05 / s06 | b1 树 `replay` 步 expected 28 / actual 36 + requirement 逐字（C3 定位——与 Python 时代 record 55 同形）/ 盲区树 `replay-ledger` 步（C7——与 record 51 同形，树性质反向锁） |
| 驱动拒绝 | s07 | `--target` 下没有 flowerp/（rc 2，参数校验面） |

### 规范化与 setup（与四套旧 golden 的关系）

- canonical JSON 规则与 l01–l04 逐字相同：`sort_keys=True, ensure_ascii=False, indent=2` + 换行；非 JSON（含空 stdout）逐字保留（防御性）
- 掩码集按 manifest `normalization.mask_fields` 数据驱动：本套零扩展（三件套防御性声明保留）；占位符形状由重放测试断言
- `setup` 块：`ws/clean`（`git clone --quiet --no-hardlinks vendors/flowERP`，与被测实现无关）+ `ws/no-flowerp`（普通目录）；`ws/b1` 由 s01 场景自身构造、`ws/blind` 由生成器内联手术物化——均不进共享 setup（重放侧：`GoldenL05ReplayTest` 自行清场后调 Java 构造器 `--defect blind` 物化，再以**空 scratch 清单**调 `replay()`，修正注②——`replay()` 首步清场不会抹掉盲区树）
- 场景记录新增可选 `main` 字段：四套旧 manifest 无该字段 → 默认 CLI 入口 → 旧行为逐字节不变

### 落位与继承

golden 前置生成，连同生成器、共享件扩展、本 §G 与附录 A 审定稿随前置提交落 master（L01–L04 先例）；`lesson-05-java` 自 master 分出即继承。生成后永不手改，要变只能重生成并留证据。

### raw 现场

`.runtime/golden-l05/raw/`（gitignore，不入库）：7 组 stdout/stderr + ws 夹具（clean / b1 / blind / no-flowerp）。重跑生成器即可复现，不依赖本机留存。

---

## R. 候选 lesson-05-java 证据（2026-09-30，implement 授权后）

### R0 环境与分支形状

- 分支 `lesson-05-java`：首开自 master@`caca6ce`，采红前预检暴露 J4（跨包可见度编译失败）→ 删支，前置跟进 `0929e82` 落 master 后自 `0929e82` 重开（继承前置 golden + 共享件扩展）
- 配合点 1 复认（to-spec 降级路径每次重新确认，2026-09-30）与配合点 2（用户显式 `/mattpocock-skills:implement`）在案；任务账具名经 AskUserQuestion 确认（owner = XianReallyHot-ZZH，verify 自举 actor = Claude，L04-java 先例，不代填）
- 冻结面硬检查：`git diff master...lesson-05-java -- evals workbench tests pyproject.toml tools/capture_evidence.py` = **0 字节**；vendors 双查恒空；`.runtime/course/L01-workbench/`（Python 账本）零写入；pom.xml 零改动

### R1 起始红（commit 1 = `31dd286`）

- 采红前预检（不采集，L04 R1 纪律）：**91 跑 7 红 0 错误**——`L05EvalContractTest` 6/6（失败信息全为「找不到主类 workbench.evals.l05.*」＝目标缺失类）+ `GoldenL05ReplayTest` 1（盲区树物化步即败）；既有 84 绿（含四套旧 golden 重放）
- **红封条**：`03-failure/20260930T024413858516Z-8c20967d/`（`mvn test` 全量，rc 1）
- stderr 词面断言使「基线已存在」「锚点命中 2 次」两拒绝面在 commit 1 **真实红**（修正注③设计生效；golden s02 偶然同形为已披露的例外）

### R2 红转绿（commit 2 = `c345495`）

**实现三件**（写集 = 附录 A2 C14 映射；零新 CLI 命令、零 REGISTRY 改动、Ledger 零扩、pom 零改动）：

| 交付物 | 内容 |
|---|---|
| `workbench/evals/l05/FlowerpProbe.java` | 客户实现驱动缝（JD1/JD2）：`probe` 单方法 + 解释器绝对化 + 临时库生命周期；子进程 python 跑客户原语只回报原始观察值（setup/receive/product/events/events_total/snapshot），判断与预期计算留 Java；`ToolFailure` = 工具自身 rc 1（非落账语义，不复用 execution/ProcessRunner） |
| `workbench/evals/l05/BuildDefectBaseline.java` | b1（默认）与冻结 Python argv/stdout/退出码/锚点守卫/自检逐字同形；`--defect blind`（JD4 补丁规格，与生成器内联手术逐字同文）+ `--source`（修正注①守卫可测缝）+ `--python`；默认基线 `-java` 路径（防跨时代冲撞，如实偏离） |
| `workbench/evals/l05/ReceivingScenarioCheck.java` | 步序与 fail/pass 载荷逐 step 同形（replay/replay-flag/replay-ledger/new/invalid…）；预期由 opening+receipt 在 Java 独立计算；rc 0/1/2 语义同形 |

复用深件：`bootstrap/Args`（argparse 同形解析，UsageException → rc 2）、`bootstrap/PyJson.dumpsCompact`（Python json.dumps 默认分隔符单行同形——stdout 契载体）。

**转绿路径**：单类 `L05EvalContractTest` 6/6 绿 → `GoldenL05ReplayTest` 绿（**7 场景字节级一致，一次通过**）→ 全量 **91/91 绿**。

**红绿间测试修正披露（一处，非放宽）**：`blindBuildsRewriteTreeToSpec` 的「盲区缺陷不是 b1」判别从全文件级 `doesNotContain("UPDATE stock…")` 改为**重放分支段内**——客户正常收货分支本就含该串，全文件级断言对真实客户树不可满足（首跑失败现场，commit message 与测试注释双留痕；意图收紧到正确作用域）。

**链三封条**（同命令 `mvn test`，Diff 严格居间，observed_at 严格递增）：red `…8c20967d`（02:44:13，rc 1，91 跑 7 红）→ diff `04-diff/20260930T034932613129Z-0a81515e`（03:49:32，rc 0，暂存区 `git diff --cached`——新增件为主如实披露）→ green `05-green/20260930T034936820988Z-0e45c661`（03:49:36，rc 0，91/91）。

### R3 证据链重演（C3–C7，附录 A4 步骤 6；现场全用 `-java` 新根）

- **三树**（全部 Java 工具产物）：`.runtime/course/L05-defect-baseline-java/`（b1 自检 JSON：`defect_live:true`/`on_hand:10`/`events:1`，终端留痕）；`.runtime/course/L05-probe-java/blind-ledger-rewrite/`（blind 自检：`on_hand:5`/`old_key_events:0`/`rewritten_key_events:1`——盲区构造**脚本化**，Java 增量对 Python 时代 ad-hoc 手术）；`.runtime/course/L05-candidate-java/`（干净 clone @ `e0088d3`）
- **客户 eval 红绿链**（同一条 `sh -c` 命令串逐字节，`$L05_EVAL_TARGET` 切目标树，pwd 行 + 封存 argv + 本账三重披露）：红① `03-failure/…040046…-e5cbb804` + 红② `…040054…-dcc31d38`（rc 1，`[BLOCK]` + `decision: block`，pwd 行证基线-java 树）→ diff `04-diff/…040101…-689ebb47`（rc 0；**与 Python 时代 record 46 字节同形**——同 `index 99753c2..d6f15ef` 行）→ 冻结 `06-observations/…040112…-e539ac92` → 绿 `05-green/…040121…-e40a19a7`（rc 0，`[PASS] 重复入库键只生效一次`，pwd 行证候选-java 树）
- **C5 冻结口径**（9 路径，超 Python 时代 8 路径的增量 = Java 工具第三件 `FlowerpProbe.java`，如实注明）：客户 `eval/harness.py e2f526b6…` 与 `eval/cases.py c8409488…` **双树同指纹且与 Python 时代一致**；基线 `service.py 1a2f303c…` / 候选 `6c372dcd…` **与 Python 时代指纹逐字节相同**——Java 构造的缺陷树与候选树是 Python 时代两树的**字节等价物**；Java 三源件指纹入冻（`36051845…`/`e412b0ab…`/`2af9c57c…`）
- **观察六枚**：20/8/8 pass（`…040132…-0bc323f0`，输出与 Python 时代 record 49 逐字节同形）/ 11/3/3 pass（`…040135…-b5eef9ea`，同 record 50）/ 基线定位补充红（`…040138…-f7825af5`，replay 步 28≠36 + requirement 逐字，同 record 55）/ 盲区驱动红（`…040140…-c105befd`，replay-ledger 步，同 record 51）/ **盲区 eval 绿×2**（`…040142…-faf6d411` + `…040143…-1d26cb09`，pwd 行证盲区树——Python 时代为叙述性留痕，Java 侧落正式封条，加严方向）
- **任务账** `CASE-WB-L05-JAVA-001` @ `.runtime/course/L01-workbench-java/`：建单 ok（requirement_summary `40406db0…`）；**导入前缺链查询**（预期失败也是证据）：rc 1 `same_command_red_diff_green_missing: 缺少同命令的红—Diff—绿链`（`…043929…-407bab8f`）；Java `ImportEvidence` ×4 全 rc 0（重算 SHA-256 验封；导入序 = 红红 diff 绿）；**verify 自举**（V0 被 operator 使用的接缝检查，`…044001…-4cf4fe5c`：`verify_completed`、`executor_command: null`、`changed_files: []`、`task_state: review`、eval rc 0）；**终态密封**（`…044009…-73ad4aaf`：`ok:true` / `evidence_complete:true` / `acceptance:pending_human_review` / `flowerp_connected:false` 四词面齐）
- **C5 收口复核**：`…044019…-2fe4e6d8` 与初冻 `e539ac92` **逐字节一致**
- 全量回归即绿命令本体（`mvn test` 就是全量门），不另设——Python 时代台账同款如实说明

## 10. C1–C7 逐项结论表（Java 口径）

| # | 结论 | 证据 |
|---|---|---|
| C1 | ✓ | 客户 blocking 用例绿腿（`e40a19a7`，decision:pass）+ Java 驱动 20/8/8（`0bc323f0`：期初 20 → A 收 8 → 28，`opening`/`receipt:A` 流水逐条断言，预期独立计算） |
| C2 | ✓ | 驱动：A 重放 28 不变、账面快照前后相等（含流水）、B 新收 36、0/负数拒绝且状态不变（`0bc323f0` 载荷 `on_hand_path [20,28,28,36]` / `invalid_quantities_rejected [0,-1]`） |
| C3 | ✓ | 同一客户 eval 对 Java 构造基线连跑两次均红（`e5cbb804`/`dcc31d38`，rc 1，`[BLOCK]`+`decision:block`）；定位三件套：用例名级 blocking + observing 数值级（`f7825af5`：expected 28 / actual 36）+ requirement 逐字文本 |
| C4 | ✓ | 红绿同一条 `sh -c` 命令串（逐字节同串，`$L05_EVAL_TARGET` 切树；pwd 行 + 封存 argv + 本账三重披露，§12 口径段照抄 Python 时代）；`workbench-status --require-red-green-evidence` → `evidence_complete:true`（`73ad4aaf`） |
| C5 | ✓ | 初冻 `e539ac92` + 收口复核 `2fe4e6d8` 逐字节一致；客户件双树同指纹；冻结对象含 Java 三源件（`FlowerpProbe` 超点名有利，如实注明） |
| C6 | ✓ | `b5eef9ea`：11→14→14→17 / 流水 1→2→2→3，预期独立计算（排除写死 28） |
| C7 | ✓ | 盲区树 Java 构造器 `--defect blind` 可复现脚本化；客户 eval 对盲区树绿×2（`faf6d411`/`1d26cb09`，盲区复证正式封条）+ Java 驱动同树红（`c105befd`）；未覆盖边界见 §12；补流水断言留检查点显式采纳 |
| —（重走线增量） | ✓ | golden l05 7 场景双跑指纹 `3d9b623c…` + Java 重放**字节级一致**（commit 1 红 → commit 2 绿，一次转绿）；工具建设链（mvn）另成链（R2），按 JD7 不入任务账 |

## 11. R 段命令台账

| 相位 | 命令 | 封条（时间戳-哈希后缀） | 退出码 |
|---|---|---|---|
| red（工具链） | `mvn test`（全量） | `03-failure/20260930T024413858516Z-8c20967d` | 1（91 跑 7 红） |
| diff（工具链） | `git diff --cached`（暂存区全量，新增件为主如实披露） | `04-diff/20260930T034932613129Z-0a81515e` | 0 |
| green（工具链） | `mvn test`（同 red 命令） | `05-green/20260930T034936820988Z-0e45c661` | 0（91/91） |
| red（eval 链） | `sh -c 'cd "$L05_EVAL_TARGET" && pwd && exec <venv>/bin/python -X utf8 -m eval.harness --case receiving_is_idempotent --no-report'`（`L05_EVAL_TARGET=…/L05-defect-baseline-java`） | `03-failure/20260930T040046325110Z-e5cbb804` | 1 |
| red（eval 链） | 同上（同串，同目标树） | `03-failure/20260930T040054553524Z-dcc31d38` | 1 |
| diff（eval 链） | `git -C …/L05-defect-baseline-java diff`（缺陷补丁：与 Python 时代 record 46 字节同形） | `04-diff/20260930T040101075785Z-689ebb47` | 0 |
| observation | `shasum -a 256`（9 路径：双树客户件 + 双树 service.py + Java 三源件）——**C5 初冻** | `06-observations/20260930T040112025555Z-e539ac92` | 0 |
| green（eval 链） | 同 red 命令逐字节同串（`L05_EVAL_TARGET=…/L05-candidate-java`） | `05-green/20260930T040121281853Z-e40a19a7` | 0 |
| observation | Java 驱动 20/8/8（候选树） | `06-observations/20260930T040132169332Z-0bc323f0` | 0 |
| observation | 同上 `--opening 11 --receipt 3 --new 3` | `06-observations/20260930T040135663334Z-b5eef9ea` | 0 |
| observation | Java 驱动对基线树（C3 定位补充，数值级） | `06-observations/20260930T040138981707Z-f7825af5` | 1 |
| observation | Java 驱动对盲区树（C7，replay-ledger 步） | `06-observations/20260930T040140797330Z-c105befd` | 1 |
| observation | 客户 eval 对盲区树 ×2（盲区复证；Python 时代叙述性留痕的加严） | `…T040142764522Z-faf6d411` / `…T040143174044Z-1d26cb09` | 0 / 0 |
| observation | 建单后缺链查询 `workbench-status --require-red-green-evidence` | `06-observations/20260930T043929959902Z-407bab8f` | 1（`same_command_red_diff_green_missing`） |
| observation | verify 自举 `workbench-task-run … --mode verify --eval-command "<客户 eval 绝对路径>" --execution-timeout 900 --actor Claude` | `06-observations/20260930T044001915156Z-4cf4fe5c`（任务账 execution `verify_completed`） | 0 |
| observation | 终态 status 四词面 | `06-observations/20260930T044009230238Z-73ad4aaf` | 0 |
| observation | `shasum -a 256`（同初冻 9 路径）——**C5 收口复核：逐字节一致** | `06-observations/20260930T044019307212Z-2fe4e6d8` | 0 |

链判定（任务账 001）：红 `e5cbb804`（04:00:46）→ 红 `dcc31d38`（04:00:54）→ diff `689ebb47`（04:01:01）→ 绿 `e40a19a7`（04:01:21），同命令、observed_at 严格递增、全局最新绿 = 成功绿；verify 自举 execution 在账不破坏链；`evidence_complete:true`。

## 12. 口径与限制

- **同命令异树口径（C4，逐字承袭 Python 时代）**：红绿两条记录的命令串逐字节相同；目标树差异经 `$L05_EVAL_TARGET` 注入（红 = 缺陷基线-java，绿 = 候选-java），pwd 行、封存 argv 与本账三重披露。链判定的「同命令」语义在「同一检查命令、缺陷树红/正确树绿」上成立——增量重定位（D1）的设计本意，非账面技巧。
- **结构翻译差异（A1，如实披露不伪装）**：冻结 Python 工具经 in-process import 驱动客户实现；Java 工具经子进程 python（`FlowerpProbe`）跑客户原语、回报原始观察值，**预期计算与断言留 Java**（独立计算语义不变）。对外契约（argv/stdout JSON/退出码/锚点守卫/自检）由 golden l05 字节级锁定。
- **盲区构造脚本化（C7/JD4）**：Python 时代盲区树为 ad-hoc 手术无冻结脚本；Java 侧 `--defect blind` 可复现构造（补丁规格与 golden 生成器内联手术逐字同文），等价性由 golden s06 驱动 fail 反向锁 + 合同测试文本形状断言。
- **golden s02 偶然同形（修正注③）**：rc 1 + 空 stdout 在工具缺失时与「主类不存在」同形，commit 1 可能不红——红点由其余 6 场景与合同测试承载（stderr 词面断言使合同侧拒绝面真实红）。
- **链入账口径（JD7）**：`CASE-WB-L05-JAVA-001` 装客户 eval 同命令链（C4 字面，Python 时代同形）；mvn 工具建设链封条随分支 commit 1/2（不入任务账——避免多命令混链；Python 时代工具建设本无账本链）。
- **无效探针 narrow 不重演**：v1→v2 翻案现场为 Python 时代历史事实（其证据账 §3 在案）；Java 按定稿 v2 合同走。
- **客户 eval 不入 golden**（客户真理非我方表面，pwd 行/耗时不可复现）；工具 stderr 错误词面语言绑定按 JD6 如实偏差（`SystemExit` 中文消息已在拒绝面逐字复刻，traceback/errno 形状不复刻不伪装）。
- **tdd 口径（翻案，加严）**：Python 时代零新命令无 unittest 起始红缝故未走 tdd；Java 侧工具新代码有合同测试起始红 → 红绿循环真实走完（implement 经用户显式调用，codebase-design/tdd 自调在案）。
- **未覆盖边界（C7，如实）**：并发、进程崩溃、同键不同内容三类边界本讲未覆盖（上游同口径）；客户 eval 不读 inventory_events 流水对「账面重写」盲（复证在案）——是否补流水断言属改客户检查，留检查点显式采纳（ADR-0003），本讲不动客户文件。
- **执行期技能口径**：`/mattpocock-skills:implement`（用户显式，2026-09-30）+ 模型自调 codebase-design / tdd；复查轮 `code-review` 见 §13。
- **vendors 双查**：全程恒空（clone 只读源，缺陷只落 `.runtime/` clone）。
- `acceptance: pending_human_review` 恒待人签；**待审核 ≠ 已接受**。
