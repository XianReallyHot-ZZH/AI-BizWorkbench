# L13 讲义：任务 API 与异步交付——把执行链路封装成可提交、可查询的资源

- 状态：**定稿 v1**（D1–D9 已裁决，见 §5 裁决记录；执行期起手 = 用户显式 `/mattpocock-skills:to-spec`，再 `/mattpocock-skills:implement`）
- 起草：2026-10-02；上游 pin `CodexFDE@406f7aa`（检查点 0003；起草日 fetch 现查 pin 后无新提交，L13 讲义未上架）
- 合同来源：`src/main/java/workbench/coursecontracts/CourseContracts.java` LESSONS[number=13]（冻结合同，逐字引用不修）：阶段 **operate**（首讲）；workbench_increment「可追溯 Task API 与异步状态」；erp_increment「通过 API 交付补货需求」；描述「从 Task API 提交补货需求，生成 Spec、执行 Eval，并停在具名人工审核」；demo_data `PURCHASE:COURSE-DEMO`；写集 `workbench/ flowerp/ eval/ tests/`（workbench/ 首次进写集——范围按绑定 eval 断言面裁决，见 D1）；验收三条「API 接受请求后返回 Task ID 而非伪称完成。」「任务事件可追溯到需求、业务对象和 Eval。」「全绿后仍停在人工审核。」；绑定 eval `delivery_evidence_and_review_controls` + `purchase_requires_approval`；causal_link「Codex 执行被封装为有稳定身份、事件和合法状态的 Task」
- 上游参考：**无 `docs/courses/L13/`（未上架）——本讲为检查点自建讲义**（用户 2026-10-02 具名裁决）。对照面改用 pin 内更硬的事实源：①绑定 eval 断言面 `eval/cases.py:262-333` `delivery_evidence_and_review_controls`；②API 合同检查 `eval/task_api_contract.py`；③HTTP 合同测试 `tests/test_course_task_http.py`（106 行，真实端口）；④参考实现五件：`workbench/task_store.py`（437 行）、`workbench/automation.py`（338 行）、`workbench/workflow.py`（四段链）、`workbench/spec.py`（交付 Spec 生成）、`workbench/feedback.py`（失败沉淀）、`workbench/workbench_server.py`（642 行，取 L13 断言面子集）
- 本讲性质：**operate 阶段首讲 + 无上游讲义的首例**。上游工程史现查：task_store/automation/workflow/feedback 骨架 2026-08-20（dad7f5f）一次性落地，Task HTTP API 面 2026-09-06（888a0c7）净增量；基线 tag `course/l13-start` 与 `l14-start` 代码零 diff（进度标记非代码边界，2026-08-30 同日相邻提交）——**范围裁决只能以绑定 eval 断言面为界**。对本仓而言上述件全部为新面（本仓任务是 L01 证据链账本，与上游九态任务机不同源，不混库）

## 1. 讲解

### 1.1 本讲要什么

L01–L12 把工作台自身建成了：账本、Spec、受控执行、Eval、护栏、CI、修复链、Loop、调度、Graph。L04 换挡点预告的「通过工作台组织协同」在本讲全面落地为**对外资源形态**（fde_loop「把本地流程变成外部可提交、可查询资源」）：执行链路（Spec → 执行 → Eval → 人审）从一串 CLI 命令升级为**有稳定身份、事件与合法状态的 Task 资源**——需求进来先发 Task ID（202，不是伪称完成），异步推进（202 必须先于 Eval 完成返回），事件留痕可追溯（需求编号、业务对象、Eval 报告三向互查），全绿停在 `review` 等具名人审。

上游钉死的判断，本讲全链贯穿：**202 是接受不是完成**；**异步必须诚实**（不能先跑完 Eval 再返 202——上游测试用 entered/release 事件对钉死）；**状态机不可跳步**（`queued → spec_ready → executing → evaluating → review`，直跳 `completed` 一律 ValueError）；**绿报告永不自动 completed**（上游 `workflow.run_task` docstring：「A green Harness report never becomes completed without a separate named review」；`transition` 双守卫「完成交付必须由具名审核人明确 approve」「阻断级 Eval 未通过，不能批准完成」）；**并发迁移被拒**（乐观锁 version + `WHERE status=?`，「任务状态已变化，拒绝并发迁移」）；**提交幂等**（同键同需求重放返回同 Task ID，同键异需求 409「提交键已用于不同需求；请使用新键」——与 FlowERP 入库幂等键同一哲学，业务边界 2 的工作台面）；**自动化停在人审前**（上游 `DeliveryAutomation` docstring：「Automation deliberately stops at review or rework. Human approval is a separate authenticated API action and is never executed by this worker」）；**失败自动沉淀、接受永远具名**（`observe_task_failure` docstring：「Observation is automatic; acceptance and promotion remain named human acts」）；**Agent 不能代老板终审**（「Agent 员工不能代替老板终审」）。

