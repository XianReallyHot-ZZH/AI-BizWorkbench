# L13 Spec：任务 API 与异步交付（把执行链路封装成可提交、可查询的资源）

> `/mattpocock-skills:to-spec` 产物（2026-10-02，用户显式调用；讲义 [L13-任务API与异步交付.md](../lessons/L13-任务API与异步交付.md) 定稿 v1，D1–D9 已裁决——本 spec 是其合同段/裁决的合成，冲突时以讲义与冻结合同 fixture 为准）。
> 接缝已具名确认（讲义 D3/D5）：**仅有的两个新高缝** = ①suite_runner 注入缝（DeliveryAutomation/Workflow 构造注入——真实面接 EvalHarness（L06 统一运行器），机检面注入合成 suite，上游 `suite_runner` 参数同形）②SpecFactory 生成缝（输入 = request/requirement_id/business_refs，输出 = 可被 L03 SpecParser 解析的六段 spec 文本）；**HTTP API 面为最高公开缝**（`delivery-serve` REGISTRY 命令 + 测试内同款 handler 起真实端口 + HttpClient 驱动——202 契约/四端点/错误映射即其输入输出契约，讲义 D2/D3 裁决的产品面）；REGISTRY 注册缝 / testsupport 起始红 / 探针 python 子进程 / EvalHarness 挂载 / 冻结指纹复核 / L03 SpecParser 解析面六缝全复用。
> 发布目标：本仓库未配置 issue tracker，按讲义导航先例口径落 `docs/specs/`，本文件即承载 `ready-for-agent` 语义。措辞继承根目录 CONTEXT.md 词汇表（复刻/上游/候选分支/起始红/具名验收/证据/合同 fixture）。

## Problem Statement

L12 之后，我的工作台有了完整的能力家族（账本/Spec/受控执行/Eval/护栏/CI/修复链/Loop/调度/Graph），但它们都是**一串要我亲手敲的 CLI 命令**：一个补货需求想走完「生成 Spec → 执行 → Eval → 人审」，得由我在会话里按序驱动，每一步的衔接靠流程纪律而不是资源身份。具体缺口：**提交无入口**——需求没有「受理回执」，没有 Task ID，外部（未来的 Web 面板、现场需求、反馈系统）无法引用一个正在进行的交付；**状态不可查询**——「现在到哪一步、绿了没有、等谁审」没有统一查询面；**异步不诚实**——同步命令天然伪装成「跑完才算受理」，而正确的语义是「先受理（202 + Task ID），后台推进，进度可查」；**事件不成链**——需求、业务对象、Eval 报告之间的追溯靠证据账手工编排，没有每任务自带的事件流；**停点无保证**——「全绿后仍停在人工审核」目前是铁律纪律，没有状态机硬约束（跳步、匿名完成、Agent 代审都该被代码拒绝而不是被流程拒绝）。上游参考实现给出了完整形态（TaskStore 九态机 + DeliveryAutomation 异步流水线 + workflow 四段链 + HTTP API + 失败反馈沉淀），但**上游 L13 讲义未上架**，且这些件对本仓 Java 线全部为新面（本仓任务是 L01 证据链账本，语义不同源）。

## Solution

