# L14 Spec：交付状态 Web 面板（让交付状态可见、可下钻、可对账）

> `/mattpocock-skills:to-spec` 产物（2026-10-02，用户显式调用；讲义 [L14-交付状态Web面板.md](../lessons/L14-交付状态Web面板.md) 定稿 v1，D1–D10 已裁决——本 spec 是其合同段/裁决的合成，冲突时以讲义与冻结合同 fixture 为准）。
> 接缝已具名（上轮列名、用户以调用 to-spec 作答，批量确认口径；讲义 D3/D4/D5/D6 承载）：**零新高架构缝**。①最高公开缝 = `delivery-serve` HTTP 面（L13 已建，本讲扩展——views 列表/详情/静态 serve 即其输入输出契约，测试内同款 handler 起真实端口 + HttpClient 驱动）；②投影纯函数面 = DeliveryView.build(task, now)（对照上游 `build_delivery_view(task, now=...)` 直测同形——**now 时钟注入是上游自带参数，非新架构缝**；unknown/stale 等合成态经它进，不绕状态机造非法库态）；③面板静态件 = 文本直读缝（仓库测试口径既有：纯文本类测试直接读文件——上游 `test_workbench_web_dashboard.py` 同形）；④受控客户实例 = 子进程 python 缝（L07/L09–L13 探针族先例——断言与预期留 Java）。复用缝：REGISTRY 注册缝 / testsupport 起始红 / EvalHarness 与 suite_runner 缝（L13 复验面）/ 冻结指纹复核 / L03 SpecParser（不涉）。
> 发布目标：本仓库未配置 issue tracker，按讲义导航先例口径落 `docs/specs/`，本文件即承载 `ready-for-agent` 语义。措辞继承根目录 CONTEXT.md 词汇表（复刻/上游/候选分支/起始红/具名验收/证据/合同 fixture）。

## Problem Statement

L13 之后，我能通过 API 提交补货需求并拿到 Task ID，但「看见」仍然只属于会话里的我：受理响应里承诺了 `view_url`，点开却只是占位式简化投影（schema + 任务字段平铺）——没有状态归属人、没有下一步动作、没有新鲜度、没有完整性判断；没有列表查询，「现在有几个任务在跑、几个等人审」要靠 GET 原始任务自己算；没有面板——业务用户（老板/验收人/同事）不看终端，交付状态对他们不可见；ERP 侧客户虽有操作页（flowERP web/），但「页面看到的、API 返回的、库里存的、ERP 权威状态」四者是否一致从未被对账过。上游把本讲的坑点名成一句（`lesson_constructibility.py:31`）：**「投影视图写死旧状态」**——投影若缓存旧快照、页面若写死演示数据，看板就成了谎言放大器。合同验收三条（页面数据来自 API 而非静态假数据 / DOM、API、SQLite 与 ERP 状态可对账 / 页面不包含凭据）全部指向同一个不可妥协点：**看见的必须是真的**。

## Solution