### 1.2 业务案例：通过 API 交付补货需求（PURCHASE:COURSE-DEMO）

四拍主线：①**提交**——`POST /api/v1/delivery/requests`（body：lesson 13、request「补货…」、actor 具名、business_refs `["PURCHASE:COURSE-DEMO"]`、头 `Idempotency-Key`）→ **202** `{task_id, accepted: true, execution_mode: "verify", status_url, view_url}`，此刻 Eval 尚未跑完；②**异步推进**——Spec 自动生成（任务级六段模板，`PURCHASE:`/补货信号词触发两条业务验收用例：「补货建议尚未由具名人员批准，当尝试收货时，那么请求被阻断且库存不变」「已批准采购，当相同入库幂等键重放时，那么库存和流水只增加一次」）→ 受控执行（verify 模式 = verification_only，「不产生业务数据副作用」）→ Blocking Eval → `review`；③**查询**——`GET status_url` → `status: "review"`、`reviewed_by: null`（全绿仍停住）；④**具名审核**——`POST /api/v1/tasks/{id}/review`（reviewer 具名 + decision approve + note 必填）→ `completed` + reviewed_by/reviewed_at 落账。

失败与边界拍：Eval 红 → `rework` + 自动反馈沉淀（`pending_review`、source `automation:rework`、blocking_failures 列表入证据）+ 有界重试（max_attempts，耗尽保留失败）；同键重放 → 同 Task ID 且任务列表不增；同键异需求 / 异 business_refs → **409**；`execution_mode: "codex"` → **400**（「网页API只允许verify；代码执行须使用明确授权的课程CLI」）；business_refs 非数组 → 400；服务重启重开 → 状态仍在（SQLite 持久）；`recover()` 恢复面；**运行目录无 flowerp.db**（API 面不直接触客户库——客户真理仍经客户 eval 子进程，ADR-0005）。

### 1.3 客户真理现查（讲前复核，`vendors/flowERP` submodule 只读）

L12 现查结论在本讲继续有效（同批采购面）：`flowerp/service.py:340` approve_purchase（空审批人 ValueError / 非 proposed InvalidTransition / 具名 approved_by）与 `:386` receive_purchase（ApprovalRequired 守卫 + 幂等键重放）是「补货需求」的业务权威；绑定 eval `purchase_requires_approval`（`eval/cases.py:98`）为客户真身，L12 ApprovalChecks 已有原名照跑先例。本讲客户侧**无新业务面**——erp_increment 的 API 在工作台侧，业务对象仍归客户；动手前复核 eval 双树与 service.py 指纹（C8）。CodexFDE `eval/cases.py:262` `delivery_evidence_and_review_controls` 是工作台面 eval（非客户件），其断言面即本讲 C 表主源。

### 1.4 宿主映射（ADR-0002）：上游双仓库 → 本仓库单仓库 + submodule

