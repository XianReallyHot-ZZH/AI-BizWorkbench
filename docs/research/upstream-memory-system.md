# 上游 CodexFDE 记忆系统调查笔记

- 调查日期：2026-09-28
- 调查对象：`vendors/CodexFDE`（submodule，绝对只读），当前 pin `7f6753369984b20454ba4241bc7b4209ab6a4192`（`git -C vendors/CodexFDE rev-parse HEAD` 现查；`git status --short` 恒空）
- 调查目的：为本仓库支线方案「记忆系统六步最小闭环（召回→采用→复验→失效）」提供上游事实基础。注意：六步是本仓库 roadmap 的措辞，上游有自己的步骤定义（见 §5、§8），本文按上游真实结构记录，不硬套。
- 调查方法：通读 `workbench/learning.py`（552 行，全读）、`feedback.py`、`evolution.py`、`session_context.py`、`session_persist.py`、`session_export.py`（头部）；grep 追 `LearningStore` 全部调用点；读 `docs/architecture/工作台范式与闭环建设.md`（全读）与 AGENTS.md、课程大纲、课程蓝图相关段；grep `tests/` 现查专项测试覆盖。以下路径默认相对 `vendors/CodexFDE/`，行号来自 Read 现查。

---

## 1. 模块清单与数据模型

上游的记忆系统不是一个单独文件，而是 **feedback → evolution → learning 三级 + 事项工作流编排** 的一组模块，全部落同一个 SQLite 库（默认 `.runtime/workbench.db`，`workbench/feedback.py:14`、`workbench/task_store.py:33`）。

### 1.1 `workbench/learning.py`（LearningStore，主记忆账）

模块 docstring（`workbench/learning.py:1-5`）："Evidence-bound, project-scoped memories and executable delivery recipes. Content and adoption snapshots are append-only. Status changes never rewrite historical snapshots."——经验绑定证据、按项目隔离；内容与采用快照只追加；状态变更绝不改写历史快照。

五张表（`workbench/learning.py:50-69`）：

| 表 | 字段 | 语义 |
|---|---|---|
| `learning_assets` | id, family, version, project, kind(memory/workflow), state, trial_approved, payload(JSON), sha256；UNIQUE(family,version) | 记忆/流程资产本体，family+version 构成版本链 |
| `learning_events` | id, asset_id, actor, action, note, evidence(JSON), at | 资产级只追加事件账 |
| `learning_recalls` | id(RECALL-*), initiative_id, payload(JSON) | 每次召回的完整快照包 |
| `learning_bindings` | id(BIND-*), initiative_id, plan_id(UNIQUE), task_id(UNIQUE), payload(JSON), sha256 | 采用快照（一次交付一条） |
| `learning_runs` | id, binding_id, phase, payload(JSON), at；UNIQUE(binding_id,phase) | 复用执行链的逐相位证据 |

数据完整性机制：每次读取资产都重算 SHA-256 复核（`workbench/learning.py:76-84`，失败报"经验或流程内容校验失败"）；来源快照、任务报告、原始事件轨迹都可复验（`check_source`，`workbench/learning.py:160-174`，报"来源任务报告内容已变化"/"原始轨迹已变化"）。具名门槛：`human()` 拒绝 `ai/codex/system/待确认/待定/tbd/pending/agent:*` 作为操作人（`workbench/learning.py:26-30`）。

资产 payload 字段（`workbench/learning.py:231-240`）：id、family、project、kind、supersedes、title、content、applies（适用关键词，最多 20 项）、excludes（排除关键词）、boundary（适用与不适用边界）、conflict_key（冲突主题）、source（完整来源证据快照+sha256）、created_by、created_at、version；workflow 类另有 recipe。

### 1.2 `workbench/feedback.py`（结构化反馈账）

表 `feedback`（`workbench/feedback.py:23-27`）：id(FB-*)、task_id、source、conclusion、next_step、reviewed、created_at，外加迁移列 status（默认 `pending_review`）、reviewed_by、reviewed_at、review_note、dedupe_key、evidence_json（`workbench/feedback.py:29-40`）。幂等键 `dedupe_key` 上建部分唯一索引（`workbench/feedback.py:41-44`），重复入库 `INSERT OR IGNORE`（`workbench/feedback.py:77-83`）。