三个增量面（讲义 §1.1，全部消费 L13 已建查询面——面板不是新数据源，是 Task API 的投影）：①**投影视图完整化**——`workbench.delivery-view/v1` 从占位升级为完整合同：状态视图（九态归属表逐字翻译——每态归属人 id/标签/下一步动作；completed 归属具名审核人；**未知状态显式不可信**「状态不在交付合同中，禁止显示为成功」）、新鲜度（stale-sensitive 集 + 300s 阈值；**review 是深思熟虑的人工等待态，不因等待久而标 stale**）、Eval 摘要（decision/blocking 计数/报告路径+SHA-256/cases）、审核块、反馈计数块、事件全序投影（每事件带状态视图——下钻面）、允许动作、**完整性问题清单五类**（unknown_status / stale_active_task / completed_without_named_approval / review_without_green_blocking_eval / out_of_scope_writes）与 truthful 聚合、links（self/task/web）；列表查询带八计数汇总（total/active/review/completed/rework/failed/unknown/pending_feedback〔九计数 - verified_evolutions 随 evolution 块 D4 裁掉——复查轮 T-2 勘误〕——unknown 单列是「未知不可信」的看板面表达）与 include_detail 轻投影压缩（results/spec_text/events 摘要化，cases/report_path/SHA-256 保留）；**每次请求实时构建，不缓存旧状态**。②**面板落地**——本仓新顶层 `workbench_web/` 静态三件（index.html + app.js + styles.css，零依赖零 CDN），单页四区（身份/合同/任务列表/任务详情），取数面六面且仅六面（capabilities / requests 提交 / views 列表 / view 详情 / verify / review——五个路径前缀，闭集机检钉死〔复查轮 T-6 勘误：原清单漏列 requests 提交面〕），加载/空/失败三态，无凭据无 JSON.stringify 整个 detail；由 `delivery-serve` 直接 serve（WEB_ROOT + 路径穿越守卫 + content-type 后缀表 + `Cache-Control: no-cache`；**无新 REGISTRY 命令**——上游同一 workbench_server 承载）。③**ERP 操作页跑通与四方对账**——受控起客户 server（子进程 python，客户 `App(临时库)` + `ThreadingHTTPServer` 随机端口，客户 test_http_api 同形起法），客户 web/ 由客户 server 自 serve（**零源码改动，铁律 1**），经客户 API 种子业务后做四向对账：客户页静态断言（AGENTS.md 约束：业务事实必须来自 /api/v1、esc() 转义、无凭据、无 CDN）↔ 客户 API 快照 ↔ 客户 SQLite 快照 ↔ 本仓 view 投影（business_refs ↔ ERP 对象关联）；客户自测 `test_http_api.py` 照跑。配套 `workbench/evals/l14/` 统一入口八件默认门 + 仓面密钥扫描（`no_committed_secrets` Java 对应物——markers 三件 + 忽略集 + 后缀名单，补 `.java/.js/target` 形态差异落账）+ L13 两件绑定 eval 断言面经 l13 DeliveryChecks 默认门照跑复验。范围以绑定 eval 断言面 + 合同验收三条为界（自建讲义第二例；上游未建面不采纳清单落账，Out of Scope）。

## User Stories

