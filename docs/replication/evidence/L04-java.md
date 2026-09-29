# L04-java 重走证据账（lesson-04-java 前置与候选）

> ADR-0006 重走线第四讲证据。本账只追加；Python 时代证据见 [L04.md](L04.md)，不重开。移植注记与 golden 方案见 [L04-受控执行.md 附录 A](../../lessons/L04-受控执行.md)（2026-09-29 用户审定，"没问题，继续"批量确认：A6.1 全部移植裁定 + JD1–JD8 推荐口径 + golden 方案八组 ~47 场景）。

## G. 对照基准（golden）生成 —— 2026-09-29（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 工作台（master，Python 实现、测试、pyproject 零改动）
- 生成工具：`tools/generate_golden_l04.py`（只用标准库；47 场景单会话按序驱动真实 CLI 子进程，与合同测试同形，不绕入口直调）
- 场景面 = 受控执行三命令（`workbench-task-run`/`-review`/`-show`）的行为语义（附录 A3 八组）：状态构建 / C9 绑定拒绝与正面 / 启动拒绝 11 面 / code 正常执行全量记录形 / 复核门 9 面 / code 异常四态 + eval 超时可分辨 / verify 面 / 摘要与账面（含篡改检出）
- 掩码块（l04 特有，manifest `mask_note` 逐字写明口径分歧）：`observed_at`/`reviewed_at` → `<TS>`（本讲场景面系机器生成 `_now()`——与 l01/l02 的"observed_at 是操作者合同字段，逐字保留"不同）；`workspace` → `<WORKSPACE>`（执行记录含机器绝对路径）；`created_at`/`recorded_at`/`workbench_id` 与三套统一
- 共享件数据驱动扩展（JD2，本前置提交落 master）：`GoldenReplay`/`Cli.normalize` 掩码改按 manifest `normalization.mask_fields` 声明驱动 + 新增 `setup` 块（候选仓等非文本夹具物化）；l01/l02/l03 三套 manifest 声明不变 → 掩码行为逐字节不变（下表 J1/J2 回归证据）
- 不入 golden（语言绑定或非确定，附录 A3/JD6）：launch error 的 errno 词面、argparse 用法错（stderr + rc 2）、真实 `claude -p`（替身 sh 命令入 golden）
- 上游现查（起草时，2026-09-29）：`origin/main` = `7f67533`，与本讲对照基线一致（检查点 0002 后无新变更，冻结合同零变化）→ 不触发检查点采纳

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| J1 | `mvn test`（共享件扩展前基线） | 0 | **59/59 绿**，BUILD SUCCESS |
| J2 | `mvn test`（GoldenReplay/Cli.normalize 数据驱动扩展后） | 0 | **59/59 绿**，含 golden l01/l02/l03 三套重放 → **行为零变化证据** |
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l04.py`（首跑） | 1（Traceback 中止） | **失败现场**：s47 篡改步 `sqlite3.OperationalError: no such column: change_manifest_json`——生成器 SQL 误写列名（真实列名 `change_manifest`），s01–s46 已 ok、s47 中止；修正 SQL 后重跑，本次首跑指纹未采纳 |
| G2 | 同命令（修正列名后重跑） | 0 | 47/47 场景按预期退出码通过；指纹 `fdb22e01…` 立即留存 |
| G3 | 同命令（第 3 跑，确定性比对） | 0 | 47/47 ok；指纹 `65ee6824…` ≠ `fdb22e01…` → **确定性失败现场**（见下），run2/run3 逐文件 diff 定位漂移 |
| G4 | `diff -rq` run2 vs run3 golden 目录 | 1 | 仅 `s46_status_full_ledger` / `s47_status_after_tamper` 两文件漂移；根因确诊：冻结 `_now()` 秒级精度（`timespec="seconds"`），同秒创建的任务由 task_id 字典序决胜，而**同秒组组成随跑随机**（T-GOLDEN-L04-J 与 T-GOLDEN-L04-B 是否同秒跨跑不同）→ `(created_at, task_id)` 排序跨跑漂移 |
| G5 | 同命令（修复后：任务编号改按创建序字典序命名 `-01`…`-10`，字典序≡创建序，并列排序跨跑确定） | 0 | 47/47 ok；指纹 `5b7ebbcdb44ca977f7bf451da53862c30f4cffb494fecf9316aceb5efd293048` |
| G6 | 同命令（修复后第 2 跑） | 0 | 47/47 ok；指纹 `5b7ebbcdb44ca977f7bf451da53862c30f4cffb494fecf9316aceb5efd293048` = G5 → **golden 字节级确定，采纳此指纹** |
| J3 | `mvn test`（golden l04 落树后回归） | 0 | 59/59 绿（l04 golden 无测试引用，纯落位不破坏现状） |

漂移指纹 `fdb22e01…`（G2）与 `65ee6824…`（G3）及其定位过程如实保留于本节，不删不改；**采纳指纹 = `5b7ebbcdb44ca977f7bf451da53862c30f4cffb494fecf9316aceb5efd293048`**（G5=G6）。

### 场景清单（47，单命令粒度，逐条见 `manifest.json` 的 scenarios 数组）

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 状态构建 | s01–s03, s25, s29, s31, s33, s35, s37, s39, s42 | init / project-add / 10 任务创建（编号按创建序字典序 `-01`…`-10`——G4 根因修复） |
| C9 绑定 | s04/s05（拒绝）、s28（正面） | `prerequisite_task_missing` / `prerequisite_not_accepted` / 接受后放行 + `prerequisite_task_id` 入账 |
| code 正常执行 | s06 | C3 全量记录形：invocation / write_scope / change_manifest（added，前值 null）/ diff 正文 / 双流 SHA / `change_manifest_sha256`；eval 绿停 review |
| 启动拒绝（11 面，不落记录） | s07–s18 | `verify_rejects_executor_command` / `write_scope_required` / `executor_command_required` / `execution_timeout_required` / `eval_command_unparseable` / `executor_command_unparseable` / `required_actor_missing` / `required_task_missing` / `workspace_invalid`×3（不存在/缺 .git/缺 HEAD）/ `executor_prompt_unreadable` |
| 复核门（9 面） | s19–s24, s27, s41, s43 | `task_in_review` / `self_review_rejected` / `required_reviewer_missing` / approve→accepted / 接受后再执行回 review（C8 版本锚定）/ 再 approve / reject→rejected / `task_not_in_review` / `no_execution_to_review` |
| code 异常四态 + eval 可分辨 | s30, s32, s34, s36, s38 | `out_of_scope`（eval 键缺位形，越界 `sneaked.txt` 留证）/ `failed`（rc 3，双流原文）/ `timeout`（部分输出 `partial-out`/`partial-err` 保留）/ `eval_failed` / eval 超时 `eval_timed_out:true` 与业务失败可分辨（S-c6） |
| verify 面 | s26, s40 | `verify_completed`（`executor_command` null 键形）/ verify 形 `eval_failed` |
| 摘要与账面 | s44–s47 | task-show 全量 / task-show 缺任务 / status 全账面投影（10 任务 + 全部执行/复核记录）/ tamper `change_manifest` → `execution_digest_mismatch`（S-c1 锁定） |

错误词面经抽查与冻结 Python 逐字同形（s08 `write_scope_required: code 模式必须声明允许写集，拒绝启动`、s47 `execution_digest_mismatch: 任务 T-GOLDEN-L04-01 执行 1 的 change_manifest 与摘要不一致` 等）；掩码零泄漏核查：s46 全文无机器绝对路径（`/Users/…` 零命中）。

### 规范化与 setup（与三套旧 golden 的关系）

- canonical JSON 规则与 l01–l03 逐字相同：`sort_keys=True, ensure_ascii=False, indent=2` + 换行；非 JSON 逐字保留（防御性）
- 掩码集按 manifest `normalization.mask_fields` 数据驱动：l01/l02/l03 三套声明（created_at/recorded_at → `<TS>`、workbench_id → `<WORKBENCH_ID>`）不变 → 旧行为逐字节不变（J2 证据）；l04 声明扩展（observed_at/reviewed_at → `<TS>`、workspace → `<WORKSPACE>`），占位符形状由重放测试断言
- `setup` 块：7 个种子候选仓（`git init` + 固定 seed + 内联 `-c user.name/-c user.email/-c commit.gpgsign=false` commit）+ `nogit` 普通目录 + `nohead` 无提交仓库；提交哈希不出现在任何输出（diff index 行为内容派生）；生成器与 Java 重放按同一 setup 规格执行

### 落位与继承

golden 前置生成，连同生成器、共享件扩展、本 §G 与附录 A 审定稿随前置提交落 master（L01–L03 先例）；`lesson-04-java` 自 master 分出即继承。生成后永不手改，要变只能重生成并留证据。

### raw 现场

`.runtime/golden-l04/raw/`（gitignore，不入库）：47 组 stdout/stderr + rt 账本 + ws 夹具。重跑生成器即可复现，不依赖本机留存。

---

## R. 候选 lesson-04-java 证据（implement 授权后补记）

（待候选分支开工后补记：R1 起始红 / R2 红转绿 / R3 证据链与复验 / R4 任务账与终核 / A 门 / B 段）