关键入口：`add_feedback`（`workbench/feedback.py:62-90`）；`observe_task_failure`（`workbench/feedback.py:93-139`）——只有 rework/failed/dead_letter 终态任务可沉淀失败反馈（`:99`），自动生成失败签名哈希（`:116-118`）、evidence 带 `schema_version: workbench.failure-observation/v1`（`:124`），dedupe_key 形如 `task-failure:{task_id}:{签名}`（`:138`）；docstring 明确"Observation is automatic; acceptance and promotion remain named human acts"（`:94-96`）。`review_feedback` 只允许 accept/reject 且"反馈已经审核，不能重复改变结论"（`workbench/feedback.py:142-164`，`:155-156`）。

CLI：独立 `python -m workbench.feedback`，子命令 add/review/summary（`workbench/feedback.py:180-198`）；主 CLI 只有只读 `workbench-status` 体系外的 `feedback --runtime-dir` 摘要入口（`workbench/cli.py:69-70`、`:211-214`）。

### 1.3 `workbench/evolution.py`（EvolutionStore，失败→资产升级链）

docstring："Governed evidence that one observed failure became a verified reusable asset"（`workbench/evolution.py:29`）。表 `evolutions` + `evolution_events`（`workbench/evolution.py:40-74`）。

状态机：`proposed →（review: approve/reject/defer）→ approved/rejected/deferred →（record_assets）→ asset_changed →（verify）→ verified`（`workbench/evolution.py:172`、`:208-209`、`:257-258`；非法迁移直接报错，如 `:180`、`:258`）。关键门槛：

- 只有具名接受的反馈才能提升为进化记录（`:126-127`）；同一反馈只能形成一条（`:153-154`）；
- 进化必须由下一项独立交付任务验证，不能复用源任务（`:259-260`）；
- 对 `learning/` 引用的资产，verify 时核验后续任务确有对应 `learning_bindings` 且 `outcome` 相位 passed、全部登记版本被采用（`:261-276`）；
- 候选任务必须完成阻断 Eval 并经具名交付验收，报告哈希现算比对（`:292-315`）。

### 1.4 会话系列（`session_*.py`，属 Harness 会话运行时，不属记忆账）

- `session_context.py`：`derive_messages` 把只追加的 Session 事件日志投影为模型可见消息（`workbench/session_context.py:4-94`）；
- `session_persist.py`：`JsonlSessionPersist` 把 Session 事件镜像到 jsonl 供重启重建（`workbench/session_persist.py:9-39`）；
- `session_export.py`：导出 `harness.session.export/v1` 会话包（`workbench/session_export.py:11-38`）；
- `session_graph.py`、`session_watch.py`：会话状态图与监听。

这五个文件均不 import learning，记忆账与会话持久化无调用关系（grep 现查）。

### 1.5 调用关系（谁驱动记忆账）

| 调用方 | 用法 |
|---|---|
| `workbench/initiative_workflow.py:42` | 持有 `LearningStore`；`learning_action` 统一受理 create/approve/publish/revoke/recall/decide 六种动作（`:456-497`）；`_research` 调 `recall`（`:579`）；`confirm` 调 `bind` 并把采用快照注入冻结 Spec（`:643`、`:646-651`） |
| `workbench/daily_delivery.py:70-142` | 执行前 `validate_binding`+`attach`（`:74-76`、`:86`），执行中逐相位 `run_event`（`:118`、`:131`、`:139`），收尾 `finish`（`:142`） |
| `workbench/task_store.py:411-414`、`:434` | 任务 approve 时先 `check_acceptance`，审核落定后 `finish` |
| `workbench/evolution.py:266-273` | verify 时反查 learning 绑定与 outcome |
| `workbench/workbench_server.py:371-372` | HTTP POST 路由到 `learning_action`；前端 `workbench_web/learning.js` 提供操作界面（`docs/architecture/工作台范式与闭环建设.md:33`） |