1. 作为业务用户（不看终端的老板/同事），我想打开浏览器就看到交付任务列表与汇总计数（进行中/等人审/已完成/失败/未知），以便不用会话也能知道「现在有几个人审在等我」。
2. 作为业务用户，我想在每个任务卡上看到状态归属人（如 human:reviewer、具名终审人）与下一步动作（如「具名核对 Spec、Diff 与 Eval 后接受或打回」），以便知道该找谁、等什么。
3. 作为业务用户，我想点开任务下钻到事件时间线（全序投影、每事件带状态视图），以便看清交付走到哪一步、每步谁干的（合同描述「能下钻到任务事件」）。
4. 作为业务用户，我想在详情里看到 Eval 摘要（decision、blocking 计数、报告路径与 SHA-256、cases 列表），以便绿灯有据可查而不是一句「过了」。
5. 作为业务用户，我想看到完整性块如实标注问题（integrity issues + truthful 聚合），以便页面永远不替系统说谎。
6. 作为业务用户，我想看到加载中/空列表/加载失败三种诚实状态而不是白屏或假数据，以便页面状态本身可信。
7. 作为业务用户，我想在无外网环境（离线、无 CDN）打开面板照常工作，以便训练营/容器冷启动不受影响。
8. 作为需求提交人，我想在面板上填需求点「提交并复验」走 202 受理拿到 Task ID，以便不写 curl 也能提交。
9. 作为具名验收人，我想在面板上对 review 态任务点「批准完成」（具名 reviewer + decision + note），以便批准是我亲手做的具名动作且 reviewed_by 落账。
10. 作为具名验收人，我想看到 review 任务的新鲜度不因我审得久而标 stale，以便深思熟虑的人工等待不被页面渲染成异常（上游注释原话：review is a deliberate human wait state）。
11. 作为具名验收人，我想看到未知状态的任务显示「未知交付状态」「状态不在交付合同中，禁止显示为成功」且 requires_human，以便异常状态永远不可能被显示成成功。
12. 作为具名验收人，我想看到 completed 但无具名批准的任务被标 `completed_without_named_approval`，以便「完成」两个字永远有具名人背书。
13. 作为具名验收人，我想看到 review/completed 但 Eval 非绿的任务被标 `review_without_green_blocking_eval`，以便绿灯是审核的硬前置。
14. 作为具名验收人，我想看到有越写集写入的任务被标 `out_of_scope_writes`，以便写集纪律在投影面可见。
15. 作为安全审计者，我想让整个仓库被密钥扫描覆盖（markers 三件语义 = 上游 cases.py:221 逐字，源件内运行时拼接构造防自匹配；忽略 `.git/.tmp/.runtime/.cache/target` 与 diff-phase 采集件；后缀含 `.java/.js`），以便「页面不包含凭据」是机检结论不是口头承诺（合同 acceptance[2]）。
16. 作为安全审计者，我想让面板静态件断言无 apiKey/secret 字样、无外部 CDN 引用，以便前端本身不带凭据也不外联。
17. 作为安全审计者，我想让静态 serve 带路径穿越守卫（resolve 后不在 WEB_ROOT 内一律 404），以便 `../` 类路径读不到面板目录之外的仓库文件。
18. 作为 API 消费者，我想 `GET /api/v1/delivery/views?limit=20`（默认 20，上游同形）拿到列表投影与八计数汇总（verified_evolutions 随 evolution 块裁掉——复查轮 T-2 勘误），以便一次请求画出看板。
19. 作为 API 消费者，我想 `GET /api/v1/delivery/views/{id}` 拿到 schema `workbench.delivery-view/v1` 的完整投影，以便 status_url/view_url 的承诺兑现（L13 因果交接）。
20. 作为 API 消费者，我想 verify/review 的 POST 响应也是完整投影（随 view() 同步升级），以便动作完成后立即拿到可渲染的权威状态。
21. 作为 API 消费者，我想列表投影走 include_detail 压缩（results/spec_text/events 摘要化，cases/report_path/SHA-256 保留），以便列表轻、详情重，带宽与信息密度各得其所。
22. 作为 ERP 用户，我想在受控实例上通过客户操作页（进货/销售/库存/财务入口）操作真实业务数据，以便「交付 ERP 操作页」不是截图而是跑得起来的页面（erp_increment）。
23. 作为 ERP 用户，我想客户页的业务事实全部来自客户 `/api/v1`（AGENTS.md 约束静态断言钉住），以便客户页自身满足「数据来自 API 而非静态假数据」。
24. 作为审计者，我想有四向对账证据（客户页静态断言 ↔ 客户 API 快照 ↔ 客户 SQLite 快照 ↔ 本仓 view 投影），以便「DOM、API、SQLite 与 ERP 状态可对账」有原件可查（合同 acceptance[1]）。
25. 作为审计者，我想对账包含 business_refs ↔ ERP 业务对象的关联断言，以便工作台任务与其业务对象互相可查。
26. 作为审计者，我想投影视图每次请求实时构建（无缓存快照、无写死旧状态），以便「投影视图写死旧状态」这个上游点名的坑在本仓代码层不存在。
27. 作为审计者，我想面板取数面被钉死为六面 API 且仅六面（静态断言 + 闭集负面断言——新数据源即红），以便页面加任何一个新数据源都必须过合同（合同 acceptance[0]）。
28. 作为执行者（Claude），我想 DeliveryView.build(task, now) 是纯函数且 now 可注入，以便时间语义（300s 阈值、review 不 stale）可确定性机检（对照上游 now 参数同形）。
29. 作为执行者，我想 L14Checks 八件默认门 + l13 DeliveryChecks 照跑共同承载三件绑定 eval 断言面，以便合同 evalCases 全数有机检对应物且 L13 面零回归。
30. 作为执行者，我想收口含冻结指纹六件复核（客户 eval 双树 + service.py + server.py + 客户 web/ 三件 + 本讲 Java 源件与 workbench_web/ 三件）与全量 `mvn test` 绿，以便旧能力零回归、对照面未被偷偷改动。

## Implementation Decisions

