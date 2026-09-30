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

指纹命令：`cd src/test/resources/golden/l06 && find . -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`。

### 场景清单（9，单命令粒度，逐条见 `manifest.json` 的 scenarios 数组）

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 构造器 | s01 / s02 | CSV 缺陷成功（自检 JSON：`defect_live:true`/`query_available:5`/`csv_available:8`）/ 已存在拒绝（防覆盖，stdout 空） |
| 驱动 pass | s03 / s04 | 8/3 主干（query/csv 同 `[8,3,5]`、拒 6、四表状态不变）/ 13/4 迁移（`[13,4,9]`、拒 10——排除写死 5） |
| 驱动 fail | s05 | 缺陷树 `AC-AVAILABLE`：expected `[8,3,5]` / query `[8,3,5]` / csv `[8,3,8]` + requirement 逐字（与 Python 时代红 66 同形） |
| 驱动拒绝 | s06 | `--target` 下没有 flowerp/（rc 2，参数校验面） |
| 运行器 | s07 / s08 / s09 | 绿报告（四 blocking 绿 + 教学告警 WARN，decision pass）/ 红报告（stock 红 `rc=1: {AC-AVAILABLE …}`、error.type AssertionError、decision block——可信红面字节锁）/ 缺 env 拒绝（stdout 空；与「主类缺失」偶然同形——l05 修正注③同款口径） |

### 规范化与 setup（与五套旧 golden 的关系）

- canonical JSON 规则与 l01–l05 逐字相同：`sort_keys=True, ensure_ascii=False, indent=2` + 换行；非 JSON（含空 stdout）逐字保留（防御性）
- 掩码集按 manifest `normalization.mask_fields` 数据驱动：本套扩展 `generated_at`/`duration_ms` 2 键（首例扩展）；三件套防御性声明保留；占位符形状由重放测试断言
- `setup` 块：`ws/clean`（`git clone --quiet --no-hardlinks vendors/flowERP`，与被测实现无关）+ `ws/no-flowerp`（普通目录）；`ws/b1` 由 s01 场景自身构造——不进共享 setup
- 场景记录新增可选 `env` 字段：五套旧 manifest 无该字段 → 子进程环境不变 → 旧行为逐字节不变（J1/J2）

### 落位与继承

golden 前置生成，连同生成器、共享件 env 字段扩展、本 §G 与附录 A 审定稿随前置提交落 master（L01–L05 先例）；`lesson-06-java` 自 master 分出即继承。生成后永不手改，要变只能重生成并留证据。

### raw 现场

`.runtime/golden-l06/raw/`（gitignore，不入库）：9 组 stdout/stderr + ws 夹具（clean / b1 / no-flowerp）。重跑生成器即可复现，不依赖本机留存。