主 CLI（`workbench/cli.py`）没有 learning 子命令；记忆治理入口在首页事项工作区（HTTP + 前端）。

## 2. 生命周期记录（上游的真实步骤）

上游对记忆条目的每一步都有对应记录，形态全部是 **SQLite 表行 + JSON payload + sha256**：

| 生命周期步 | 上游机制 | 记录落点 |
|---|---|---|
| 来源准入 | 只允许两类来源：已验收任务（status=completed 且 review_decision=approve 且有 reviewed_by），或已接受失败反馈对应的 Evolution；失败经验必须带实际失败轨迹（rework/failed/dead_letter 事件）（`workbench/learning.py:135-158`，`:138`、`:151`） | source 证据快照整体嵌入 asset payload，附 sha256（`:153-157`） |
| 写入（候选） | `create`：kind 必须为 memory 或 workflow（`:215`）；初始 state='candidate'（`:245`）；事件 action='candidate'（`:246`） | `learning_assets` 行 + `learning_events` 行 |
| 审核 | `govern`：决定限 approve/publish/revoke（`:254`）；必须非提炼者独立审核（`:258-259`）；workflow 发布前必须在独立事项显式试用并完成隔离 Eval 和人审（`:282-296`） | `learning_events`（action=approve/publish）+ 状态/试用列变更 |
| 召回 | `recall`：按项目 + 状态过滤（active，或 trial_approved 的 workflow 候选且显式选试用）（`:308`）；关键词 applies 命中、excludes 排除（`:317-321`）；同一来源事项不得作为独立复用（`:313-314`）；上下文字符预算（默认 12000，超限进 excluded）（`:332-333`）；同 conflict_key 多条命中列为 conflicts"请逐项判断，不自动合并"（`:339-343`）；检索不到如实为空 | 完整召回包（matches/excluded/conflicts/预算占用）持久化到 `learning_recalls`，id 为 RECALL-*（`:344-348`） |
| 采用/不采用决定 | `decide`：必须与本轮召回 matches 逐项对应，逐项记录 adopt 布尔 + 理由 + 操作人（`:351-379`，`:358-362`）；一次交付最多绑定一个受控流程（`:377-378`） | 决定记录暂存事项工作流 JSON（`initiative_workflow.py:488`），最终固化进绑定 |
| 采用快照 | `bind`：把采用的资产全文快照 + 参数 + 前置检查结果封进 BIND-* payload 并加 sha256（`workbench/learning.py:415-433`）；绑定任务后向任务账追加事件"记忆与流程采用快照已绑定"（`:457-466`） | `learning_bindings` 行 + 任务事件 |
| 快照防漂移 | `validate_binding`：执行前复验已采用版本内容未变、前置检查依据未变（`:445-455`）；确认方案后把采用的"有界结论"注入冻结 Spec（`initiative_workflow.py:646-651`） | 校验失败即阻断，不改数据 |
| 复验（执行链） | `run_event` 逐相位写入 precheck/implement/eval（`workbench/daily_delivery.py:118`、`:131`、`:139`）；acceptance 时 `check_acceptance` 强制三相位齐全且 passed，工作区必须隔离，候选 manifest 未变（`workbench/learning.py:473-493`） | `learning_runs` 行，UNIQUE(binding_id,phase) 每相位只记一次（`:65-67`、`:468-471`） |
| 结果回写 | `finish`：任务终态后写 review + outcome 相位（通过须有具名验收，否则"复用验证缺少具名验收"）（`:495-526`） | `learning_runs` 两行；失败保留轨迹（`:516-524`） |

上游没有把"采用决定"单独建表——它经历"召回包（learning_recalls）→ 事项工作流 JSON 暂存（`initiative_workflow.py:488`）→ 采用快照（learning_bindings，decision 内嵌于 payload，`workbench/learning.py:427-429`）"三次落点。

## 3. 相位/链判定机制

上游存在**两套互不混用的相位体系**：