| 上游（CodexFDE 参考） | 本仓库 |
|---|---|
| `workbench/task_store.py`（三表 + 九态机 + 幂等提交 + 并发守卫） | `workbench/delivery/` 新子包 TaskStore（schema 三表 tasks/task_events/task_submissions 同形；独立运行库 `.runtime/course/L13-delivery/workbench.db`——**与 L01 信用账本不混库**） |
| `workbench/automation.py`（线程 + max_workers + pending 队列 + 有界重试 + wait/recover） | 同包 DeliveryAutomation（Java 线程同形；wait 软硬超时 + version 刷新 deadline 同语义） |
| `workbench/workflow.py` 四段链（prepare/start/execute/evaluate + run_task） | 同包 Workflow 四段（execute 阶段 verify 模式 = verification_only；evaluate 落报告 + SHA-256） |
| `run_suite("blocking", write_report=True)`（进程内直调） | **suite_runner 缝接本仓 EvalHarness**（L06 统一运行器；新高缝具名，D5） |
| `workbench/spec.py`（build_delivery_spec 六段模板 + 信号词路由 + normalize_*） | 同包 SpecFactory（模板逐字翻译；生成物可被 L03 SpecParser 解析；新高缝具名，D5） |
| `workbench/feedback.py`（observe_task_failure/add/review/summary） | 同包 Feedback（同库表；schema `workbench.failure-observation/v1`；签名去重） |
| `workbench/workbench_server.py`（accept_course_task + 四端点路由 + 错误映射 415/400/404/409） | 同包 HttpApi（`com.sun.net.httpserver`——JDK 内置，白名单零新依赖）+ **REGISTRY `delivery-serve`**（上游 `serve()` 常驻面） |
| `eval/task_api_contract.py` + `tests/test_course_task_http.py` | Java 合同测试（HttpClient 真实端口 + latch 异步证明）+ `workbench/evals/l13/` DeliveryChecks 登记项 |
| initiatives/backups/projects/web_execution/daily/desktop/graphs(HTTP) | **不建**（不在 L13 断言面，D1 清单如实记录） |

映射的**不变量**（上游教学点逐条承接）：202 接受 ≠ 交付完成；202 先于 Eval 完成（异步诚实，测试钉死）；九态合法迁移表外一律拒绝；completed 三守卫（合法边 + 具名 approve + Eval 绿）；同键异需求 409 请用新键（幂等不失效）；verify-only 网页面（代码执行走显式授权 CLI——ADR-0002 边界的 API 面表达）；自动化永不代人审；失败沉淀观察自动、接受具名；已审核反馈不可重复改变结论；API 运行不触客户库；报告路径 + SHA-256 双留痕；匿名/Agent 终审一律拒绝。

### 1.5 因果交接（本讲为下一讲准备什么）

L14「让交付状态在 Web 面板可见」（合同：页面数据来自 API 而非静态假数据、能下钻到任务事件）直接消费本讲的三个查询面：`/api/v1/delivery/capabilities`、`status_url`、`view_url`——面板不是新数据源，是 Task API 的投影。L15 反馈治理消费本讲的 feedback 沉淀面；L16 现场需求经 Task API 全链交付。本仓 REGISTRY 模式、EvalHarness、L12 graph-run 的「等待是正确结果」语义在本讲汇成一条可外提交的交付管道。

## 2. 本讲合同（C 编号清单）

write_scope 映射（合同四路径 → 本仓库）：`workbench/` → `src/main/java/workbench/delivery/`；`flowerp/` → 临时库运行面（客户 eval 子进程照跑，无源码改动）；`eval/`、`tests/` → `src/main/java/workbench/evals/l13/` + `src/test/java/`。