工作台新增**交付子包**（`workbench/delivery/` 六件）与**常驻 HTTP 服务命令**（REGISTRY `delivery-serve`）：需求经 `POST /api/v1/delivery/requests` 提交（具名提交人 + Idempotency-Key），受理即回 **202 + Task ID**（异步——必须先于 Eval 完成返回，测试用 latch 钉死），后台线程沿九态状态机推进（queued → spec_ready → executing → evaluating → **review**/rework），每步迁移落 task_events 事件链（evidence 三向：requirement_id / business_refs / report_path+SHA-256）。Spec 由 SpecFactory 自动生成（六段模板 + 信号词路由——PURCHASE/补货触发「未经批准阻断 + 幂等键只生效一次」两条业务边界用例），生成物可被 L03 解析器解析、每任务独立。Blocking Eval 经 suite_runner 缝接本仓 EvalHarness，报告落盘 + SHA-256。**绿报告只进 review，永不自动 completed**（transition 三守卫：合法边 + 具名 approve + Eval 绿；匿名完成、Agent 代审、未绿批准一律 ValueError）。失败自动进 rework + 有界重试（max_attempts，耗尽保留 failure_preserved），终态失败自动沉淀为**待审反馈**（观察自动、接受永远具名——pending_review / source automation:rework / blocking_failures 列表），反馈具名审核闭环（accept/reject + 不可重复改变结论 + summary 统计）。提交幂等与 FlowERP 入库幂等键同一哲学：同键同需求重放返回同 Task ID，同键异需求 409「请使用新键」。网页 API verify-only（「网页API只允许verify；代码执行须使用明确授权的课程CLI」——ADR-0002 边界的 API 面表达）。API 运行不触客户库（运行目录无 flowerp.db，业务权威仍经客户 eval 子进程，ADR-0005）。配套 `workbench/evals/l13/` DeliveryChecks 十件默认门（对照上游绑定 eval `delivery_evidence_and_review_controls` 断言面逐断言机检 + `purchase_requires_approval` 客户名原样照跑 + 冻结指纹复核）。范围以绑定 eval 断言面为界（无上游讲义首例——用户裁决检查点自建讲义；上游 workbench 后期增量与治理层不采纳，Out of Scope 清单）。

## User Stories

