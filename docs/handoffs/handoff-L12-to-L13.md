# Handoff：AI-BizWorkbench — L12 完成收口（agent 家族四件收齐），下一会话 L13 开局

- 生成：2026-10-02（同会话完成：fetch 现查无新上游 → L12 讲义起草裁决（D1–D9）→ to-spec（用户显式，两新高缝具名）→ implement（用户显式）→ 起始红 23 → 转绿 234 → 对账实验 → 必红选跑采集 → verify → code-review 双轴（真缺陷 T-1 test-first 修复）→ 具名验收 → 合并收口 → push ×2 + CI 三 Run 留证 → 记忆入账 → 本 handoff）。上一会话起点 = [handoff-L11-to-L12.md](handoff-L11-to-L12.md)
- 本文件**接替**上一份 handoff；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `53ee1ca`，已 push，领先 0）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：收口后实跑 **Tests run: 237, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（= 211 既有 + L12 新 26：graph 二十用例（含复查轮 +3）+ checks 六用例）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@406f7aa`（检查点 0003；本会话 fetch 现查 pin 后无新提交）。新会话**仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代，只读封存）；`L01-workbench-java/`（Java 线账本，L12 终态见下）；`L07/L08/L09-candidate/L10-candidate/L10-runs/L11-candidate/L11-integration/L11-integration-replay/`（勿动）；`.runtime/hooks/` 持续增长；无后台服务与未收任务
- CI：三条 Run 全绿实测——`36984963080`（checkout `9f323de` 收口）+ `36985081145`（checkout `53ee1ca` 附记）+ handoff 期 `36976042939`（checkout `a5279c1`）；本 handoff commit 推送后再触发一条（预期绿，口径同 L12 证据账 §7 附记）

## 新会话必读：两道活护栏（都在生效）

1. **Stop hook**（L07）：每次回合结束过闸（净树 15–25s）；被 block = gate 如实报告，修真实原因勿绕闸；详见 [hook_staging/README.md](../../hook_staging/README.md)
2. **CI 工作流**（L08）：`.github/workflows/l08-eval.yml`——L09/L10/L11/L12 四讲均裁定不动（**改工作流前先读 CI_GATE_SPEC**，门规格是合同写集映射件；远程接入新检查面留检查点统一裁量）

## L12 终态与遗留（详见 [docs/replication/evidence/L12.md](../replication/evidence/L12.md)）

- 任务 `CASE-WB-L12-001`：verify `verify_completed`（execution 13，actor Claude），task_state review；链闭合 red(107) < diff(108) < green(109)；具名验收行已入账 §7（验收人 XianReallyHot-ZZH，接受）
- 交付件：`workbench/graph/GraphRunner` + REGISTRY `graph-run`（上游 `agent/graph.py` 对应物——六状态机逐句对齐、退出码 **0/3/2（3 = 正常等待不是失败）**、`_result` 十字段状态文件、`--suite-command` 子进程缝 + RepairMapper 复用；上游四缺口如实暴露不粉饰：move 不验边 / 空报告进等待 / 批准未绑候选 / 姓名不认证）+ `workbench/evals/l12/` 两件（ApprovalProbe 九模式——触发器注入 `:66-67` 逐字源**内联**，不建注入件不建拷贝树 / ApprovalChecks 十件默认门 + 两必红选跑）
- **受控对账实验**（acceptance[2]）：graph 真实走通（suite = 真实 ApprovalChecks：等待 rc 3 → 具名批准 rc 0 零新查）；双端原件三份 + 对账表 + 同源机检（`state.report == business-gate.json`）在 [lesson-12-submission/12-reconciliation/](../../../lesson-12-submission/12-reconciliation/reconciliation.md)
- **R1–R6 对照账三分类**（L11 乙移交处置，L12.md §4）：R1/R4 运行实证（必红选跑留证——客户原样行为不修绿不删报告）；R2/R3/R5 引用不复现；R6 待验证。**无未解决悬案**
- 复查轮：T-1 真缺陷（修复任务缺省路径与 spec 矛盾）test-first 修 + T-2「声称已检实未检」补 CLI 可观察面 + T-c1 rounds 严格化 + T-c2 三拍补公共尾断言 + S-2/S-3/S-4 三小修；T-3b 已核无误（x-mode 拒收护栏实证）；搁置 2（S-1 修复上下文与 LoopRunner 重复 / T-3a 探针阶段快照落盘原件——**检查点候选**）

## L12 执行教训（已入记忆 feedback-l12-execution-discipline，此处仅指针）

夹具**控制流**也要逐字段对照（`elif mode!="unapproved"` 误写 `else`——不只数据，分支结构同纪律）；REGISTRY 命令 checked 异常逃逸面用 `UncheckedIOException` 就地包装保词面（内部方法勿挂 `throws Exception`）；javadoc 声称的行为面必须有类内对应用例（上游库面探针在 CLI-only 缝仓库换可观察面承载）。

## 下一会话任务：主线 L13（operate 阶段首讲）

**L13 上游讲义 pin 内未上架**（roadmap：L13+ 未上架，待后续检查点随 fetch 现查再裁）——开局第一件事即 fetch 现查；有新上游则走检查点裁决（ADR-0003），无则与用户商量安排（教学线支线 / 检查点自建讲义 / 其他）。

L13 合同现查口径（`CourseContracts.java` LESSONS[13]，以新会话现查为准）：主题「把执行链路封装成任务 API」，**operate 阶段首讲**；workbench_increment「可追溯 Task API 与异步状态」/ erp_increment「通过 API 交付补货需求」；描述「从 Task API 提交补货需求，生成 Spec、执行 Eval，并停在具名人工审核」；demo `PURCHASE:COURSE-DEMO`；写集 **`workbench/ flowerp/ eval/ tests/`**（注意：workbench/ 首次进写集——上游 `workbench/workflow_graph.py` 治理层与 course-status 等命令是否属 L13 范围，起草时按当期上游讲义裁决）；验收三条「API 接受请求后返回 Task ID 而非伪称完成」「任务事件可追溯到需求、业务对象和 Eval」「全绿后仍停在人工审核」；绑定 eval `delivery_evidence_and_review_controls` + `purchase_requires_approval`（后者 L12 已有客户名照跑先例）。L12 建成的 graph-run 状态机是 L13「停在人工审核」的状态投影源（因果交接见讲义 §1.5）。

## 配合点（届时停下提醒用户）

- 开局 fetch 现查 → 有新上游走检查点裁决（ADR-0003），用户裁决
- L13 讲义候选表：用户具名确认（一次列全等一句）
- `/mattpocock-skills:to-spec`（用户显式；L08–L12 先例已立：接缝确认 → spec 落 `docs/specs/`）
- `/mattpocock-skills:implement`（用户显式，动笔前）
- 收口前：`code-review` 双轴（模型自调，并行 sub-agent + S/T 编号处置表——L12 抓获真缺陷先例：T-1 spec/实现矛盾 + T-2 声称已检实未检）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L13 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| spec 合成 | `/mattpocock-skills:to-spec` | 用户显式 |
| L13 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 机制图（如讲义需要） | `archify` | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求后的「没问题，继续」= 签收（L12 验收与 push 授权均此口径）
- 收口时照例提请用户 push（L12 push 两次均一次成功未触发 HTTP 400 分段预案；预案记忆仍在：**核真实领先数用 `git rev-list --count origin/master..master`**，勿口头估数）
- 执行纪律见记忆 feedback-l07/l08/l09/l10/l11-execution-discipline + **L12 新增三条（已入记忆 feedback-l12-execution-discipline）**

## 教学线（learning/，用户自定节奏，不催）

- L07–L11 复盘课未做、**L12 复盘课新候选**、0001/0002 测验仍未作答、cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-10-02）

- `mvn test` → **Tests run: 237, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**，rc 0（复查轮后实跑）
- push 两次均一次成功：`a5279c1..9f323de`（9 commits）+ `9f323de..53ee1ca`（附记）
- CI 三 Run 全绿实测：`36984963080`（`9f323de`）/ `36985081145`（`53ee1ca`）/ `36976042939`（`a5279c1`，handoff 期预期绿兑现）
- master 头 = `53ee1ca`（L12 附记）；本 handoff commit 为新头，push 后再触发一条 master Run（预期绿）