| C | 验收项（来源） | 类型 | 怎么验 |
|---|---|---|---|
| C1 | API 接受请求后返回 Task ID 而非伪称完成（acceptance[0]） | blocking | 202 契约（task_id 唯一 `TASK-[A-Z0-9]{10}`、accepted、execution_mode verify、status_url/view_url）+ **202 先于 Eval 完成**（latch 异步证明，对照上游 entered/release 形态）+ capabilities 面（execution_modes `["verify"]`、requires_idempotency_key true）+ 词面校验（「现场需求不能为空」/超长/「必须填写具名提交人」/「业务引用必须是最多 20 个非空编号…」/lesson 4–16 越界） |
| C2 | 幂等提交与原子性（同键重放/冲突；description 隐含） | blocking | 同键同需求重放 → 同 Task ID 且 list 不增；同键异需求（含 business_refs 变化）→ 409 TaskSubmissionConflict；跨实例并发同键 → 同 Task ID（两 store 线程并发改库）；提交键词面（「提交键不能为空且不能超过200字符」/「异步提交必须提供 Idempotency-Key」） |
| C3 | 任务事件可追溯到需求、业务对象和 Eval（acceptance[1]） | blocking | 事件链全序留痕（任务已接收 → 已从需求生成任务级结构化 Spec → 自动流水线开始推进（stages 四段 + human_review_is_automatic: false）→ 开始受控执行 → 受控执行阶段完成 → 阻断级 Eval 已通过，等待具名人工审核 → 老板终审）+ evidence 三向（requirement_id / business_refs / report_path + report_sha256）+ Spec 文件每任务独立且落盘可解析（L03 SpecParser） |
| C4 | 全绿后仍停在人工审核（acceptance[2]） | blocking | 绿链终态 `review` 且 reviewed_by null；匿名完成 → ValueError「完成交付必须由具名审核人明确 approve」；Eval 未绿批准 → 「阻断级 Eval 未通过，不能批准完成」；Agent 代审 → 「Agent 员工不能代替老板终审」；具名 approve → completed + reviewed_by/reviewed_at；reject → rework |
| C5 | 状态机与失败路径（上游 workflow/automation 全口径） | blocking | 九态合法迁移表（跳步 → 「非法任务状态迁移：x -> y」）+ 并发守卫（「任务状态已变化，拒绝并发迁移」）+ Eval 红 → rework + 自动反馈（pending_review / automation:rework / blocking_failures）+ 有界重试与耗尽保留（failure_preserved: true）+ 异常分类处置（executing→rework；queued/spec_ready→failed→dead_letter）+ recover 恢复面 + reopen 实例状态持久 |
| C6 | 反馈审核闭环（上游 delivery eval 断言面） | blocking | observe 自动沉淀（仅 rework/failed/dead_letter；签名去重）+ add_feedback → pending_review + review_feedback 具名 accept/reject（note 必填）+ 重复审核 → ValueError + summary 统计（pending_review/accepted 计数） |
| C7 | 绑定 eval 两件（合同 evalCases） | blocking | `delivery_evidence_and_review_controls` 断言面全数 Java 机检（DeliveryChecks 登记项逐断言对齐上游 cases.py:262-333 + check_required_workbench）；`purchase_requires_approval` 客户名原样子进程照跑（L12 先例，业务权威 ADR-0005） |
| C8 | 冻结与回归 | blocking | 客户 eval 双树 + `flowerp/service.py` 指纹复核（L05–L12 机制承袭）+ 本讲 Java 源件指纹初冻与收口复核 + `mvn test` 全量绿（含 golden 六重放 + L07–L12 合同） |
| C9 | 诚实性分层留痕 | observing | 202 不写成完成；review 不写成 completed；异步不得先跑完再返 202；合成 suite/fixture 只用于机检与断言面，不冒充真实 Eval；「API 不触客户库」以运行目录无 flowerp.db 断言钉住；上游 main 后期增量不采纳边界如实记录（D1）；教学验收人字串不冒充身份认证 |

登记项构成（`DeliveryChecks` 收口，`workbench/evals/l13/`）：默认门全 blocking——`l13_task_api_accept`（C1：202 契约 + 异步证明 + capabilities）、`l13_idempotency_and_conflict`（C2）、`l13_event_traceability`（C3）、`l13_stops_at_review`（C4：含三拒绝拍）、`l13_state_machine`（C5：跳步/并发/异常分类）、`l13_rework_and_feedback_observation`（C5+C6 失败沉淀）、`l13_feedback_review_loop`（C6）、`l13_required_workbench`（C7：对照上游 check_required_workbench——accept → wait → review 停住 → 同键重放 → 冲突 409 → reopen 持久 → 无 flowerp.db）、`purchase_requires_approval`（C7 客户名原样）、`l13_frozen_checks`（C8）。HTTP 端到端合同测试（真实端口 + HttpClient，对照 test_course_task_http 三测：202/查询/冲突/持久、跨实例原子、spec 独立）落 `src/test/java/`。

## 3. 实操流程 + Claude 简报