1. 作为需求提交人，我想要 POST 提交补货需求后立即收到 202 与唯一 Task ID（`TASK-[A-Z0-9]{10}`）而非等待完成或伪称完成，以便受理与交付分离、外部可引用（合同 acceptance[0]）。
2. 作为需求提交人，我想要 202 返回时 Eval 尚未跑完（异步诚实，测试以 latch 钉死），以便「受理」不是伪装成同步的假异步。
3. 作为需求提交人，我想要用同一 Idempotency-Key 重放同一需求时拿回同一 Task ID 且任务列表不增，以便网络重试不会造成重复交付。
4. 作为需求提交人，我想要同一 Idempotency-Key 提交不同需求（含 business_refs 变化）被 409 拒绝（「提交键已用于不同需求；请使用新键」），以便幂等键永不失效（业务边界 2 的工作台面）。
5. 作为需求提交人，我想要提交 execution_mode 之外的值被 400 拒绝（「网页API只允许verify；代码执行须使用明确授权的课程CLI」），以便代码执行永远走显式授权面而不是网页放行。
6. 作为需求提交人，我想要非法 business_refs（非数组/超 20 个/超长/空串）与越界 lesson、空需求、匿名提交人被 400 拒绝且词面明确，以便输入错误当场可见。
7. 作为需求提交人，我想要 GET status_url 查询任务当前状态与 reviewed_by，以便任何时候都能回答「到哪一步了、等谁审」。
8. 作为需求提交人，我想要 GET /api/v1/delivery/capabilities 查询执行能力（execution_modes `["verify"]`、requires_idempotency_key true、async_submission true），以便客户端按合同编程。
9. 作为具名验收人，我想要全绿任务停在 review 且 reviewed_by 为 null（合同 acceptance[2]），以便自动化永不代替我接受（铁律 2 的代码面）。
10. 作为具名验收人，我想要匿名完成被拒绝（「完成交付必须由具名审核人明确 approve」），以便「具名」不是可选装饰。
11. 作为具名验收人，我想要 Agent 身份代老板终审被拒绝（「Agent 员工不能代替老板终审」），以便人审永远是人。
12. 作为具名验收人，我想要阻断级 Eval 未通过时批准被拒绝（「阻断级 Eval 未通过，不能批准完成」），以便绿灯是完成的前置硬条件。
13. 作为具名验收人，我想要具名 approve 后任务 completed 且 reviewed_by/reviewed_at/review_note 落账（「老板终审：approve；note」），以便每次接受可追溯到我。
14. 作为具名验收人，我想要 reject 后任务回 rework 留证据，以便打回是可继续的状态而不是终点。
15. 作为具名验收人，我想要任务事件链全序留痕（任务已接收 → 已从需求生成任务级结构化 Spec → 自动流水线开始推进（stages 四段 + human_review_is_automatic false）→ 开始受控执行 → 受控执行阶段完成 → 阻断级 Eval 已通过，等待具名人工审核 → 老板终审），以便交付历史 append-only 可审计（合同 acceptance[1]）。
16. 作为具名验收人，我想要事件 evidence 三向可追溯（requirement_id / business_refs / report_path + report_sha256），以便需求、业务对象与 Eval 报告互相可查。
17. 作为具名验收人，我想要 Spec 自动生成（六段模板：来源/目标/非目标/约束/验收用例/完成定义）、每任务独立文件、可被 L03 解析器解析，以便「生成 Spec」是机器可验的产物不是一句声称。
18. 作为具名验收人，我想要补货/采购信号词自动触发业务边界验收用例（「补货建议尚未由具名人员批准…请求被阻断且库存不变」「已批准采购…相同入库幂等键重放…只增加一次」），以便 FlowERP 边界写进每份交付合同。
19. 作为具名验收人，我想要状态机九态合法迁移表外一律拒绝（「非法任务状态迁移：x -> y」——含直跳 completed），以便跳过 Spec/执行/Eval/审核的路径在代码层不存在。
20. 作为具名验收人，我想要并发迁移被拒绝（乐观锁 version + 原状态条件更新，「任务状态已变化，拒绝并发迁移」），以便两个 worker 不能各推一步。
21. 作为具名验收人，我想要 Blocking Eval 失败自动进 rework 并沉淀待审反馈（pending_review、source automation:rework、blocking_failures 列表），以便失败自动观察、接受永远具名（业务边界 5）。
22. 作为具名验收人，我想要有界重试（max_attempts）与耗尽后保留失败（failure_preserved true，不宣称成功），以便重试不无限、失败不蒸发。
23. 作为具名验收人，我想要流水线异常分类处置（执行中 → rework 保留现场；队列/Spec 期 → failed；不可安全重试 → dead_letter 转人工）而非静默吞，以便异常永远留下状态与证据。
24. 作为具名验收人，我想要服务重启后任务状态仍在（SQLite 持久）且 recover 能恢复安全的自动工作，以便崩溃不丢交付现场。
25. 作为具名验收人，我想要反馈审核闭环：add → pending_review，具名 accept/reject（note 必填），重复审核被拒（已审核不可改结论），summary 统计准确，以便反馈治理与任务交付同权可审计。
26. 作为具名验收人，我想要 `purchase_requires_approval` 以客户原名子进程照跑（L12 先例），以便本仓绿灯不冒充客户业务结论（ADR-0005）。
27. 作为具名验收人，我想要 API 运行目录断言无 flowerp.db，以便工作台 API 不直接触客户库——业务权威永远经客户 eval。
28. 作为执行者（Claude），我想要 suite_runner 注入缝（真实面 = EvalHarness blocking 套件 + 报告落盘；机检面 = 合成 suite），以便控制模式机检与真实检查走同一条缝。
29. 作为执行者，我想要 Eval 报告落 `reports/{taskId}-harness-blocking.json` 且事件里带 SHA-256，以便报告不可抵赖、事件链可校验。
30. 作为执行者，我想要 `delivery-serve` 经 REGISTRY 注册缝接入（`--runtime-dir/--port/--host`），以便常驻服务与既有命令同一注册缝、同一异常边界。
31. 作为具名验收人，我想要错误映射与上游同形（415 unsupported_media_type / 400 invalid_json·ValueError 类名 / 404 not_found / 409 conflict / body 上限），以便 API 合同逐字对照上游而不是自造。
32. 作为具名验收人，我想要本讲收口含冻结指纹三件复核（客户 eval 双树 + flowerp/service.py + 本讲 Java 源件）与全量 `mvn test` 绿，以便旧能力零回归、检查面未被偷偷改动。