1. **L01 命令证据账四相位** `red/diff/green/observation`：在 `workbench/bootstrap.py:66`（表列 `CHECK(phase IN ('red','diff','green','observation'))`）与 CLI `--phase` 选项（`:197`）。这是本仓库信用内核的上游源头，但它**只服务 L01 任务证据账，不服务记忆系统**。
2. **记忆复用链自己的五相位** `precheck / implement / eval / review / outcome`：常量定义于 `workbench/learning.py:195-198`（首版流程固定"前置检查→实现→Eval→人审"四阶段），落 `learning_runs` 表，UNIQUE(binding_id,phase) 保证每相位只追加一次（`:65-67`）；review/outcome 由 `finish` 写入（`:525-526`）。记忆相关记录**不沿用** red/diff/green/observation。

链判定方面的对应物：

- 记忆账的"链"是 **sha256 快照链**而非红绿链：资产内容、来源快照、任务报告、采用快照每次读取/执行前都重算哈希复验（`workbench/learning.py:82-83`、`:131-132`、`:441-442`、`:450-451`）；
- 日常研发明示"既有检查执行前为绿是常态，不制造红灯"（`workbench/daily_delivery.py:110-112` 注释 "Existing green checks are normal for new development; do not manufacture red."）；
- 有效的"失败进入记忆"通道不在相位层，而在来源层：失败任务轨迹 + 已接受反馈 → Evolution（§1.3）。

## 4. 失效/废弃的留痕（"失效不可抹"的对应机制）

上游不删除任何记忆条目，失效全部是**状态迁移 + 事件追加**：

- **撤回（revoke）**：`govern` 把 state 置 `revoked`（`workbench/learning.py:275-278`），已 revoked/superseded 的再撤报"该版本已停用"（`:276-277`）；每次都追加 `learning_events`（`:301`）。
- **被替代（superseded）**：发布 family 新版本时，旧 active 版本在同一事务内原子置 superseded，并带 `{'replacement': 新id}` 事件（`:297-300`）。
- **复用失败自动停用**：`finish` 中 outcome 未通过时，绑定的 **workflow 类**资产自动置 revoked，事件 action='reuse_failed'，note 逐字为"复用失败，停用此流程版本；保留来源并提炼新版本"（`:527-533`）。注意：**memory 类资产复用失败只记录 outcome，不自动撤回**——上游只对流程版本做失败自动停用。
- **失效后不默认注入**：召回过滤只取 active（或试用候选）（`:308`）；`_eligible` 拒绝"经验已撤回、被替代或未经审核"（`:381-386`）。架构规格逐字规定："过期或撤回的经验保留历史，但不得默认注入新任务"（`docs/architecture/工作台范式与闭环建设.md:67`）。
- **历史快照不可改写**：模块 docstring "Status changes never rewrite historical snapshots"（`workbench/learning.py:3-4`）；历史旧任务的采用快照始终指向当时版本（规格 `:77`）。
- 反馈层同构：已审核反馈"不能重复改变结论"（`workbench/feedback.py:155-156`）；Evolution 非法状态迁移一律拒绝（`workbench/evolution.py:180`、`:258`）。

## 5. 相关文档（路径 + 核心主张）