1. **前置（裁决后、动手前）**：讲义定稿入 master（§5 裁决记录补齐）。无对照基准（无 Python 先例 + 上游无讲义）；回归门 = 全量 `mvn test`。**规模预期如实**：上游断言面五件约 1600 行 Python → Java 估 2000+ 行（重走线最大单讲），实现期按「TaskStore → Workflow → Automation → Spec/Feedback → HttpApi → Checks」分段多 commit（L11 八 commit 先例），链证据取红绿首尾。
2. **候选分支 `lesson-13`**：【配合点·to-spec】`/mattpocock-skills:to-spec` 用户显式（L08–L12 先例；spec 落 `docs/specs/`；两新高缝具名 = suite_runner 缝接 EvalHarness + SpecFactory 生成缝）。commit 1 = 合同测试起始红（`delivery-serve` 未注册 / DeliveryChecks main 缺席 / HTTP 合同测试缺席三面）+ 红证据（capture + ImportEvidence 落账）。
3. **实现转绿**（用户显式 `/mattpocock-skills:implement` 后动笔）：`workbench/delivery/`（TaskStore 三表与九态机词面逐句对照 `task_store.py` → Workflow 四段对照 `workflow.py` → DeliveryAutomation 对照 `automation.py` → SpecFactory 对照 `spec.py` build_delivery_spec/normalize → Feedback 对照 `feedback.py` → HttpApi 路由与错误映射对照 `workbench_server.py` 断言面子集）；`workbench/evals/l13/` DeliveryChecks；REGISTRY `delivery-serve`。
4. **受控 API 实验**（C1/C4 端到端原件）：真实起 `delivery-serve`（随机端口）→ curl/脚本走四拍（提交 202 → 查询 review → 具名批准 completed → 同键重放/冲突 409）→ 请求响应原件与状态/事件快照落 `lesson-13-submission/13-api/`（永不移动）；教学 reviewer 字串如实标注。
5. **verify 集成**：`./bin/wb workbench-task-run CASE-WB-L13-001 --mode verify --eval-command "<DeliveryChecks 绝对路径命令，本地展开>" --execution-timeout 900 --actor Claude`（L08–L12 先例同形；actor 填实际执行者——L07 教训①）→ `workbench-status` 密封。
6. **收口**：`code-review` 双轴（master...lesson-13）→ 修复轮（若有）→ 用户按 §4 具名验收 → `git merge --no-ff lesson-13`（合并信息含验收人与结论）→ roadmap 阶段 5 行推进 + 讲义导航 + CLAUDE.md（架构行 delivery/ 子包与 evals l13、待建设清单改口）；收口后提请用户 push（HTTP 400 分段预案记忆在案）。

### Claude 简报（第 3 段粘贴用）

> 在 lesson-13 候选分支上：①以合同测试（delivery-serve 未注册 / DeliveryChecks main 缺席 / HTTP 合同测试缺席三面）采起始红，capture + ImportEvidence 落账；②实现 `workbench/delivery/`（TaskStore 三表 + 九态机 + 幂等提交 + 并发守卫——词面逐句对照上游 task_store.py；Workflow 四段链——绿报告只进 review；DeliveryAutomation——线程 + 有界重试 + wait/recover，自动化停在 review/rework 永不代人审；SpecFactory 六段模板 + 信号词路由；Feedback 观察自动接受具名；HttpApi 四端点 + 415/400/404/409 错误映射——com.sun.net.httpserver 零新依赖）+ REGISTRY delivery-serve + evals/l13 DeliveryChecks 十件默认门；③受控 API 实验：真实服务四拍原件落 lesson-13-submission/13-api/；④verify 集成 CASE-WB-L13-001（actor 实际执行者，eval-command 绝对路径）+ status 密封。vendors/ 只读；202 是接受不是完成；异步必须诚实（不得先跑完 Eval 再返 202）；自动化永不代人审；同键异需求 409 请用新键；失败沉淀不删不改；上游 main 后期增量（v0 冻结面/agent_runner/graphs HTTP 面）不在本讲断言面、不采纳；API 运行目录不得出现 flowerp.db。

## 4. 人审清单

- **看哪个 diff**：commit 1 = 三面起始红（无实现）；实现分段 commit（存储→工作流→自动化→Spec/Feedback→API→Checks）。TaskStore 状态机与幂等提交词面逐句对照上游 `task_store.py`（九态迁移表、fingerprint 构成、BEGIN IMMEDIATE 事务、version 乐观锁）；HttpApi 错误映射对照 `workbench_server.py`（415/400/404/409 词面）。API 实验原件：四拍请求响应 + 状态/事件快照——出现断言面之外的文件改动即红旗。
- **跑哪些门**：亲手 `mvn test`（全量含六重放 + L07–L12 合同 + 本讲 HTTP 端到端与 Checks）；亲手起 `delivery-serve` 走四拍（提交 202 → status review → 具名批准 completed → 同键重放同 ID / 异需求 409）；`DeliveryChecks` 默认门全绿 rc 0 + `--case purchase_requires_approval`（客户名原样绿）；`workbench-status --require-red-green-evidence`；核对指纹三件（客户 eval 双树、service.py、本讲 Java 源件）；核对 D1 不采纳清单（上游后期增量未被混入）。
- **什么算作弊**：先跑完 Eval 再返 202 的假异步；review 写成 completed 或「接近完成」；同键异需求被静默接受或旧任务被改写（幂等键失效——业务边界 2 同款）；匿名或 Agent 终审被放行；事件链事后手写补造（append-only 之外的 evidence 写入路径）；fixture 绿报告冒充真实 Eval（合成缝只用于机检）；API 直接连写客户库（运行目录出现 flowerp.db）；跳步迁移被放行；把上游 main 后期增量混进本讲冒充对照范围；失败反馈被删改或自动接受（观察自动、接受永远具名）。