## Implementation Decisions

- **范围与对照源**（讲义 D1，无上游讲义首例）：范围 = 绑定 eval 断言面（`eval/cases.py:262-333` delivery_evidence_and_review_controls + `eval/task_api_contract.py` + `tests/test_course_task_http.py`）+ 合同验收三条；对照源 = pin main 五件中断言面子集（task_store / automation / workflow / spec / feedback + workbench_server 的 L13 路由段）；词面逐句对齐（错误词面、事件 detail、状态名全部继承上游中文原文）。
- **落点与命令形态**（讲义 D2）：新子包 `workbench/delivery/` 六件（TaskStore / Workflow / DeliveryAutomation / SpecFactory / Feedback / HttpApi）+ **REGISTRY `delivery-serve`**（上游 `serve()` 对应物；常驻 HTTP）；独立运行库 `.runtime/course/L13-delivery/workbench.db`（三表 tasks/task_events/task_submissions 同形 schema）——**与 L01 信用账本不混库**（L01 是工作台自身信用内核，本讲是交付任务面）。
- **TaskStore 状态机**：九态合法迁移表（queued→spec_ready/failed/dead_letter；spec_ready→executing/…；executing→evaluating/rework/…；evaluating→review/rework/…；review→completed/rework/…；rework→executing/…；completed/dead_letter 终态；failed→dead_letter）；transition 三守卫（合法边 + completed 时具名 approve 且报告 decision pass/blocking_failed 0 + 原状态条件更新 rowcount 校验）；version 乐观锁递增（wait 续期依据）；submission_key 幂等（fingerprint = sha256(request/requirement/refs/actor/mode/scope/timeout/automation) 同键同指纹返回既有任务、异指纹 TaskSubmissionConflict）；create 全词面校验（「任务需求不能为空」「业务对象引用不能重复」「automation_mode 必须是 manual 或 automatic」「execution_mode 必须是 verify 或 codex」「Codex 代码执行至少需要一个明确写入范围」「execution_timeout_seconds 必须在 30..3600」「任务编号格式无效」「提交键不能为空且不能超过200字符」）。
- **DeliveryAutomation**：Java Thread + max_workers=2 + pending 队列（「自动流水线等待可用 Worker」）；submit（Task ID 生成 + SpecFactory 写 Spec + 首事件「已从需求生成任务级结构化 Spec」+ 失败转 failed）；有界重试（attempts 按历史事件计数 + max_attempts=3，重试事件与耗尽事件 failure_preserved）；异常分类处置（executing/evaluating/review → rework；queued/spec_ready → failed；终态 → 仅追加事件）；finally 失败沉淀 observe_task_failure（「失败已沉淀为待具名审核的反馈候选」automatic_acceptance false）；wait 软超时（version 变化续期）+ 硬超时（execution_timeout+30）+ worker 活跃判定；recover 重启恢复。**docstring 语义入 javadoc**：自动化停在 review/rework，人审是分离的具名动作、worker 永不执行。
- **Workflow 四段链**：prepare（queued→spec_ready，Spec md 校验）/ start（→executing，allowed_actions 四件 + forbidden 三件 write_runtime_database/approve_business_document/skip_eval）/ execute（verify 模式 = verification_only「未配置代码写入执行器；本轮只验证当前候选，不产生业务数据副作用」）/ evaluate（→evaluating→绿 review「阻断级 Eval 已通过，等待具名人工审核」/红 rework「阻断项未通过，退回最小修复」，报告落盘 + SHA-256 入 evidence）；run_task 编排 + 异常处理（ControlledExecutionError 且 executing → rework；非终态 → failed「工作流异常，保留原因」）。
- **suite_runner 缝**（新缝①，讲义 D5）：上游 `run_suite("blocking", write_report=True)` → 本仓 `EvalHarness.run(entries, "blocking", names, reportPath)`；报告路径 `reports/{taskId}-harness-blocking.json`；机检经构造注入合成 suite（上游 eval 用例同款手法）。
- **SpecFactory**（新缝②，讲义 D5）：`build_delivery_spec` 六段模板逐字翻译（来源/目标/非目标/约束/验收用例/完成定义 + 五条平台验收用例：Task ID 唯一 / 状态迁移序 / 绿进 review 不进 completed / 红进 rework / 审核分叉）+ `_business_acceptance` 信号词路由（库存/采购补货/渠道/订单/回调五组 + 兜底组，保序去重）；normalize_requirement_id（REQ- 前缀正则或自动 REQ-AUTO-{date}-{hex6}）+ normalize_business_refs（格式校验 + 从 request 提取 + 去重保序）；生成物经 L03 SpecParser 可解析。
- **Feedback**：同库表；schema `workbench.failure-observation/v1`；签名去重（status/blocking_failures/error 摘要 sha256 前 20 位）；四函数 observe_task_failure（仅 rework/failed/dead_letter，「只有 rework、failed 或 dead_letter 任务可以沉淀失败反馈」）/ add_feedback（pending_review）/ review_feedback（具名 + note 必填 + 已审核不可改结论）/ feedback_summary（计数）。
- **HttpApi**（讲义 D3）：`com.sun.net.httpserver.HttpServer`（JDK 内置——依赖白名单零新增）；端点：GET /api/v1/delivery/capabilities、POST /api/v1/delivery/requests（202 契约 {task_id, accepted, execution_mode, status_url, view_url}）、GET /api/v1/tasks/{id}、GET /api/v1/delivery/views/{id}、POST /api/v1/tasks/{id}/review；错误映射同上游（415「只接受 application/json」/ 400 invalid_json 与 ValueError 类名 / 404 not_found / 409 conflict「提交键已用于不同需求；请使用新键」；body 上限 8192）；Idempotency-Key 头承载提交键（「异步提交必须提供 Idempotency-Key」）；accept_course_task（key 必填 + 幂等重放不启新自动化 + queued 才 start）。
- **DeliveryChecks**（讲义 §2 登记项构成）：`workbench/evals/l13/` 独立 main 挂 EvalHarness，十件默认门全 blocking（l13_task_api_accept / l13_idempotency_and_conflict / l13_event_traceability / l13_stops_at_review / l13_state_machine / l13_rework_and_feedback_observation / l13_feedback_review_loop / l13_required_workbench / purchase_requires_approval 客户名原样 / l13_frozen_checks）。
- **命名**（讲义 D7）：分支 `lesson-13`；证据账 `docs/replication/evidence/L13.md`；任务 `CASE-WB-L13-001` @ `.runtime/course/L01-workbench-java/`；采集根 `lesson-13-submission/`（含 `13-api/` 子根）；运行库 `.runtime/course/L13-delivery/`。
- **CI**（讲义 D8）：不动 L08 工作流；delivery-serve 常驻服务不进 CI；DeliveryChecks 远程复验缺口如实声明。