| 文档 | 核心主张 |
|---|---|
| `docs/architecture/工作台范式与闭环建设.md`（全读，规格正文 100 行） | 建设规格与实现对照，核对日期 2026-09-14（`:3`）。工作台 = Harness + 记忆系统 + 工作流蒸馏（`:5`）。记忆系统保留三类材料：原始经历；提炼结论；指向已发布流程版本的程序性记忆，"原始证据与提炼结论分别保存，结论始终能够回到来源"（`:65`）。每条可复用记忆至少含项目归属、来源、证据引用、内容、适用/不适用条件、状态、版本、审核记录、替代关系；四状态 candidate/active/superseded/revoked（`:67`）。召回按项目与适用条件、有限预算、带召回理由；"检索不到应如实为空"；首版 SQLite+关键词，不用外部向量服务（`:69`）。**最小可交付闭环六步**见 §6。当前回归 `Ran 30 tests, FAILED (errors=10)`（`LearningStore.project` 对无项目事项抛错）与"tests/ 检索不到 LearningStore 专项测试"均有自述（`:49`、`:51`） |
| `AGENTS.md:47-49` | 三件套范式定义（`:47`）；闭环判定："单次交付成功、事件持久化、反馈登记或资产登记，均不足以证明三者已闭环。完成须有跨事项证据：前一事项经验被后一事项召回，流程版本被明确采用，Harness 执行并保留 Eval 与人审结果，复用失败可追溯并触发修订或停用"（`:49`） |
| `docs/课程大纲-Codex-FDE行动营-个人研发自动化工作台.md:337-342` | **第 15 讲「生成交付摘要并采集真实反馈」是唯一把可治理 Memory/RAG 列入课内增量的讲次**：记忆版本与状态、元数据过滤、可解释排序、Top-k 上下文包和采用快照；"记忆采用和自动改代码之间的人工边界"；通过标准含"撤回或跨项目记忆不会默认进入上下文"（`:339-342`） |
| `docs/courses/课程蓝图.md:202` | 跨事项复用须记录：召回了哪条经验、采用哪个流程版本、执行与人审结果、复用失败后的修订或停用 |
| `docs/courses/L05/参考详解.md:380`、`L06/参考详解.md:361`、`L08/参考详解.md:248` | 各讲以同口径复述三件套："只有后续事项实际召回经验、采用流程版本并保留复验与具名审核，才能证明跨事项复用" |
| `docs/courses/L10/参考详解.md:272`、`:413` | 区分"Loop 内的任务记忆"与"跨事项记忆闭环"；验收表把 Memory（历史与经验）列为独立检查项 |
| `docs/courses/L11/参考详解.md:265`、`L11/实践操作手册.md:444`、`L12/参考详解.md:330` | 反复强调"当前规格尚未完成实现，登记 Skill/资产不足以证明闭环"；闭环声称须补来源、版本/有效状态、召回与采用理由、流程版本、执行/Eval/人审、失败处置 |
| `docs/courses/L01/assets/technical/02-harness-memory-loop.svg` | L01 教学图：记忆保留依据与结果（来源、版本、有效状态）→ 召回经验 → 采用流程 → 复验与人审的环形闭环；"L01 只验证记录基础，完整跨事项闭环由后续任务检验" |

L05–L14 各讲参考详解中记忆系统以**贯穿叙述**出现，无独立成讲的记忆系统实现课；实现本体（learning.py 等）在课程合同之外，属工作台长期建设（蓝图 `:202`）。

## 6. 上游自己的"最小可交付闭环"六步（与本仓库 roadmap 措辞不同，如实记录）

`docs/architecture/工作台范式与闭环建设.md:79-88` 定义了六步，并有"上述六步已有首版代码对应，但尚未形成通过验收的完整交付"（`:88`）的自述：

1. 从已验收事项或已接受失败反馈提炼一条有证据引用的候选经验（复用现有任务和 Evolution 标识）；
2. 在首页该事项内展示候选经验、适用边界与原始证据，完成具名审核；
3. 在另一事项调研中召回该经验，显示原因；记录采用或不采用及其理由；
4. 从真实修订轨迹提炼一份参数化流程候选，绑定版本、来源及隔离复验结果；
5. 后一事项在确认方案时明确采用流程版本与记忆快照，执行仍经过现有授权、Eval 和验收链；
6. 把复用结果回写为新证据；失败保留并推动修订或停用。首页同一事项内可追到整条因果链。

与代码的对应：①=`create`（source 准入）→ ②=`govern approve` → ③=`recall`+`decide` → ④=workflow 候选+试用 → ⑤=`bind`+冻结 Spec 注入+逐相位执行 → ⑥=`finish` outcome+失败停用/替代。即上游六步是"提炼→审核→召回/决定→流程候选→采用执行→回写治理"，**不是**本仓库 roadmap 的"召回→采用→复验→失效"四段式；失效治理在上游是第 6 步的一部分，不单列。

## 7. 边界：什么进记忆账、什么不进