## 5. 候选表（决策点，一次列全，等具名确认）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| D1 | **范围与对照源**（无上游讲义的首例，本讲特色裁决） | 范围 = 绑定 eval 断言面（cases.py:262-333 + task_api_contract.py + test_course_task_http.py）+ 合同验收三条；对照源 = pin main 五件中断言面子集（task_store/automation/workflow/spec/feedback + workbench_server 的 L13 路由段）；**不采纳清单如实入账**：workbench/ 内后期增量（frozen contract v0 spec_text 面、agent_runner 缝、graphs HTTP 面 = workflow_graph 治理层）、course-status 等其余命令、initiatives/backups/projects/web_execution/daily/desktop——均不在断言面，留后续讲次按检查点裁量（L12 §1.5 口径延续）；基线 tag 无代码区分度（进度标记）如实记录 | 按 888a0c7 提交切范围（混入 daily/desktop/web 等非断言面，范围失控，不取）；按 l13-start 基线切（API 面彼时尚不存在，L13 主角缺席，不取）；等上游讲义上架再开工（时间不可控且用户已裁自建） |
| D2 | 落点与命令形态 | `src/main/java/workbench/delivery/` 新子包（六件：TaskStore/Workflow/DeliveryAutomation/SpecFactory/Feedback/HttpApi）+ **REGISTRY `delivery-serve`**（常驻 HTTP 服务 = 上游 `serve()` 对应物；`--runtime-dir/--port/--host` 参数面）+ 独立运行库 `.runtime/course/L13-delivery/workbench.db`（三表同形；**与 L01 信用账本不混库**——L01 是工作台自身信用内核，本讲是交付任务面） | 复用 L01 账本扩表（信用内核混入交付面，铁律面风险，不取）；纯 CLI 无 HTTP（acceptance[0] 的 API 字面与上游 202 契约断裂，不取）；HTTP 只在测试内起不进 REGISTRY（上游有 serve() 常驻面，不如实，不取） |
| D3 | HTTP 承载与异步证明 | `com.sun.net.httpserver.HttpServer`（JDK 内置——白名单 Jackson+sqlite-jdbc 零新增）+ 路由四端点（capabilities / delivery/requests POST / tasks/{id} GET / delivery/views/{id} GET + tasks/{id}/review POST）+ 错误映射同上游（415 unsupported_media_type / 400 invalid_json·ValueError 类名 / 404 not_found / 409 conflict；body 上限 8192）+ **异步证明测试同形**（CountDownLatch 对应上游 entered/release：202 返回时 suite 未完成） | 引 HTTP 框架（Spring/Undertow——破坏依赖白名单，不取）；异步证明省略（假异步无护栏，不取）；202 之前先同步跑 Eval（违反 acceptance[0] 本义，不取） |
| D4 | 异步并发承载 | Java `Thread` + `max_workers=2` + pending 队列同形（词面「自动流水线等待可用 Worker」）+ SQLite 并发面：单库连接互斥（对应 BEGIN IMMEDIATE 串行写）+ version 乐观锁（transition `WHERE status` + rowcount 校验同语义）+ wait 软超时刷新（version 变化续期）与硬超时（execution_timeout+30）同形 + recover 重启恢复面 | ExecutorService 全托管（pool 语义与上游 daemon Thread + pending 手工队列形态偏离，且 recover/is_active 观察面难同形，不取）；全同步单线程（异步语义丢失，不取） |
| D5 | 两新高缝具名：suite_runner 缝 + Spec 生成缝 | ①suite 缝：上游 `run_suite("blocking", write_report=True)` → 本仓 `EvalHarness.run(entries, "blocking", names, reportPath)`（L06 统一运行器直接对接；报告落 `reports/{taskId}-harness-blocking.json` + SHA-256 同形；**新缝具名进 spec**）；②Spec 缝：SpecFactory 六段模板 + `_business_acceptance` 信号词路由逐字翻译（PURCHASE/库存/订单/渠道/回调五组 + 兜底组；REQ- 前缀正则与自动编号 REQ-AUTO-{date}-{hex6} 同形；BUSINESS_REF_PATTERN 提取去重保序），生成物经 L03 SpecParser 可解析（同仓闭环） | 报告 schema 自造（对照面断裂，不取）；spec 模板简写（信号词路由是「业务边界进验收用例」的机制本体，不取） |
| D6 | 反馈面承载 | 同库表 feedback（上游 DEFAULT_DB 独立但 eval 用例传任务库路径——断言面走同库）+ schema `workbench.failure-observation/v1` + 签名去重（sha256 前 20 位）+ 四函数对应物（observe_task_failure/add_feedback/review_feedback/feedback_summary）；词面承袭（「只有 rework、failed 或 dead_letter 任务可以沉淀失败反馈」等） | 独立 feedback.db（断言面传的是任务库路径，形态不符，不取）；反馈自动接受（「接受永远具名」铁律，不取） |
| D7 | 分支/证据/任务/账本命名 | 分支 `lesson-13`；证据账 `docs/replication/evidence/L13.md`；任务 `CASE-WB-L13-001` @ `.runtime/course/L01-workbench-java/`；采集根 `lesson-13-submission/`（含 `13-api/` 子根：四拍请求响应 + 状态事件快照原件，永不移动）；运行库 `.runtime/course/L13-delivery/`；讲义本文件名 `L13-任务API与异步交付.md`（L09–L12 命名惯例：上游 title 紧凑化） | 文件名直用上游 title「把执行链路封装成任务API」（可，备选）；任何 -java 后缀；复用 lesson-12-submission（跨讲混链） |
| D8 | CI 面：本讲不动 L08 工作流 | **保持 `.github/workflows/l08-eval.yml` 终态不动**（L09–L12 D8 直承）；本讲 CI 覆盖 = push lesson-13 触发现有 SalesChecks Run + `mvn -q process-test-classes` 编译面；DeliveryChecks 远程复验缺口如实声明（本地全量门承载）；`delivery-serve` 常驻服务不进 CI（CI 是一次性 Run 环境，常驻面无意义） | 本讲扩工作流（跨讲合同变更，不取）；CI 内起服务做端到端（Run 环境与常驻语义不匹配，不取） |
| D9 | 无对照基准的验收门替代 | ①合同测试三面起始红 ②HTTP 端到端（真实端口三测对照 test_course_task_http：202/冲突/持久 + 跨实例原子 + spec 独立）③DeliveryChecks 十件默认门 ④受控 API 实验四拍原件 ⑤指纹冻结对账三件 ⑥全量 `mvn test`。如实声明：无字节级对照基准 + 无上游讲义（L07 D8 / L08 D9 / L09 D9 / L10 D9 / L11 D9 / L12 D9 直承，另加「自建讲义、无讲义对照」首例声明；上游 L13 讲义日后上架则检查点对账补记） | 现在补建对照基准或等讲义（硬造即自造对照 / 时间不可控，均不取） |

> **裁决记录（2026-10-02）**：D1–D9 推荐方案**全部接受**。用户原话：「没问题，继续」（批量确认，逐字入账）。起草日现查：CodexFDE pin `406f7aa`（检查点 0003，fetch 后无新提交）；上游 L13 讲义未上架——用户具名裁决**检查点自建讲义**（本讲首例，会话开局 AskUserQuestion 选定「检查点自建 L13 讲义」）；绑定 eval 断言面三件在场（`eval/cases.py:262-333` / `eval/task_api_contract.py` / `tests/test_course_task_http.py`）；上游工程史：骨架四件 dad7f5f（2026-08-20）一次性落地、Task HTTP API 面 888a0c7（2026-09-06）净增量、基线 tag 无代码区分度（`l13-start..l14-start` 代码零 diff，同日进度标记）——范围以断言面为界（D1）；客户真理无新面（L12 现查结论有效：`flowerp/service.py:340/:386` 采购审批面 + `eval/cases.py:98` purchase_requires_approval，动手前复核指纹 C8）。
