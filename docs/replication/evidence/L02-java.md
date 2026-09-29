# L02-java 重走证据账（lesson-02-java 前置与候选）

> ADR-0006 重走线第二讲证据。本账只追加；Python 时代证据见 [L02.md](L02.md)，不重开。移植注记与 golden 方案见 [L02-仓库规则.md 附录 A](../../lessons/L02-仓库规则.md)（2026-09-29 用户审定，"没问题，继续"批量确认：重建口径 + R1–R5 候选写入/R6 不写入 + golden 方案 + java/ 子根 + 任务号 + mvn test 链命令）。

## G. 对照基准（golden）生成 —— 2026-09-29（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 工作台（master，Python 实现、测试、pyproject 零改动）
- 生成工具：`tools/generate_golden_l02.py`（只用标准库；15 场景单会话按序驱动真实 CLI 子进程，与合同测试同形，不绕入口直调）
- 上游现查（起草时，2026-09-29）：`origin/main` = `7f67533`（纯课程文档加深，`course_mainline.py` 冻结合同零变化）→ 本讲基线保持 `a74445a`，不触发检查点采纳

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l02.py`（首跑） | 0 | 15/15 场景按预期退出码通过（t04/t15 预期 rc 1，其余 rc 0），EXIT=0 |
| G2 | 同命令（第 2 跑，确定性比对） | 0 | 15/15 ok，EXIT=0 |
| G3 | `find src/test/resources/golden/l02 -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`（两跑各一次） | 0 | 两跑目录指纹同为 `ed8aad6ca469e6a3b4d1ff6674d0f34ce917a81269953fbce738b1a362645768` → **golden 字节级确定** |

### 场景清单（15，逐条见 `manifest.json` 的 scenarios 数组）

审定方案表 t13 一行按单命令粒度展开为 t13–t15 三场景，语义不变（任务创建与追加本身无法并入查询场景）。

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 身份/项目/任务 | t01–t03 | init 成功 / 项目登记 / 带 spec 快照创建（T-GOLDEN-L02） |
| 链基线 | t04 | 缺链拒绝（零记录，`same_command_red_diff_green_missing`） |
| 链三记录 | t05–t07 | red/diff/green 追加（command_text = `mvn test`，fixture 常量） |
| 链完整 | t08 | `ok:true` + `evidence_complete:true` + `acceptance:pending_human_review` |
| **obs 不破坏链** | t09–t10 | 绿后 observation 追加成功（账本只追加）→ 链仍完整（**L02 流核心语义，l01 golden 未覆盖**） |
| 多 obs + 全量回显 | t11–t12 | 第二条 observation；无 require 标志全量回显（1 任务 5 记录形状） |
| **obs 不满足链** | t13–t15 | 第二任务 T-OBS-ONLY 建 1 条 observation → require-red-green 查询仍报缺链（t15 `errors` 逐字核过：`same_command_red_diff_green_missing: 缺少同命令的红—Diff—绿链`） |

### 规范化与掩码规则（与 golden/l01 逐字相同，Java 测试侧按 `manifest.json` normalization 块复现）

- 掩码字段（不可复现）：`created_at` / `recorded_at` → `<TS>`（墙钟）；`workbench_id` → `<WORKBENCH_ID>`（uuid4）
- `observed_at` **逐字保留**——合同字段（链时序语义），生成时固定字面量（09:00:01–09:00:05 +08:00 严格递增）
- canonical JSON：`sort_keys=True, ensure_ascii=False, indent=2` + 末尾换行；非 JSON 逐字保留（防御性）
- golden 共 16 个文件（15 份 stdout + manifest.json）；**生成后永不手改**，要变只能重生成并留证据

### 落位与继承

golden 前置生成，连同生成器与本 §G 随前置提交落 master（L01 先例：`evidence/L01-java.md` §G 落位说明）；`lesson-02-java` 自 master 分出即继承。

### raw 现场

`.runtime/golden-l02/raw/`（gitignore，不入库）：15 组 stdout/stderr + inputs 固定件。重跑生成器即可复现，不依赖本机留存。

---

## R. 候选 lesson-02-java 证据（2026-09-29，implement 授权前阶段）

### R0 环境与分支形状

- 环境：JBR Java 21.0.10（javac 21.0.10）、Maven 3.9.6、macOS（Darwin 25.6.0）；Python 侧 .venv（3.13，anaconda 托管——L00 既记偏离）
- 分支 `lesson-02-java` 自 master@`f445da0` 分出（继承前置 golden）
- 决策记录：**配合点 1 复认（2026-09-29）**——用户显式选择降级路径：讲义 §2 合同表 + 冻结合同 fixture 即 Spec，不调用 `/mattpocock-skills:to-spec`（Python 时代同意不延续，本次重新确认）；A6 候选表 R1–R5 写入、R6 不写入随同批量确认

### R1 起始红（C1）

- **红封条**：`lesson-02-submission/java/03-failure/20260929T051448833581Z-5b184e93/`（`mvn test`，rc=1）
- 红形态：40 跑 **3 红**（`JavaLineRuleFactsTest` 全部三条——CLAUDE.md 缺 Java 重走线规则要素：`mvn test` 词面缺席 / `src/main` 结构组缺席 / 冻结面组缺席，全部目标能力缺失类）+ **37 绿**（就位类：L02 翻译件 5 绿【Python L02 ReferencedCommandsAreReal 先例，映射表声明】、fixture 5 绿、既有合同 25 绿）
- **golden 双重放绿于红中**：GoldenL02ReplayTest 15 场景首次 Java 重放即字节级一致（就位类语义面锁定，移植注记 A3 预期）；GoldenL01ReplayTest 委托重构后行为不变
- 采红前失败两次（按"先修再采"处理，现场如实保留）：
  1. **golden 重放支撑重构滑手**：`GoldenReplay.goldenResource` 对已含前导 `/` 的资源名再补 `/`，双斜杠 classpath 查不到 → GoldenL01/L02ReplayTest 双双"必须在测试 classpath"红（预检跑，`target/test-classes` 中资源实存、manifest 可读，定位到拼接 bug）→ 修复后两重放绿
  2. **采集参数误读**：`--submission-root` 是完整相对路径而非父根下子名，首采落仓库根 `java/03-failure/20260929T051109701433Z-accdd5fa/`（rc=1，封条本身有效但位置错）——按写集纪律不入库、原样保留于工作树不删，处置待人审裁定；重采以正确子根落位

---