**进记忆账**（`learning_assets`）：
- 只有两个入口：从已验收任务提炼，或从已接受失败反馈（经 Evolution）提炼（`workbench/learning.py:135-158`）；自动观察（`observe_task_failure`）只产生待审反馈，"观察是自动的，接受与提升保持具名人工动作"（`workbench/feedback.py:94-96`）；
- 记忆内容三分类：原始经历（留在任务/事项账）、提炼结论（memory 资产）、程序性记忆（workflow 资产）（`docs/architecture/工作台范式与闭环建设.md:65`）；
- 按项目严格隔离：跨项目召回/采用/治理均被拒（`workbench/learning.py:252-253`、`:381-383`）。

**不进记忆账**：
- **会话持久化**：Session 事件日志、jsonl 镜像、会话导出属 Harness 会话运行时（§1.4），与会话模块零调用关系；规格明确"会话恢复本身仍不等于经验复用"（`docs/architecture/工作台范式与闭环建设.md:30`）；
- **Loop 内任务记忆**：修复循环里为下一轮装配的上下文"是 Loop 内的任务记忆，不代表工作台已经形成跨事项记忆闭环"（`docs/courses/L10/参考详解.md:272`）；
- **教学/学习记录**：上游未见类似本仓库 `learning/` 教学工作区目录与记忆账的划分机制（上游 docs/courses 是课程讲义，学生证据走课程提交与 SUBMISSION，不进 workbench.db）——"上游未见"现查结论；
- **原始证据本体**：任务报告、Diff、事件轨迹留在任务账与受控 reports 目录，记忆资产只存引用+摘要+sha256，"来源轨迹仍留在证据存储"（`workbench/learning.py:327` 注释、`initiative_workflow.py:646-647` 注释）。

## 8. 对本支线方案的输入（客观事实汇总，不做方案推荐）

1. 上游记忆系统 = SQLite 单库五表（learning_assets/events/recalls/bindings/runs）+ feedback/evolution 两级前置账，全部只追加、sha256 快照链、读取时重算复核——与本仓库 L01 信用内核同构，但**不共用**四相位 red/diff/green/observation；记忆复用链自有五相位 precheck/implement/eval/review/outcome，每相位 UNIQUE 约束只记一次。
2. 上游生命周期状态机：candidate →（approve/publish）→ active →（superseded/revoked）；workflow 另有 trial_approved 试用层；复用失败自动 revoke 仅限 workflow 类，memory 类失败只记 outcome。
3. 召回是关键词匹配（applies/excludes）+ 项目隔离 + 字符预算 + 冲突显式分组（不自动合并）+ excluded 带原因，包整体落库；非向量检索，规格明示首版不依赖外部服务。
4. 采用必须逐项具名决定（adopt 布尔+理由），一次交付最多一个流程；采用快照 BIND-* 冻结并在执行前后两次 validate（版本未变+前置检查依据未变），采用的有界结论注入冻结 Spec。
5. 失效治理：不删除、只状态迁移+事件；撤回/被替代/复用失败三类失效全部保留历史与证据，失效条目不进召回。
6. 上游规格自认缺口（截至 pin 7f67533 现查）：tests/ 中 LearningStore/learning_action/learning_binding 零命中，无专项测试；规格记录的 30 tests/10 errors 回归与"效果统计待测"自述仍在文档中；文档同时自述"六步已有首版代码对应，但尚未形成通过验收的完整交付"。
7. 上游的"六步"定义（§6）与本仓库 roadmap 六步措辞不同；支线方案起草时如何对齐或另立，属主会话裁决。
8. 可直接对照的上游验收口径：闭环判定必须跨事项（AGENTS.md `:49`）；完成验收的正常/失败路径场景清单在规格 `docs/architecture/工作台范式与闭环建设.md:90-98`（跨项目召回、撤回记忆、被替换证据、跳过审批、复用失败不得因历史成功标记通过、重启后引用链可恢复、新版本不改写旧快照）。

---

调查日期：2026-09-28；上游 pin：7f6753369984b20454ba4241bc7b4209ab6a4192（submodule 现查，只读未动）。
