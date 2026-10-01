# Handoff：AI-BizWorkbench — L09 完成收口（阶段 5 首讲），下一会话启动 L10

- 生成：2026-10-01（同会话完成：开局 push 待办清账 + L08 证据账附记 master Run → L09 讲义起草裁决 → to-spec（用户显式）→ implement（用户显式）→ 执行验收合并 → 收口 push → 本 handoff）。上一会话起点 = [handoff-L08-to-L09.md](handoff-L08-to-L09.md)（其仓库状态段与「下一会话任务」段已被 L09 跨过；配合点/技能表先例仍可参考）
- 本文件**接替**上一份 handoff；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `e6118d2`，已 push，领先 0）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：交接时点 **Tests run: 163, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（复查轮处置后实跑）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@7f67533`。本会话 fetch 现查（10-01）：无新上游提交——**新会话仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代，只读封存）；`.runtime/course/L01-workbench-java/`（Java 线账本，L09 终态见下）；`.runtime/course/L07-candidate/` 与 `.runtime/course/L08-candidate/`（勿动）；`.runtime/course/L09-candidate/`（净态 + git 起点封存 `49b369e`，恢复后 service.py 字节级 == vendors）；`.runtime/l09-repair/`（红报告 + 草案 v1 留档/v2 有效）；`.runtime/hooks/` 持续增长；无后台服务与未收任务

## 新会话必读：两道活护栏（都在生效）

1. **Stop hook**（L07）：每次回合结束过闸（净树 15–25s）；被 block = gate 如实报告，修真实原因勿绕闸；详见 [hook_staging/README.md](../../hook_staging/README.md)
2. **CI 工作流**（L08）：`.github/workflows/l08-eval.yml`——on push（lesson-*/master）+ PR（master）；checkout submodule → `mvn -q process-test-classes` 构建（不跑 mvn 测试面，双重披露在 [CI_GATE_SPEC.md](../../CI_GATE_SPEC.md)）→ `SalesChecks --target vendors/flowERP` → `./bin/wb ci-evidence` 信封 → artifact 上传（`if-no-files-found: error`）。**L09 裁定（D8）：本讲未动该工作流**——L09 新检查面（CancelChecks）不在 CI，远程接入留后续检查点统一裁量；**改工作流前先读 CI_GATE_SPEC**（门规格是合同写集映射件）

## L09 终态与遗留（详见 [docs/replication/evidence/L09.md](../replication/evidence/L09.md)）

- 任务 `CASE-WB-L09-001`：verify `verify_completed`（execution 10，actor Claude），task_state review；records 82–88（业务链红 82 / 注入面 diff 83 / 绿 84、85 / 实现面 diff 86 / 绿 87 / 净树观察 88）；`workbench-status --require-red-green-evidence` ok rc 0
- 受控修复链全走通（本讲主线）：注入（267c267 一行替换，上游 diff 缺席自拟——D5 如实记录）→ CancelChecks 红（**四项一因**：normal/repeat/write_error/客户绑定 eval；draft/shipped/formal/mapper/frozen 绿=隔离护栏）→ `repair-map` 草案（report_sha256 `baab1e1c` 对账）→ **用户具名确认范围**（原话「没问题，继续」）→ 反向替换恢复 → 同命令绿
- 交付件：`workbench/repair/RepairMapper`（严格三态，REGISTRY `repair-map`）+ `workbench/evals/l09/` 三件（CancelProbe/CancelChecks 九登记项/InjectCancelDefect）；`SalesChecks.customerCase` 可见性开放（public，行为零变化）
- 顺带清账：`Args` repeating 语义修复（重复出现覆盖 → append，对齐上游 argparse；L04 `--write-scope` 面零回归经全量验证）——L10 起多值参数方可依赖
- **无未解决悬案**；复查轮 S-1/T-1/T-5/T-6/T-7 修、T-2/T-3/T-8/T-9 与 S-2..S-5 披露/搁置全记录（证据账 §6）

## 下一会话任务：主线 L10（阶段 5 第二讲）

L10 合同现查口径（`CourseContracts.java` LESSONS[10]，以新会话现查为准）：主题「建立有停止条件的修复 Loop」，repair 阶段；workbench_increment「带预算与停止条件的修复 Loop」/ erp_increment「交付合法订单状态迁移」；描述「用最多三轮的修复 Loop 阻断订单跳过前置状态直接发货」；绑定 eval 与写集讲前现查。**L09 的 RepairMapper + 单轮受控修复就是 Loop 的每轮体**（`max_attempts: 1` 字段已在任务结构里，L10 加预算与停止条件）；上游 `agent/` 家族（loop.py/graph.py/schedule.py 等）是参考件——L09 只建了 repair 件，其余未复刻（CLAUDE.md 待建设清单已更新）。**上游 L10 讲义在 pin 内已上架**（`dd9f205` publish L09–L12）。

开局顺序（L07/L08/L09 同款）：

1. `git -C vendors/CodexFDE fetch` 现查（报告结果；有新上游走检查点裁决 ADR-0003）
2. 通读上游 `vendors/CodexFDE/docs/courses/L10/` 五件套 + 客户真理现查（非法状态迁移面：客户 `illegal_transition_is_blocked` 在场——`eval/harness.py` 注册；入门 `service.py` `ship_order` 状态机与正式 `sales.py` 迁移守卫——现查为准）+ 上游 `agent/loop.py` 等参考件在场性
3. 起草 `docs/lessons/L10-*.md`（四段结构 + §5 候选表一次列全等具名确认；命名拟 `lesson-10` / `evidence/L10.md` / `CASE-WB-L10-001` / `lesson-10-submission/`），**用户审完讲义才动手**
4. 动工：候选分支（ADR-0004），commit 1 = 合同测试起始红 + 红证据

## 配合点（届时停下提醒用户）

- L10 讲义候选表：用户具名确认（一次列全等一句）
- `/mattpocock-skills:to-spec`（用户显式；L08/L09 先例已立：接缝确认 → spec 落 `docs/specs/`）
- `/mattpocock-skills:implement`（用户显式，动笔前）
- 修复链草案人审环节（L09 D6 先例：allowed_files/objective/scope/命令一次核对，等具名）
- 收口前：`code-review` 双轴（模型自调，并行 sub-agent + S/T 编号处置表）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L10 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| spec 合成 | `/mattpocock-skills:to-spec` | 用户显式 |
| L10 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 机制图（如讲义需要） | `archify` | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求验收后的「没问题，继续」= 签收
- 收口时照例提请用户 push（本讲两次 push 均一次成功未触发 HTTP 400 分段预案；预案记忆仍在：核真实领先数、分段推、勿信 up-to-date 字样）
- 执行纪律见记忆 feedback-l07-execution-discipline（actor/指纹/rc）+ feedback-l08-execution-discipline（eval-command 绝对路径+本地展开；工具链证据不入任务账 JD7；submodule .git 是文件；链封条勿并发）+ **L09 新增三条（候选自记，建议入记忆）**：①链判定的 diff 须 rc 0——注入/恢复面 diff（rc 1）留账不参与机器链，机器链 diff 用实现面 git diff 补（L08 §3.6 同构）；②恢复后 diff 空输出会被诚实护栏拒收（预期行为非缺陷）；③verify 前置要求候选树 git 起点封存（InjectDefect 拷贝树缺 .git 会 workspace_invalid）

## 教学线（learning/，用户自定节奏，不催）

- L07 复盘课未做、L08 复盘课候选、**L09 复盘课新候选**、0001/0002 测验仍未作答、cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-10-01）

- `mvn test` → **Tests run: 163, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**，rc 0（复查轮处置后；= 161 既有 + 2 枚复查轮回归锚）
- `git -C vendors/CodexFDE fetch` 后现查：无新提交，双 submodule status 恒空，pin `7f67533` 不动
- push 两次均一次成功：`55ee914..eddd272`（10 commits）+ `eddd272..e6118d2`（本 handoff 前的附记 commit）；master CI Run `36881281229`（checkout `eddd272`，success 47s）
- master 头 = `e6118d2`（L09 附记）；本 handoff commit 为新头，push 后再触发一条 master Run（预期绿，口径同 L09 证据账 §8）