## Testing Decisions

- **好测试只测外部行为**：经公开接缝断言可观察输出——HTTP 状态码与 JSON 体、任务状态与 reviewed_by、事件链内容与顺序、落盘 Spec 与报告（+SHA-256）、反馈记录与 summary 计数；不直调私有方法；预期值独立计算不抄返回值。
- **起始红三面**：`delivery-serve` 未注册（rc 2 invalid choice）/ DeliveryChecks main 缺席 / HTTP 合同测试缺席，经 testsupport/Cli 真实子进程断言（Java 编译不绑实现类——L06-java 教训承袭）。
- **HTTP 端到端**：测试内起真实端口（同款 handler），HttpClient 驱动，对照上游 `test_course_task_http.py` 三测——①202/查询/冲突/持久（latch 异步证明：202 返回时合成 suite 未放行；同键重放同 ID；异需求/异 refs 409；codex 400；非法 refs 400；重开实例状态仍在；reviewed_by null；404 面）②跨实例并发提交原子（两 store 线程同键并发 → 同 Task ID；异需求 Conflict）③每任务 spec 独立落盘。
- **DeliveryChecks 机检**：合成 suite 经 suite_runner 缝注入（断言面与上游 eval 用例逐条对应）；`purchase_requires_approval` 子进程 python 驱动客户真身（L09–L12 探针族先例）。
- **先例**：L09–L12 Checks+合同测试族形（独立 main + `--case` + 默认门）、L07 起 REGISTRY 命令测试、EvalHarness 挂载、冻结指纹复核；golden 六重放不涉本讲（无 Python 先例——验收门替代 = 三面起始红 + HTTP 端到端 + 十件默认门 + API 实验原件 + 指纹三件 + 全量 mvn test，讲义 D9）。
- **受控 API 实验**：真实 delivery-serve 起服走四拍（提交 202 → status review → 具名批准 completed → 重放/冲突），请求响应与状态事件快照原件落 `lesson-13-submission/13-api/`（永不移动）；教学 reviewer 字串如实标注。

