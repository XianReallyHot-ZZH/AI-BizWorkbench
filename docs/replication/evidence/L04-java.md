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

## R. 候选 lesson-04-java 证据（2026-09-29，implement 授权后）

### R0 环境与分支形状

- 分支 `lesson-04-java` 自 master@`76b7583` 分出（继承前置 golden + 共享件扩展）；决策记录：附录 A 全案审定 + 配合点 1 复认（降级路径，每次重新确认）见 `lesson-04-submission/java/decisions-java.md`（指针化）

### R1 起始红（C1）

- **红封条**：`lesson-04-submission/java/03-failure/20260929T124639108663Z-648136ff/`（`mvn test` 全量，rc=1，observed_at 2026-09-29T12:46:39Z）
- 红形态：**84 跑 22 红**，全部目标能力缺失类——`L04ExecutionContractTest` 21（三命令 invalid-choice rc 2 + V0 行为断言落空）+ `GoldenL04ReplayTest` 1（golden l04 重放 s06 退出码 2≠0——本讲预期红点）；就位组 3 绿（映射表声明：C9 拒绝词面 / 建设合同 Java CLI spec 解析 rc 0 / Python 工件护栏）+ 既有 59 绿
- 采红前预检同形状（首预检 83 跑 22 红暴露映射表误声明：`prerequisiteBindingFaces` 正面半边依赖 V0 → 拆分为就位拒绝面 + 红点正面；复检 25 跑 22 红符合预期后再正式采集）；commit 1 = `6598819`

### R2 红转绿（配合点 2：用户显式调用 `/mattpocock-skills:implement`）—— 2026-09-29

**实现八件**（commit 2 = 本 diff 封条，写集与附录 A2 C14 映射一致；Python 冻结面零触碰，pom.xml 零改动）：

| 交付物 | 内容 |
|---|---|
| `workbench/execution/ExecutionCommands.java` | 三命令（JD1）：REGISTRY 第二批注册；校验类拒绝不落记录、执行类失败如实落账；检查序与冻结 execution.py 逐位同形（task 存在→workspace→scope→shlex→mode 门→超时门→prompt→actor→review 态）；verify 模式 `executor_command` null、失败/超时/越界路径 eval 键缺位形（golden s30 锁定）；`change_manifest_sha256` 只入库不出账（S-c1） |
| `workbench/execution/Shlex.java` | 单串→argv（POSIX 子集：单引号字面/双引号受限转义/引号外反斜杠；不闭合即失败——词面由调用方固定给出） |
| `workbench/execution/ProcessRunner.java` | 共用进程运行器：超时杀进程留部分输出（timed_out 独立）；启动失败落 failed 记录（launch error preserved，§3-16；errno 词面语言绑定按 JD6 如实偏差）；UTF-8 replace 解码同形 |
| `workbench/execution/WriteScope.java` | 归一化（绝对路径/`..` 剔除、只剥 `./` 前缀、保序去重——S6a）+ in_scope |
| `workbench/execution/WorkspaceInspector.java` | 校验拒于进程前（目录/.git/HEAD——S-c4）；`-uall` 逐文件采集（S-c8）+ `add -N .` + diff HEAD；前后摘要对原始字节（S6b） |
| `bootstrap/Args.java` | 可重复取值选项扩展（--write-scope nargs="+" 同形；四参构造器行为零变化，L03 JD2 先例） |
| `bootstrap/PyJson.java` | `dumpsCompact`（DB JSON 列 = Python json.dumps 默认紧凑分隔符同形；change_manifest_json 出账为原文，字节保真依赖它） |
| `bootstrap/Ledger.java` | 读侧可见度放宽（latestExecution/latestReview→public、taskRow 新增、EXECUTION_COMPLETE_STATUSES→public、connection() 访问器）——Python execution.py import bootstrap 单一来源同形；`cli/Main.java` 注册缝 +1 |

**红转绿**：`mvn test` 全量 **84/84 绿**（59 既有零变化 + L04 合同 24 + golden l04 47 场景字节级一致）——V0 行为面与冻结 Python 逐字节对照达成（移植注记 A1 预期）。

**红绿间测试修正披露**（两处，期望对齐冻结实测、无断言放宽）：① stdout 期望 `"created\n"`→`"createdn"`（未引号 shell 词内反斜杠转义 `n`，golden s06 真值源）；② `change_manifest_sha256` 断言从 task-run 出账迁至 task-show DB 投影（golden s06/s44 键位证实：只入库不出账）。全记录在 `A-evidence-java.md`。

### R3 证据链（C14）

- **链三封条**（同命令 `mvn test`，observed_at 严格递增，Diff 严格居间）：red `…648136ff`（12:46:39，rc 1，84 跑 22 红）→ diff `…3d283277`（13:18:44，rc 0，暂存区全量 `git diff --cached`——新增件为主，Python 时代台账 #4 同口径如实披露）→ green `…34bbc6d0`（13:18:49，rc 0，84/84）
- 全量回归即绿命令本体（`mvn test` 就是全量门）——Python 时代"全量回归 observation"在此不另设，如实说明

