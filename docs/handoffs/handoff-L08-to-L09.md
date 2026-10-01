# Handoff：AI-BizWorkbench — L08 完成收口（阶段 4 质量链收官），下一会话启动 L09

- 生成：2026-10-01（同会话完成：L08 讲义起草裁决 → to-spec（**首次用户显式调用**，spec 落 `docs/specs/`）→ 执行验收合并 → 本 handoff）。上一会话起点 = [handoff-L07-to-L08.md](handoff-L07-to-L08.md)（其仓库状态段与「下一会话任务」段已被 L08 跨过；配合点/技能表先例仍可参考）
- 本文件**接替**上一份 handoff；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## ⚠️ 下一会话第一件事：push 待办

- **master 未 push**：`git rev-list origin/master..master --count` = 16（master 直加 4：`0230d23` 讲义定稿 → `ae79bc7` 验收合并 --no-ff → `1c1c789` 收口 → `03f1881` 本 handoff；其余 12 = merge 带入的 lesson-08 候选 commits——**正常，非异常**）。lesson-08 分支已单独全 push。开局向用户提请 push（载荷含大量 submission 证据件，遇 HTTP 400 分段推送，先例记忆）；**push 后 GitHub Actions 将首次对 master 触发 `L08 Eval Gate` Run**（终态工作流，预期绿约 1–2 分钟；submodule + python/java setup）——Run 结果留证入 L08 证据账附记或如实说明

## 仓库当前状态（master `1c1c789`，**待 push**）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：交接时点 **Tests run: 138, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（2026-10-01 复查轮改动后实跑）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@7f67533`。本会话 fetch 现查（09-30）：无新上游提交——**新会话仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代，只读封存）；`.runtime/course/L01-workbench-java/`（Java 线账本，L08 终态见下）；`.runtime/course/L07-candidate/`（gate pin 目标，勿动）；`.runtime/course/L08-candidate/`（净态 + git 起点封存 `544563b`）；`.runtime/hooks/` 持续增长；无后台服务

## 新会话必读：两道活护栏（都在生效）

1. **Stop hook**（L07）：每次回合结束过闸（净树 15–25s）；被 block = gate 如实报告，修真实原因勿绕闸；详见上一份 handoff 同名段 + [hook_staging/README.md](../../hook_staging/README.md)
2. **CI 工作流**（L08 新增）：`.github/workflows/l08-eval.yml`——on push（lesson-*/master）+ PR（master）；checkout submodule → `mvn -q process-test-classes` 构建（**不跑 mvn 测试面**：终态合同测试与 A/B 教学态自指冲突的裁量，双重披露在 [CI_GATE_SPEC.md](../../CI_GATE_SPEC.md) §门定义 + L08 证据账 §5.2）→ `SalesChecks --target vendors/flowERP` → `./bin/wb ci-evidence` 信封 → artifact 上传（`if-no-files-found: error`）。push 后用 `gh run list` 核对；**改工作流前先读 CI_GATE_SPEC**（门规格是合同写集映射件）

## L08 终态与遗留（详见 [docs/replication/evidence/L08.md](../replication/evidence/L08.md)）

- 任务 `CASE-WB-L08-001`：verify `verify_completed`（execution 9，actor **Claude**——L07 教训已吸收）；records 74–81（业务链 75–77 + 工具链链补全 74/79/80 + 观察 78/81）；`workbench-status --require-red-green-evidence` rc 0
- 七次真实 Run 受控对照（A 假绿/B 可信红 attempt 3/C 可信绿/隔离/基线/恢复/PR merge 测试）：原件 + `audit.py` 复验脚本在 `lesson-08-submission/ci/`（永不移动）；PR #1 已关闭（存档）
- **遗留观察项**：B Run attempt 1（Maven Central 瞬时失败）留在平台 Run 史，复验时看 attempt 3；无未解决悬案

## 下一会话任务：主线 L09（阶段 5 首讲，粒度已裁定**逐讲**）

L08 检查点已裁（2026-10-01 用户具名）：后半程逐讲（L09–L12；L13+ 未上架待后续检查点）——已入 roadmap 阶段 5 行。

L09 合同现查（`CourseContracts.java` LESSONS[9]「把失败报告翻译成修复任务」，repair 阶段，以现查为准）：workbench_increment「报告到 Repair Task 的确定性映射」/ erp_increment「交付取消释放预占」；绑定 eval `cancellation_releases_reservation`；写集 `flowerp/ agent/ eval/ tests/`（课程路径口径，映射在讲义定；`agent/` 是新面——L10/L11 的 agent_loop/repair_loop 系前奏，讲前现查上游 `workbench/repair_loop.py` 等参考件在场性）。**上游 L09 讲义已上架**（pin 内 `dd9f205`）。

开局顺序（L07/L08 同款）：

1. `git -C vendors/CodexFDE fetch` 现查（报告结果；有新上游走检查点裁决 ADR-0003）
2. 通读上游 `vendors/CodexFDE/docs/courses/L09/` 五件套 + 客户真理现查（取消释放预占面：`flowerp/sales.py:591` `cancel` 在场——释放逻辑与 FlowERP 边界 3 的后件义务；绑定 eval 在场性盲区）
3. 起草 `docs/lessons/L09-*.md`（四段结构 + §5 候选表一次列全等具名确认；命名拟 `lesson-09` / `evidence/L09.md` / `CASE-WB-L09-001` / `lesson-09-submission/`），**用户审完讲义才动手**
4. 动工：候选分支（ADR-0004），commit 1 = 合同测试起始红 + 红证据

## 配合点（届时停下提醒用户）

- L09 讲义候选表：用户具名确认（一次列全等一句）
- `/mattpocock-skills:to-spec`（用户显式；**L08 先例已立**：接缝确认 → spec 落 `docs/specs/LNN-*.md`，无 issue tracker 口径见讲义导航注记）
- `/mattpocock-skills:implement`（用户显式，动笔前）
- 收口前：`code-review` 双轴（模型自调，L08 先例：并行 sub-agent + S/T 编号处置表）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L09 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| spec 合成 | `/mattpocock-skills:to-spec` | 用户显式（L08 先例） |
| L09 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 机制图（如讲义需要） | `archify` | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求验收后的「没问题，继续」= 签收
- 收口时照例提请用户 push；push 后核对首条 master CI Run（上面 ⚠️ 段）
- 执行纪律见记忆 feedback-l07-execution-discipline（actor/指纹/rc）+ **feedback-l08-execution-discipline（新四条：eval-command 绝对路径+本地展开；工具链证据不入任务账 JD7；submodule .git 是文件；链封条勿并发）**

## 教学线（learning/，用户自定节奏，不催）

- L07 复盘课未做、L08 复盘课新候选、0001/0002 测验仍未作答、cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-10-01）

- `mvn test` → **Tests run: 138, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**，rc 0
- `git -C vendors/CodexFDE fetch` 后现查（09-30 起草日）：无新提交，双 submodule status 恒空，pin `7f67533` 不动
- `git rev-list origin/master..master --count` = 16（master 直加 4 含本 handoff + merge 带入 12 候选，push 待办——见顶部 ⚠️ 段）