## Out of Scope

- 上游 workbench/ 后期增量（不在断言面，D1 不采纳清单）：frozen contract v0 spec_text 冻结面、agent_runner 缝、graphs HTTP 面（= `workbench/workflow_graph.py` 治理层——允许边校验/expected_version/候选绑定，留后续讲次按检查点裁量）、course-status 等其余命令、maintenance gate / runtime lease / process guard。
- initiatives / backups / projects / web_execution / daily delivery / desktop / workbench_web 前端（上游 888a0c7 大提交的非断言面）。
- L14 Web 面板（消费本讲 capabilities/status_url/view_url 的投影面）与 L15 反馈治理讲。
- 代码执行模式接线（codex/claude 无头执行器——本讲 verify-only，ADR-0002 的执行器合同不在本讲展开）。
- CI 工作流扩展（远程复验缺口如实声明——D8）。
- 客户实现任何改动（铁律 1）；客户采购面行为修复（L12 R1–R6 对照账已处置口径延续）。
- 把 verify-only 扩成网页代码执行（上游明示代码执行走显式授权 CLI——放行即越权）。

## Further Notes

- 讲前 fetch 现查：pin `406f7aa`（检查点 0003）之后无新提交；上游 L13 讲义未上架——用户具名裁决**检查点自建讲义**（首例），上游日后上架则检查点对账补记（D9）。
- 上游工程史（范围证据）：骨架四件 dad7f5f（2026-08-20）一次性落地、Task HTTP API 面 888a0c7（2026-09-06）净增量、基线 tag 无代码区分度（`l13-start..l14-start` 代码零 diff——同日进度标记）。
- 客户真理无新面：L12 现查结论有效（`flowerp/service.py:340/:386` 采购审批面 + `eval/cases.py:98` purchase_requires_approval），动手前复核指纹（C8/l13_frozen_checks）。
- 规模预期：上游断言面约 1600 行 Python → Java 估 2000+ 行（重走线最大单讲）；实现按「TaskStore → Workflow → Automation → Spec/Feedback → HttpApi → Checks」分段多 commit（L11 先例），链证据取红绿首尾。
- 验收顺序：起始红三面 → 实现转绿 → 受控 API 实验（四拍原件）→ verify 集成（CASE-WB-L13-001，actor 填实际执行者，eval-command 绝对路径）→ code-review 双轴 → 用户按讲义 §4 具名验收 → merge --no-ff → 收口提请 push。
