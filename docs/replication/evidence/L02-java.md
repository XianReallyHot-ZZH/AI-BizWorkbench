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
