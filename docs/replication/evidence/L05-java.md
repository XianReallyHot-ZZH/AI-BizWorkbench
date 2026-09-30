# L05-java 重走证据账（lesson-05-java 前置与候选）

> ADR-0006 重走线第五讲证据。本账只追加；Python 时代证据见 [L05.md](L05.md)，不重开。移植注记与 golden 方案见 [L05-失败优先Eval.md 附录 A](../../lessons/L05-失败优先Eval.md)（2026-09-30 用户审定，「没问题，继续」批量确认：A6.1 全部移植裁定 + JD1–JD8 推荐口径 + golden 方案；前置写作期两处起草级修正按附录 A3 修正注①②逐字披露，③为红点口径披露）。

## G. 对照基准（golden）生成 —— 2026-09-30（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 的 `evals/l05` 两工具（`evals/` 冻结面零改动）。本套场景面 = **工具 stdout 而非 workbench CLI**——l01–l04 以来首次（附录 A1 结构翻译点的对照面：工具对外契约逐字同形，内部驱动缝 subprocess vs in-process import 如实差异）
- 生成工具：`tools/generate_golden_l05.py`（只用标准库；7 场景单会话按序实跑冻结 Python 工具；Java 重放侧经共享件 main 字段驱动 `workbench.evals.l05.*` 两 main——工具不进 REGISTRY）
- 场景面 = L05 两工具行为语义（附录 A3 七场景）：构造器 b1 成功自检 / 已存在拒绝 / 驱动 20/8/8 pass / 11/3/3 pass / b1 树 fail（replay 步 28≠36）/ 盲区树 fail（replay-ledger 步）/ `--target` 拒绝
- 掩码：**本套零新掩码**——工具 stdout 无机器时间戳（无 l04 `_now()` 同秒漂移面）与机器绝对路径（构造器 `baseline` 字段以固定相对路径调用规避，驱动输出不含路径）；三件套声明保留（防御性，本套输出无这些键），口径逐字入 manifest `mask_note`
- 共享件行为零变化扩展（JD5，本前置提交落 master）：`GoldenReplay` 场景可选 `main` 字段（默认 `workbench.cli.Main`）+ `Cli.runMain(main, args)` 独立方法（**非重载**——`run(String, String...)` 与既有 `run(String...)` 在字符串实参调用点两可，J2 编译失败现场见下表；旧 `run` 委托默认主类）+ `deleteRecursively` 可见度放宽为包内（l05 重放测试自行清场用，修正注②）；l01–l04 四套 manifest 无 `main` 字段 → 行为逐字节不变（J1/J3 回归证据）
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