- **范围与对照源**（讲义 D1）：范围 = 绑定 eval 断言面三件（`web_api_and_persistence_projection_agree` 与 `delivery_evidence_and_review_controls` 为 L13 已建面的复验、`no_committed_secrets` 为新面）+ 合同验收三条 + 参考件断言面子集（`delivery_view.py` 投影块 + `workbench_server.py` views/静态 serve 段 + `workbench_web/` 取数面与身份文案 + `tests/test_delivery_view.py` 五测 + `tests/test_workbench_web_dashboard.py` 静态断言模式）；词面逐句对齐（九态归属表、issues 词面、未知状态文案全部继承上游中文原文）。
- **落点**（讲义 D2）：`workbench/delivery/` 新件 DeliveryView（纯函数 + 列表服务方法）+ HttpApi 增量（列表路由、view() 升级、verify 仅复验路由〔D5 取数面之 verify 动作——上游 verify_task 断言面，复查轮 T-4 勘误补列〕、静态 serve）；本仓顶层 `workbench_web/` 静态三件（写集字面落位）；`workbench/evals/l14/` 统一入口。受控面板实验独立库 `.runtime/course/L14-panel/`（L13-delivery 证据数据勿动——L13→L14 handoff 口径，复查轮 T-5 勘误：原文「复用 L13-delivery」会改写 L13 实验数据）；Checks 投影场景用临时库；受控客户实例临时库一次性（实验后留原件不留服务）。
- **DeliveryView 投影合同**（讲义 D4，采纳/裁掉边界）：采纳块 = schema/task_id/requirement_id/request/business_refs/task 投影/**status**（九态 _STATUS_CONTROL 归属表逐字：owner id/label/next_action；completed 且有 reviewed_by → 归属改为具名交付审核人；unknown → 人工处置人 +「停止自动推进并核对未知状态、数据库和事件链」+ 内联硬词面 title「未知交付状态：{code|空值}」/summary「状态不在交付合同中，禁止显示为成功。」〔上游内联词面非 pipeline 块——复查轮 T-1 勘误〕；label「已停止，待核对」仅 dead_letter；known 态 label/title/summary 为 null〔pipeline 来源 D4 裁掉〕）/ **freshness**（updated_at 解析失败 → state unknown + stale true；age = max(0, now-updated)；stale = status ∈ {queued, spec_ready, executing, evaluating, rework} 且 age > 300——**review 不在 stale-sensitive 集**；state = stale ? "stale" : (终态 ? "final" : "current")）/ policy / **execution**（out_of_scope_files 等）/ **eval**（available/decision/blocking 计数——summary 缺项时按 results 兜底计算/report_path/report_sha256/cases/runner）/ **review**（required = status==review + reviewed_by/decision/note/reviewed_at）/ **feedback**（四计数）/ **events 投影**（全序，每事件带 to_status 的 status_view）/ **allowed_actions**（基础 view_evidence+add_feedback；queued/spec_ready/rework + run；review + approve/reject；completed 且 requirement_id 前缀 REQ-COURSE-L + export_candidate）/ **integrity**（五类 issues + truthful = issues 空）/ **links**（self=/api/v1/delivery/views/{id}、task=/api/v1/tasks/{id}、web=/#delivery）/ **list 汇总**（total/active/review/completed/rework/failed[含 dead_letter]/unknown/pending_feedback 八计数〔verified_evolutions 随 evolution 裁掉〕）/ **include_detail=False 压缩**（task 投影去 spec/result/events；eval 去 results 留 cases 与路径摘要；execution 去 diff/stdout_tail/stderr_tail/commands/change_manifest）。裁掉块落账（Out of Scope）：control_surface、evolution、lane（cockpit）、workflow_graph、delivery_summary.risks 的 injected_process_runner/authorization_policy 依赖项——**可承项按本仓 TaskStore/Workflow 字段如实翻译**（如 status≠completed、error、verify 模式提示），形态差异在证据账记录。
- **HttpApi 增量**（讲义 D5/D6）：`GET /api/v1/delivery/views`（?limit= 默认 20，非法 limit 沿用 L13 容错口径）；`GET /api/v1/delivery/views/{id}` 与 verify/review POST 响应共用升级后的完整投影；**静态 serve 内建**：非 API 路径 → WEB_ROOT = 本仓 `workbench_web/`；`/` → index.html，其余取相对路径；`Path.resolve()` 后 **不在 WEB_ROOT 内 → 404**（穿越守卫同形）+ is_file 守卫 404；Content-Type 按后缀表（html/js/css/json 对照 mimetypes.guess_type 语义）；`Cache-Control: no-cache`；API 路由优先于静态回退；**无新 REGISTRY 命令**（上游同一 workbench_server 承载 serve 面）。
- **面板静态三件**（讲义 D5）：单页四区——身份区（「个人研发工作台」「FlowERP 是客户项目案例」身份文案 marker）、合同区（本讲合同）、任务列表区（views 列表 + 汇总计数 + 状态归属人）、详情区（事件时间线/Eval 摘要/integrity 块/动作按钮「提交并复验」「批准完成」）；取数面六面且仅六面（五个路径前缀，闭集机检——新增数据源必须过合同）；加载/空/失败三态文案；客户端转义（对照客户页 esc() 精神）；零依赖零 CDN；不 JSON.stringify 整个 detail。
- **ERP 受控实例与对账**（讲义 D3）：子进程 python 起客户 `App(临时库)` + `ThreadingHTTPServer(("127.0.0.1", 0))`（客户 tests/test_http_api.py 同形起法），随机端口，客户 web/ 由客户 server 自 serve；种子业务经客户 API（不写客户库文件）；对账四向断言留 Java；客户自测 `test_http_api.py` 以本仓 `.venv` python 照跑；**客户源码零改动**（收口 submodule status 恒空复核）。
- **no_committed_secrets Java 对应物**（讲义 D7）：扫本仓根；markers 三件语义 = 上游 cases.py:221 逐字，源件内**运行时拼接构造**（防自匹配——扫描器源码进证据链 diff 后整词字面会回流毒化扫描，首版实测抓获即此）；忽略 = `.git/.tmp/.runtime/.cache` 同形 **+ `target/.venv/vendors/java` 四目录**（Java 构建产物 / 环境非承诺 / 只读对照非本仓承诺 / L02-java 裁定保留的未跟踪件——形态差异落账）**+ diff-phase 采集件**（04-diff 输出 = 已扫描受跟踪源的衍生引用，扫描零增量）；后缀名单 = 上游七件同形 **+ `.java` + `.js`**（本仓源码与面板载体——上游是 Python 仓故无此二项，不补则主源码面扫不到；形态差异落账而非静默沿用）。
- **L14Checks 构成**（`workbench/evals/l14/` 统一入口，默认门全 blocking，本 spec 定形）：`l14_projection_review_owner`（C4 review 投影）、`l14_projection_unknown_and_freshness`（C4 unknown 不可信 + 300s/review 不 stale）、`l14_projection_compact_and_list`（C4 压缩 + 八计数 + 投影与库同源复核〔复查轮 T-3 补拍〕）、`l14_http_views_and_static`（C5 列表/详情/静态 serve/穿越 404/content-type/no-cache）、`l14_panel_static_asserts`（C1 marker 集 + 取数面闭集负面断言〔复查轮 T-6 补拍〕+ 无凭据 + 无 CDN + 三态）、`no_committed_secrets`（C3/C6 仓面扫描）、`l14_erp_reconciliation`（C2/C8 四向对账）、`l14_frozen_checks`（C7 指纹六件）。三件绑定 eval 中两件 L13 面的复验 = l13 DeliveryChecks 默认门照跑承载（断言面属主不变，L14Checks 不重复实现，证据账并列两门结论行）。

## Testing Decisions

- **好测试只测外部行为**：经公开缝断言可观察输出——HTTP 状态码与 JSON 体（投影字段、schema、汇总计数）、静态文件响应（content-type/no-cache/404 守卫）、面板文件文本（marker 与取数面）、对账快照数值；不直调私有方法；预期值独立计算不抄返回值。
- **起始红三面**（commit 1，讲义 §3 步骤 2）：①面板静态断言测试（`workbench_web/` 缺席——读文件即红）②HTTP 合同测试（views 列表路由缺席 → 404 红）③投影合同测试（对现存简化投影断言完整字段——freshness/status.owner/integrity 缺席即红，可编译不需类缺席）；经 testsupport/Cli 与真实端口承载。
- **投影纯函数面**：DeliveryView.build(task, now) 直测对照上游 `test_delivery_view.py` 五测同形（review 投影归属人/未知状态不可信/新鲜度语义/压缩等价/列表 join 与汇总）；合成态（unknown status、stale 时间窗）经入参 Map + now 注入构造，**不绕状态机改库造非法态**。
- **HTTP 端到端**：测试内起真实端口（同款 handler），HttpClient 驱动——列表默认 20 与自定义 limit、详情 schema 与 L13 受理响应 view_url 闭环（提交 → view_url 可取且为完整投影）、穿越路径（`/` 外的 `../` 编码路径）→ 404、静态件 content-type/no-cache、verify/review POST 响应升级同形。
- **面板静态断言**：文本直读三件（上游 test_workbench_web_dashboard 模式——marker 集、fetch 面名单、无凭据、无 JSON.stringify(detail)、三态文案、无 CDN）；**如实声明**：证明结构与取数面，不冒充渲染测试（讲义 D10）。
- **ERP 对账**：子进程 python 受控实例（探针族先例——断言与预期留 Java）；四向快照独立采集互相对账；`test_http_api.py` 客户自测照跑（`.venv` python）。
- **no_committed_secrets**：对本仓真实文件系统跑（含 workbench_web 三件自身——.html/.js 在名单）；自豁免与忽略集同上游。
- **先例**：L09–L13 Checks+合同测试族形（独立 main + `--case` + 默认门 + 冻结指纹）、L13 HTTP 端到端（真实端口 + HttpClient）、L07–L12 探针子进程族、文本直读口径（golden/合同 fixture 类）；golden 六重放不涉本讲（验收门替代 = 三面起始红 + 投影/HTTP/面板三面合同测试 + L14Checks 八件 + 两幕实验原件 + 指纹六件 + 全量 mvn test，讲义 D10）。
- **受控两幕实验**：幕一 = 真实起 `delivery-serve` 面板四拍（提交 202 → 列表见 review → 下钻详情 → 具名批准 completed）请求响应与投影原件；幕二 = 受控客户实例对账四快照原件；落 `lesson-14-submission/14-web/`（永不移动）。

## Out of Scope

- **投影裁掉块**（讲义 D4 落账）：control_surface（上游 main 后期增量，未建）、evolution 块（L15 演进记录面——L15 按断言面增量补块）、lane（cockpit 未建）、workflow_graph 块（L12 治理层已裁）、delivery_summary.risks 的 injected_process_runner/authorization_policy 依赖项（未建面字段）。
- **上游面板多面板件与端点**：course/current、cockpit/upgrade、execution/plans、preview、artifacts、patch、workflow-handlers 端点；initiatives / backups / projects / drafts / learning / desktop / workspace-dashboard / daily 多面板件；capabilities 的 code_execution 簇（L13 已裁维持）。
- 无头浏览器/真渲染证据（上游自己也不用——静态断言即对照面；引浏览器破依赖白名单，讲义 D10）。
- CI 工作流扩展（L08 工作流不动——D8；远程复验缺口如实声明；delivery-serve 与受控客户实例不进 CI）。
- 客户实现任何改动（铁律 1——ERP 页「交付」= 受控跑通 + 对账 + 零源码改动，不讲义 D3）；客户仓 web/ 拷贝进本仓冒充交付。
- L15 反馈治理 / Memory/RAG / 演进记录讲。
- 代码执行模式接线（面板维持 verify-only；代码执行走显式授权 CLI——L13 口径不变）。

## Further Notes

- 讲前 fetch 现查：pin `406f7aa`（检查点 0003）之后零新提交；上游 L14 讲义未上架——沿 L13 先例走**检查点自建讲义（第二例）**，上游日后上架则检查点对账补记（D10）。
- 上游把本讲风险点名为「投影视图写死旧状态」（`lesson_constructibility.py:31` LessonGap(14)——「工作台面板与 ERP 四方对账」）——C4 与测试决策围绕「投影实时算、页面真取数」设计。
- 客户真理无新面：erp_increment 的载体 `vendors/flowERP/web/` 在 pin 内已是完成态（app.js 693 行覆盖进货/销售/库存/财务/渠道/审计入口）；动手前复核指纹（C7/l14_frozen_checks）。
- 规模预期：投影断言面子集约 400–600 行 Java + HttpApi 增量约 100 行 + `workbench_web/` 三件约 300–400 行 + 检查件与合同测试约 800–1000 行——中等规模讲次（约 L13 一半）；实现按「DeliveryView → HttpApi → workbench_web → Checks」分段多 commit，链证据取红绿首尾。
- 执行纪律承袭：capture 命令与实跑逐字一致（L13 教训③）；status 密封前先探输出体量（L13 教训⑤ 52.76MB）；verify 前不补采（task_in_review 护栏）；submodule status 恒空收口复核。
- 验收顺序：起始红三面 → 实现转绿 → 受控两幕实验（原件）→ verify 集成（CASE-WB-L14-001，actor 填实际执行者，eval-command 绝对路径）→ code-review 双轴 → 用户按讲义 §4 具名验收（含亲手浏览器走面板四拍）→ merge --no-ff → 收口提请 push。
