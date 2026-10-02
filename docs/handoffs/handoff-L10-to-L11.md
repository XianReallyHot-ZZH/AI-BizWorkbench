# Handoff：AI-BizWorkbench — L10 完成收口（阶段 5 第二讲），下一会话启动 L11

- 生成：2026-10-02（同会话完成：检查点 0003 → L10 讲义起草裁决 → to-spec（用户显式）→ implement（用户显式）→ 起始红 26 → 转绿 189 → 三轨迹受控实验 → 修复链草案人审（用户具名）→ 复查轮双轴四修 → 验收合并 → 收口 → push + CI 留证 → 本 handoff）。上一会话起点 = [handoff-L09-to-L10.md](handoff-L09-to-L10.md)
- 本文件**接替**上一份 handoff；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `e50dc7f`，已 push，领先 0）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：收口后实跑 **Tests run: 190, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（= 189 + 1 复查轮回归锚 `loopSuiteTimeoutIsStructuredInvalidReport`）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@406f7aa`（**检查点 0003**，本会话 fetch 现查推进 + changelog 入 §10）。新会话**仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代，只读封存）；`L01-workbench-java/`（Java 线账本，L10 终态见下）；`L07/L08/L09-candidate/`（勿动）；**`L10-candidate/`**（恢复后净态——service.py 字节级 == vendors；git 起点 `905c12f` 封存注入态）；**`L10-runs/`**（dry-run / main-repair / no-change / last-repair 各自目录 + after-loop-audit，互不覆盖）；`.runtime/l10-patch/`（restore.py / noop.py 教学补丁）；`.runtime/hooks/` 持续增长；无后台服务与未收任务

## 新会话必读：两道活护栏（都在生效）

1. **Stop hook**（L07）：每次回合结束过闸（净树 15–25s）；被 block = gate 如实报告，修真实原因勿绕闸；详见 [hook_staging/README.md](../../hook_staging/README.md)
2. **CI 工作流**（L08）：`.github/workflows/l08-eval.yml`——L09/L10 两讲均裁定不动（**改工作流前先读 CI_GATE_SPEC**，门规格是合同写集映射件；远程接入新检查面留检查点统一裁量）

## L10 终态与遗留（详见 [docs/replication/evidence/L10.md](../replication/evidence/L10.md)）

- 任务 `CASE-WB-L10-001`：verify `verify_completed`（execution 11，actor Claude），task_state review；链闭合 `workbench-status --require-red-green-evidence` ok / `pending_human_review`
- 交付件：`workbench/loop/LoopRunner`（决策序 有效性→达标→无进展→时间→Token→任务→执行 + **上游四缺口全补**：executor 异常结构化 / 空报告拒绝 / 待复验标记+两候选指纹分开 / 检查独立超时）+ REGISTRY `loop-run` + `workbench/evals/l10/` 三件（ShipProbe / ShipChecks 六登记项——发货面源件全冻指纹 / InjectShipDefect **上游逐字锚点** candidate_loop_lab.py:24-25）；`RepairMapper.Context` 增 `checksMainClass`（L09 缺省零变化）
- 三轨迹全走通：收敛 converged 2/1（恢复后字节级 == vendors）/ no-change stopped_no_progress 2/1 / last-repair stopped_max_rounds 1/1 + 待复验 + 循环外审计单独目录不回填
- 复查轮四修含两起真缺陷（超时路径 `Map.of` 空值 NPE / 草案命令面错指 L09 CancelChecks）；spec 五处勘误已钉死（docs/specs/L10-*.md 勘误节——**读 spec 时以勘误为准**）
- **无未解决悬案**；claude 执行器接线未真实调用（D6 声明在案）
- CI：master Run `36955232076` success（checkout `86a311c`，旧门照跑即绿）

## L10 执行教训（候选自记，建议入记忆）

1. **链 diff 居间性**：diff capture 必须在树红**之后**、绿**之前**——本次实现面 diff 采早（红之前）致 `same_command_red_diff_green_missing`；修复 = 补采 `git diff <commit1> <commit2> -- src/…`（rc 0 居间）+ 复采同命令绿（L09 同构、顺序陷阱再现）
2. **mvn -q 全绿 stdout 空** → ImportEvidence 诚实拒收（护栏预期非缺陷）；链上红绿用**检查命令** capture（L09 先例），被拒 attempt 留盘不删
3. `Map.of` 不容忍 null 值——超时路径 returncode=null 直接 NPE；该缺陷只有把超时压短到可测（`WORKBENCH_LOOP_SUITE_TIMEOUT` 测试缝）才暴露——**缺证据面的补齐项（如 D4-④）值得专设测试缝**
4. 探针三量（on_hand/reserved/available）走 `service.product()` 计算列，`available` **不在 stock 表**——真树首跑抓获（合同测试假树测不出数据面）
5. 红点构成预测要按模式准备阶段逐行核（double-ship 准备阶段走首次合法发货——注入破坏它，红点三项非两项；上游明示口径「重复用例在第一次发货时就失败」）

## 下一会话任务：主线 L11（阶段 5 第三讲）

L11 合同现查口径（`CourseContracts.java` LESSONS[11]，以新会话现查为准）：主题「用 Codex 原生 Subagents 并行处理独立任务」，**build 阶段**；workbench_increment「独立写集调度与串行集成」/ erp_increment「交付采购申请」；描述「将采购申请拆成互不冲突的实现与反证任务，串行集成后统一复验」；验收含「写集冲突的子任务不得并行」；绑定 eval 讲前现查。**上游参考件在场**：`agent/schedule.py`（85 行：Subtask/read_set/write_set/resource_set + `_scope`/`_overlap` 写集重叠检查——L11 核心）+ `agent/graph.py`（123 行：DeliveryState 交付状态机，L11+ 材料）；上游 L11 讲义在 pin 内（dd9f205 publish + 406f7aa 重写版）。L10 的 Loop 是纵向重复，L11 是横向分工——同族边界（会改同一文件的任务不得并行）。

开局顺序（L07–L10 同款）：

1. `git -C vendors/CodexFDE fetch` 现查（报告结果；有新上游走检查点裁决 ADR-0003）
2. 通读上游 `vendors/CodexFDE/docs/courses/L11/` 五件套 + 客户真理现查（采购面：`flowerp/service.py` propose_purchase/approve_purchase/receive_purchase + `eval/cases.py` purchase_requires_approval 等——现查为准）+ `agent/schedule.py` 精读
3. 起草 `docs/lessons/L11-*.md`（四段结构 + §5 候选表一次列全等具名确认；命名拟 `lesson-11` / `evidence/L11.md` / `CASE-WB-L11-001` / `lesson-11-submission/`），**用户审完讲义才动手**
4. 动工：候选分支（ADR-0004），commit 1 = 合同测试起始红 + 红证据

## 配合点（届时停下提醒用户）

- L11 讲义候选表：用户具名确认（一次列全等一句）
- `/mattpocock-skills:to-spec`（用户显式；L08–L10 先例已立：接缝确认 → spec 落 `docs/specs/`）
- `/mattpocock-skills:implement`（用户显式，动笔前）
- 修复链草案人审环节（L09 D6 / L10 承袭先例：allowed_files/objective/scope/命令一次核对，等具名——L11 若有受控链）
- 收口前：`code-review` 双轴（模型自调，并行 sub-agent + S/T 编号处置表）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L11 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| spec 合成 | `/mattpocock-skills:to-spec` | 用户显式 |
| L11 执行 | `/mattpocock-skills:implement` | 用户自显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 机制图（如讲义需要） | `archify` | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求验收后的「没问题，继续」= 签收
- 收口时照例提请用户 push（本讲 push 两次均一次成功未触发 HTTP 400 分段预案；预案记忆仍在：**核真实领先数用 `git rev-list --count origin/master..master`**——本讲教训：口头估的 4 实为 10，merge 分支 commit 全在集内，勿信口头数）
- 执行纪律见记忆 feedback-l07/l08/l09-execution-discipline + **L10 新增五条（上文「L10 执行教训」，建议入记忆 feedback-l10-execution-discipline）**

## 教学线（learning/，用户自定节奏，不催）

- L07 复盘课未做、L08/L09 复盘课候选、**L10 复盘课新候选**、0001/0002 测验仍未作答、cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-10-02）

- `mvn test` → **Tests run: 190, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**，rc 0（收口 commit 后实跑）
- `git -C vendors/CodexFDE fetch` 后现查：无新提交（406f7aa 之后），双 submodule status 恒空
- push 两次均一次成功：`02978bc..86a311c`（10 commits）+ `86a311c..e50dc7f`（附记）；master CI Run `36955232076`（checkout `86a311c`，success）；附记 push 再触发一条 master Run（预期绿，口径同 L10 证据账 §4）
- master 头 = `e50dc7f`（L10 附记）；本 handoff commit 为新头，push 后再触发一条 master Run（预期绿）
