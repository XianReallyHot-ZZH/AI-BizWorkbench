# Handoff：AI-BizWorkbench — L11 完成收口（agent 家族第三件），下一会话启动 L12

- 生成：2026-10-02（同会话完成：fetch 现查无新上游 → L11 讲义起草裁决（D1–D9）→ to-spec（用户显式，两新高缝具名）→ implement（用户显式）→ 起始红 10 → 转绿 210 → 受控整合（含 20/2/18 夹具真缺陷 test-first 修复）→ 真实并行双子代理 → 溯源整合 → verify → code-review 双轴（根语义真缺陷 T-1 修复）→ 具名验收 → 合并收口 → push ×2 + CI 留证 → 记忆入账 → 本 handoff）。上一会话起点 = [handoff-L10-to-L11.md](handoff-L10-to-L11.md)
- 本文件**接替**上一份 handoff；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `00f557d`，已 push，领先 0）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：收口后实跑 **Tests run: 211, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（= 190 既有 + L11 新 21：合同测试 10 + schedule 机检 11 含根语义回归锚）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@406f7aa`（检查点 0003；本会话 fetch 现查 pin 后无新提交）。新会话**仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代，只读封存）；`L01-workbench-java/`（Java 线账本，L11 终态见下）；`L07/L08/L09-candidate/L10-candidate/L10-runs/`（勿动）；**`L11-candidate/`**（净态 + 甲产物 `tests/test_l11_purchase.py`；拷贝树内 git 起点封存 `53f77a8`，已跟踪面零改动）；**`L11-integration/`**（v1/r2 报告 + index.json 对账件）、**`L11-integration-replay/`**（重演树）；`.runtime/hooks/` 持续增长；无后台服务与未收任务
- CI：master Run `36975672673` success（checkout `ef8cf02`，旧门照跑即绿）；附记 push（`00f557d`）再触发一条 Run（预期绿，口径同证据账 §4）

## 新会话必读：两道活护栏（都在生效）

1. **Stop hook**（L07）：每次回合结束过闸（净树 15–25s）；被 block = gate 如实报告，修真实原因勿绕闸；详见 [hook_staging/README.md](../../hook_staging/README.md)
2. **CI 工作流**（L08）：`.github/workflows/l08-eval.yml`——L09/L10/L11 三讲均裁定不动（**改工作流前先读 CI_GATE_SPEC**，门规格是合同写集映射件；远程接入新检查面留检查点统一裁量）

## L11 终态与遗留（详见 [docs/replication/evidence/L11.md](../replication/evidence/L11.md)）

- 任务 `CASE-WB-L11-001`：verify `verify_completed`（execution 12，actor Claude），task_state review；链闭合 red(95) < diff(97) < green(98)；具名验收行已入账 §7
- 交付件：`workbench/schedule/Schedule.java` 纯核（逐句对齐上游 `agent/schedule.py`；**无 CLI 面不进 REGISTRY**——D1：上游即纯库件，调用面 = `write_sets_reject_conflict` 登记项 + 合同测试）+ `workbench/evals/l11/` 三件（PurchaseProbe 七模式 / PurchaseChecks 九登记项默认门 + premature-stock 选跑 / InjectPurchaseDefect 插入式 + `--restore`，上游逐字锚点 `integration_lab.py:87-88`）
- 受控同版整合：三次检查 rc `[0,1/0]`——0/1/0 + 指纹断言（service 前两同第三异 == vendors 字节级）+ v1→r2 夹具真缺陷实录（20/2/18 → SQL 直插 → 17/2/15 上游口径）
- **真实并行双子代理**（D6，本讲核心）：两份合同（`lesson-11-submission/07-parallel/contract-{tests,risk}.md`）+ 声明检查一次冲突被拒留痕（`declared-checks.jsh`）+ Agent tool 甲（03:04:09–03:07:20，Ran 7 OK 首跑即绿）乙（03:04:15–03:10:52，零写入）**重叠 3 分钟** + 写集回查干净 + 溯源三分类 + 同版复验双绿（unittest 7/7 + PurchaseChecks 全套 rc 0）
- 复查轮：T-1 根语义真缺陷（`scope(".")` 折叠丢失 `PurePosixPath(".") == "."` 全匹配——对照表无 `.` 场景绿测不出，test-first 回归锚先红后修）+ S-2 重构 + T-2 spec 勘误（Java 源件指纹 = 证据账层面，六文件初冻 §6）+ T-3 假安全显式断言
- **无未解决悬案**；并行提效未实测不声称（挑战任务边界）

## L11 执行教训（已入记忆 feedback-l11-execution-discipline，此处仅指针）

夹具同形核查（红点数字是分歧信号）/ capture cwd 固定仓库根（候选根命令 bash -c 包装）/ 对照表盲区（逐句对齐靠代码轴复查兜底）/ 链序 diff 用 commit1..commit2 预防走对；另：真实并行写集落实 = prompt 合同 + 事后 git diff 回查；fresh general-purpose 子代理比 fork 更贴上下文隔离教学点。

## 下一会话任务：主线 L12（阶段 5 第四讲）

L12 合同现查口径（`CourseContracts.java` LESSONS[12]，以新会话现查为准）：主题「用 Graph 显式表达状态、回退和人工审核」，**build 阶段**；workbench_increment「显式状态图、回退边和具名人审」/ erp_increment「交付审批后入库」；描述「用显式 Graph 交付采购审批与入库，未经具名审批不得改变库存」；demo `PURCHASE:COURSE-DEMO` + `SKU:COURSE-DEMO`；验收三条「未审批采购入库被阻断且库存不变」「具名审批后允许一次幂等入库」「Graph 状态与 ERP 权威状态可对账」；绑定 eval `purchase_requires_approval` + `receiving_is_idempotent`（客户名原样，flowERP `eval/cases.py` 在场——本会话已核 :100 附近用例体）。**上游参考件在场**：`agent/graph.py`（123 行：DeliveryState 状态机 develop→test→rework/human_review→awaiting_human_review→completed + reject_once 回退演示 + state_file 持久化 + 具名审核人/决定/时间 + 退出码 0/3/2——本会话已精读）+ 上游 L12 讲义目录（pin 内）；客户 `approve_purchase`（service.py:340）/ `receive_purchase`（:386，ApprovalRequired 守卫 + event_key 幂等）在场且未动（L11 讲义 §1.3 明示「本讲不动」）。

**L12 起草要吃的现成材料**：L11 乙子代理已证实发现 R1–R5（全部位于客户 `receive_purchase→receive_stock` 段 = L12 业务面）——两段事务窗口（成功显示成失败）/ 换键重驱双重入库 / event_key=None 绕过幂等 / 已消费键静默吞入库（失败显示成成功）/ 双事件重放误拒 + R6 待验证；见 [lesson-11-submission/07-parallel/risk-review.md](../../lesson-11-submission/07-parallel/risk-review.md) 三分类表。注意口径：这些是**客户真理的原样行为**（上游参考实现同形），L12 讲义须处理「客户 eval `receiving_is_idempotent` 的实际断言面 vs 乙发现的键治理缺口」的对照关系——如实记录，不把客户原样行为写成 L12 复刻的缺陷。

开局顺序（L07–L11 同款）：

1. `git -C vendors/CodexFDE fetch` 现查（报告结果；有新上游走检查点裁决 ADR-0003）
2. 通读上游 `vendors/CodexFDE/docs/courses/L12/` 五件套 + 客户真理现查（审批/入库面 + 两绑定 eval 用例体）+ `agent/graph.py` 精读（本 handoff 已给骨架，仍须逐句）
3. 起草 `docs/lessons/L12-*.md`（四段结构 + §5 候选表一次列全等具名确认；命名拟 `lesson-12` / `evidence/L12.md` / `CASE-WB-L12-001` / `lesson-12-submission/`），**用户审完讲义才动手**
4. 动工：候选分支（ADR-0004），commit 1 = 合同测试起始红 + 红证据

## 配合点（届时停下提醒用户）

- L12 讲义候选表：用户具名确认（一次列全等一句）
- `/mattpocock-skills:to-spec`（用户显式；L08–L11 先例已立：接缝确认 → spec 落 `docs/specs/`）
- `/mattpocock-skills:implement`（用户显式，动笔前）
- 收口前：`code-review` 双轴（模型自调，并行 sub-agent + S/T 编号处置表——L11 抓获真缺陷先例：对照表盲区靠 spec 轴逐句对照兜底）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L12 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| spec 合成 | `/mattpocock-skills:to-spec` | 用户显式 |
| L12 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 机制图（如讲义需要） | `archify` | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求验收后的「没问题，继续」= 签收
- 收口时照例提请用户 push（本讲 push 两次均一次成功未触发 HTTP 400 分段预案；预案记忆仍在：**核真实领先数用 `git rev-list --count origin/master..master`**——本讲实测 12，勿口头估数）
- 执行纪律见记忆 feedback-l07/l08/l09/l10-execution-discipline + **L11 新增四条（已入记忆 feedback-l11-execution-discipline）**

## 教学线（learning/，用户自定节奏，不催）

- L07 复盘课未做、L08/L09/L10 复盘课候选、**L11 复盘课新候选**、0001/0002 测验仍未作答、cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-10-02）

- `mvn test` → **Tests run: 211, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**，rc 0（收口 commit 后实跑）
- push 两次均一次成功：`3c342be..ef8cf02`（12 commits）+ `ef8cf02..00f557d`（附记）；master CI Run `36975672673`（checkout `ef8cf02`，success）；附记 push 再触发一条 master Run（预期绿，口径同 L11 证据账 §4）
- master 头 = `00f557d`（L11 附记）；本 handoff commit 为新头，push 后再触发一条 master Run（预期绿）