### R4 A 票任务账与 A 门自举（C14）—— 2026-09-29

- **具名**：`--owner`/`--actor` = `XianReallyHot-ZZH`（用户 AskUserQuestion 确认，不代填）；账本 `.runtime/course/L01-workbench-java/`（活账本沿用）
- **任务创建**：`./bin/wb workbench-task-create … CASE-WB-L04-JAVA-001 --spec-file docs/lessons/L04-受控执行.md` → `ok:true`，requirement_summary `0ac961634cfc78d4…`
- **导入前缺链查询**（预期失败也是证据）：observation `…7ad9d554`（rc **1**，词面 `same_command_red_diff_green_missing: 缺少同命令的红—Diff—绿链`）✓
- **链导入**：Java `workbench.tools.ImportEvidence` ×3 全 rc 0（重算 SHA-256 验封）——顺序 red→diff→green
- **A 门自举实跑**（V0 第一次被真人 operator 使用，Python A 门先例）：`./bin/wb workbench-task-run CASE-WB-L04-JAVA-001 --workspace . --mode verify --eval-command "mvn test" --execution-timeout 900 --actor Claude` → rc 0，`verify_completed`、eval rc 0（`mvn test` 84/84）、task_state `review`、execution_id 1（任务账留证）
- **终态 status 密封采集**：observation `…cdb060f2`（rc 0）——`ok:true` / `evidence_complete:true` / `acceptance:pending_human_review` / `flowerp_connected:false`；链三记录 red(1)→diff(0)→green(0)，自举 execution 在账不破坏链
- 待 A 具名验收后：`workbench-task-review` 具名 approve（reviewer=用户，执行者 Claude 不得自批）→ B 门（C9 前置绑定）放行

## 7. Ticket A 具名验收（A 门）

- 验收人：XianReallyHot-ZZH
- 日期：2026-09-30
- 对象：CASE-WB-L04-JAVA-001 @ `lesson-04-java` 源码版本 `68e7d50`（任务账 review 记录在场：review_id 1 锚定 execution 1，`task_state: accepted`）
- 结论：**接受**（用户原话「没问题，继续」，A 段清单显式具名验收请求之后的答复）
- 效力：A 接受先于 B 全部记录（本行 observed_at 先于 B 任务创建与 execution 2，任务账时间序机器可证）；B 启动门（C9 前置绑定）由此放行

## 8. Ticket B 段（C10–C13）

- **B 单**：CASE-WB-L04-JAVA-002（`--prerequisite-task CASE-WB-L04-JAVA-001` 放行——C9 正面账本可证）；Spec SHA-256 `f3e81669…` 运行前后一致，Java CLI spec 解析 rc 0
- **候选**：`.runtime/course/L04-delivery-java/candidate`（起点 `e0088d3`，vendors 双查恒空，无复制回仓库）
- **N0 基线**：probe `--case all` rc 0 五组全 pass（`06-probe-n0/`，observation record_id 30）
- **受控执行（execution 2，C10–C12）**：V0 `--mode code` 组织 `claude -p`（bypassPermissions，用户确认；stream-json 事件流 26,991 行落盘；900s 预算未触顶）→ rc 0 `completed`；实测写集 = delivery/ 四件（`out_of_scope_files: []`；`practice.db` 系 gitignored 不入 change_manifest——git 实测口径既有语义，eval 权威对照独立证实其存在）；eval rc 0（V2 形态：practice.db 权威账 SQL 同源对照 + Spec 常量，CSV 7 行 = 权威 7 行零失败——**调用正常结束 ≠ 业务正确**，故有下条）
- **终验（C11）**：probe `--case all` rc 0 五组全 pass（`07-probe-final/`，observation record_id 31；file-failure 注入为真实失败实验，原始 report 在场；只读性看 iterdump 快照对照非结论文字）；按 eval 名 `inventory_export_is_stable` 登记，**harness 双轨口径如实注明（Python 侧已建成冻结 / Java 侧待建设）**
- **范围检查（C13）**：改动仅 delivery/；无密钥/.env；FDE_SPEC SHA 未变；flowerp 未复制回仓库
- **B 段证据与交接（C13）**：`B-evidence-java.md`（含剩余风险 4 条：gitignored 产物 / 模型别名回退 stderr 留痕 / 提示词约定 ≠ 事前全部防住 / 自批名义级比较）+ `handoff-java.md` 五问；B status 密封 `…28ada20b`（rc 0，`ok:true` / `acceptance:pending_human_review` / `flowerp_connected:false`，常规完整性口径）

## 9. Ticket B 具名验收（B 门·待签）

- 验收人：＿＿＿＿
- 日期：＿＿＿＿
- 对象：CASE-WB-L04-JAVA-002 @ execution 2（completed）+ 交付物 `delivery/inventory.csv`（任务账 review 门在待：`task_state: review`）
- 结论：＿＿＿＿
