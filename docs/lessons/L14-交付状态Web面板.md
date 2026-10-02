# L14 讲义：交付状态 Web 面板——让交付状态可见、可下钻、可对账

- 状态：**定稿 v1**（D1–D10 已裁决，见 §5 裁决记录；执行期起手 = 用户显式 `/mattpocock-skills:to-spec`，再 `/mattpocock-skills:implement`）
- 起草：2026-10-02；上游 pin `CodexFDE@406f7aa`（检查点 0003；起草日 fetch 现查 pin 后**无新提交**、课程讲义目录仍止于 L12——L14 未上架）
- 合同来源：`src/main/java/workbench/coursecontracts/CourseContracts.java` LESSONS[number=14]（冻结合同，逐字引用不修）：阶段 **operate**（第二讲）；workbench_increment「可查询任务与工作台面板」；erp_increment「交付 ERP 操作页」；描述「在无密钥 Web 面板展示 ERP 权威状态和交付证据，并能下钻到任务事件。」；demo_data `REQUIREMENT:COURSE-L14`；写集 `web/ workbench/ workbench_web/ tests/`（**web 与 workbench_web 首次进写集**）；验收三条「页面数据来自 API 而非静态假数据。」「DOM、API、SQLite 与 ERP 状态可对账。」「页面不包含凭据。」；绑定 eval `delivery_evidence_and_review_controls` + `web_api_and_persistence_projection_agree` + `no_committed_secrets`；causal_link「页面只投影工作台 API 与 ERP 权威状态，不生成假进度」
- 上游参考：**无 `docs/courses/L14/`（未上架）——检查点自建讲义（第二例，L13 先例直承）**。对照面 = pin 内更硬的事实源：①绑定 eval 断言面三件 `eval/cases.py:219-227`（no_committed_secrets）、`:262-333`（delivery_evidence_and_review_controls，L13 已建成对应物）、`:479-483`（web_api_and_persistence_projection_agree → `eval/task_api_contract.py` check_required_workbench——断言面 = L13 `l13_required_workbench` 登记项已覆盖）；②投影合同 `workbench/delivery_view.py`（348 行）+ 参考测试 `tests/test_delivery_view.py`（155 行，五测）；③面板静态断言模式 `tests/test_workbench_web_dashboard.py`（47 行——**上游测 DOM 面的方式就是静态文本断言，无浏览器依赖**）+ `workbench_web/`（index.html 212 / app.js 845 / styles.css）；④serve 面 `workbench/workbench_server.py:375-399`（views 列表/详情路由 + WEB_ROOT 静态 serve + 穿越守卫）；⑤客户面 `vendors/flowERP/web/`（AGENTS.md 约束 + index.html 221 / app.js 693 / styles.css，`flowerp/server.py` 自 serve——受控实例可原样跑）+ `tests/test_http_api.py`（客户自测，App + ThreadingHTTPServer 临时库起法）
- 本讲性质：**operate 第二讲 + 自建讲义第二例**。课程蓝图（pin 内在场）口径：「学生组织 Codex 完成工作台最小任务面板，并交付覆盖进货、销售、库存与财务入口的 FlowERP 操作页……验收重点：实际走通提交、查看状态、检查报告与获取产物；页面、接口、持久化记录和 ERP 结果一致，失败可追踪，前端与仓库无密钥」。上游 `lesson_constructibility.py:31` 把本讲的坑点名成一句：**「投影视图写死旧状态」**——本讲一切机检围绕「投影必须实时算、页面必须真取数」设计

## 1. 讲解

### 1.1 本讲要什么

L13 把执行链封装成了有稳定身份、事件与合法状态的 Task 资源，并在受理响应里承诺了 `status_url` 与 `view_url`——但 view 端点目前只是占位式简化投影（schema + task 平铺），也没有列表查询，更没有面板。本讲兑现因果交接（story 14「现场循环：让业务用户看见真实状态、失败和下一步」）：**面板不是新数据源，是 Task API 的投影**（L13 §1.5 原话）。三个增量面：①**投影视图完整化**——`workbench.delivery-view/v1` 从占位升级为完整合同（状态归属人/下一步动作/新鲜度/Eval 摘要/审核块/事件投影/完整性问题清单），列表查询带汇总；②**面板落地**——本仓新顶层 `workbench_web/` 静态三件（零依赖、零 CDN），由 `delivery-serve` 直接 serve（WEB_ROOT 同形 + 路径穿越守卫）；③**ERP 操作页跑通与四方对账**——客户 `flowERP/web/` 在受控实例上原样 serve 真数据，与本仓面板投影、两库快照对账（只读铁律下客户源码零改动）。

上游钉死的判断，本讲全链贯穿：**页面数据来自 API 而非静态假数据**（面板 fetch 面机检钉死）；**投影必须诚实**——未知状态显式不可信（「状态不在交付合同中，禁止显示为成功」）、review 是深思熟虑的人工等待态**不因等待久而标 stale**（上游注释原话：「review is a deliberate human wait state and must not become dishonest merely because the reviewer takes longer」）、completed 无具名批准 / review 无绿 Eval / 越写集写入 → integrity issues 如实列出、`truthful` 聚合；**页面不含凭据**（no_committed_secrets 扫描 + 面板无 apiKey/secret 断言 + 无 CDN 离线可跑）；**下钻到任务事件**（view 详情带全序事件投影，每事件带状态视图）。

### 1.2 业务案例：面板四拍 + ERP 对账（REQUIREMENT:COURSE-L14）

工作台面板四拍：①**提交**——面板填需求（demo 数据 `REQUIREMENT:COURSE-L14`）→ `POST /api/v1/delivery/requests` → 202 受理；②**看板**——`GET /api/v1/delivery/views?limit=20` 列表（汇总九计数：total/active/review/completed/rework/failed/unknown/pending_feedback——unknown 单列是「未知状态不可信」的看板面表达）→ 任务卡显示 review 态与归属人（human:reviewer、下一步「具名核对 Spec、Diff 与 Eval 后接受或打回」）；③**下钻**——`GET /api/v1/delivery/views/{id}` 详情（事件链全序投影 + Eval 报告路径与 SHA-256 + integrity 块）；④**具名批准**——面板动作 `POST /api/v1/tasks/{id}/review` → completed + reviewed_by 落账，列表汇总即时反映。

ERP 对账拍：受控起客户 server（subprocess python，`App(临时库)` + `ThreadingHTTPServer` 随机端口，客户 `tests/test_http_api.py` 同形起法）→ 客户 web/ 由客户 server 自 serve（`WEB_ROOT = web/`，进货/销售/库存/财务入口全在）→ 经客户 API 种子一笔业务 → 四向对账：客户页静态断言（AGENTS.md 约束：业务事实必须来自 /api/v1、esc() 转义、无凭据）↔ 客户 API 快照 ↔ 客户 SQLite 快照 ↔ 本仓工作台 view 投影（business_refs ↔ ERP 对象关联）。

### 1.3 客户真理现查（讲前复核，`vendors/flowERP` submodule 只读）

本讲客户侧**无新业务面**：erp_increment 的「交付」发生在客户仓 web/——pin 内已是完成态（app.js 693 行覆盖进货/销售/库存/财务/渠道/审计全入口，由 `flowerp/server.py` serve）。**flowERP 只读铁律下本仓不重写客户产品**（ADR-0005 客户真理精神）：ERP 面的承载 = 受控实例真实跑通 + 四向对账断言 + 客户自测 `test_http_api.py` 照跑。动手前复核客户 eval 双树 + `flowerp/service.py` + `flowerp/server.py` + `web/` 三件指纹（C7）。绑定 eval 三件**均非客户件**（`web_api_and_persistence_projection_agree` 与 `delivery_evidence_and_review_controls` 是上游工作台面 eval、`no_committed_secrets` 是仓面扫描）——本讲 C 表主源在参考件断言面，与 L13 同构。

### 1.4 宿主映射（ADR-0002）：上游双仓库 → 本仓库单仓库 + submodule

| 上游（CodexFDE 参考 / 课程双仓） | 本仓库 |
|---|---|
| `workbench/delivery_view.py`（build_delivery_view + DeliveryViewService） | `workbench/delivery/` 新件 DeliveryView（断言面子集翻译：freshness/status_view/eval_view/execution_view/review/feedback/events/allowed_actions/integrity/links + list 汇总；**裁掉块见 D4**） |
| `workbench_server.py` GET `/api/v1/delivery/views`（?limit=20）+ `/{id}` + WEB_ROOT 静态 serve（mimetypes + no-cache + 穿越守卫） | 同包 HttpApi 增量（列表路由新增；`view()` 从简化投影升级为完整投影——verify/review POST 响应随之同形；静态 serve 内建进 `delivery-serve`，**无新命令**） |
| `workbench_web/`（index.html + app.js + styles.css + 上游多面板件） | 本仓新顶层 `workbench_web/` **静态三件**（取数面子集：capabilities/views 列表/view 详情/verify/review；上游多面板件不采纳，D5） |
| 课程双仓写集 `web/`（= 客户仓 FlowERP 操作页） | **受控客户实例运行面**（客户 server.py + web/ 原样跑，零源码改动——L13 §2「flowerp/ → 临时库运行面」同型映射） |
| `tests/test_delivery_view.py` / `test_workbench_web_dashboard.py` | Java 合同测试（投影五面 + 面板静态断言）+ `workbench/evals/l14/` 登记项 |
| `eval/cases.py` no_committed_secrets（扫上游仓） | Java 对应物扫**本仓**根（markers/忽略集/后缀名单同形 + 本仓形态差异落账，D7） |

映射的**不变量**（上游教学点逐条承接）：投影实时算不缓存旧状态（constructibility 点名坑）；未知状态不可信不显示为成功；review 等待不 stale（诚实新鲜度）；integrity.issues 五类如实聚合（unknown_status / stale_active_task / completed_without_named_approval / review_without_green_blocking_eval / out_of_scope_writes）；include_detail=False 压缩保摘要（列表轻投影，报告路径与 SHA-256 保留）；面板取数面 = API 面且仅此五条；静态 serve 有穿越守卫与 no-cache；页面与仓库无凭据；无 CDN 离线可跑；客户页原样不拷贝不冒充本仓交付。

### 1.5 因果交接（本讲为下一讲准备什么）

L15「生成交付摘要并采集真实反馈」消费本讲的 view 投影 feedback 块与列表汇总（pending_feedback 计数即 L15 反馈治理的入口）；上游 delivery_view 的 evolution 块（本讲裁掉，D4）正是 L15 演进记录的投影面——本讲把投影合同骨架立住，L15 按断言面增量补块。L16 现场需求全链在人审与发布证据两环都依赖「页面可见、可对账」——本讲是 operate 阶段从「能交付」到「看得见交付」的一步。

## 2. 本讲合同（C 编号清单）

write_scope 映射（合同四路径 → 本仓库）：`web/` → 受控客户实例运行面（零源码改动）；`workbench/` → `src/main/java/workbench/delivery/` + `src/main/java/workbench/evals/l14/`；`workbench_web/` → 本仓顶层 `workbench_web/` 静态三件；`tests/` → `src/test/java/`。

| C | 验收项（来源） | 类型 | 怎么验 |
|---|---|---|---|
| C1 | 页面数据来自 API 而非静态假数据（acceptance[0]） | blocking | 面板静态断言（上游 test_workbench_web_dashboard 模式）：fetch 面名单 = 五条 API 路径（capabilities / views 列表 / view 详情 / verify / review）、无硬编码业务数据 marker、无 JSON.stringify(detail)、有加载/空/失败态文案；HTTP 面：views 列表与详情 schema `workbench.delivery-view/v1` 一致 |
| C2 | DOM、API、SQLite 与 ERP 状态可对账（acceptance[1]） | blocking | 四向对账机检（受控客户实例 + 两库快照 + 工作台投影关联 business_refs）+ view 详情事件全序投影（下钻面）+ 投影与 `.runtime` L13-delivery 库三表同源复核（同一 taskId 经 API 与经 SQL 读出一致） |
| C3 | 页面不包含凭据（acceptance[2]） | blocking | `no_committed_secrets` Java 对应物（markers `sk-proj-`/`-----BEGIN PRIVATE KEY-----`/`AKIA` + 忽略集 `.git/.tmp/.runtime/.cache` + 后缀名单同形，本仓形态差异 .java/.js 落账）+ 面板无 apiKey/secret 断言 + 无 CDN（离线可跑） |
| C4 | 投影诚实性（上游 delivery_view 断言面 + constructibility 点名坑） | blocking | review 投影（owner human:reviewer + next_action 含「具名」+ 绿 Eval 摘要 report_path/SHA-256）；unknown 状态（known false +「未知交付状态」+ requires_human + issues 含 unknown_status + truthful false）；freshness（stale-sensitive 集与 300s 阈值同形；**review 不因等待而 stale**）；integrity 五类 issues + truthful 聚合；include_detail=False 压缩（results/spec/events 摘要化，cases/report_path/SHA-256 保留）；列表汇总九计数 |
| C5 | 查询面三件落地（L13 因果交接兑现） | blocking | `GET /api/v1/delivery/views?limit=20`（新路由，默认 20 与上游同形）；`GET /api/v1/delivery/views/{id}` 完整投影（替代简化投影；verify/review POST 响应同形升级）；静态 serve（`/`→index.html + WEB_ROOT 解析 + **穿越守卫** resolve 后不在根内 → 404 + is_file 守卫 + mimetypes Content-Type + `Cache-Control: no-cache`） |
| C6 | 绑定 eval 三件（合同 evalCases） | blocking | `web_api_and_persistence_projection_agree` 断言面（check_required_workbench——受理/幂等/冲突/持久/无 flowerp.db，L13 `l13_required_workbench` 面复验）；`delivery_evidence_and_review_controls` 断言面（L13 DeliveryChecks 回归直承）；`no_committed_secrets`（新面，C3 承载） |
| C7 | 冻结与回归 | blocking | 指纹六件（客户 eval 双树 + `flowerp/service.py` + `flowerp/server.py` + 客户 `web/` 三件 + 本讲 Java 源件与 `workbench_web/` 三件）+ `mvn test` 全量绿（含 golden 六重放 + L07–L13 合同）；CodexFDE 参考件由 submodule pin 锁（检查点 0003），不重复指纹（L13 口径） |
| C8 | ERP 操作页交付（erp_increment，只读铁律下的交付形态） | blocking | 受控客户实例真实跑通（subprocess python 起客户 App+server，客户 test_http_api 同形起法，随机端口 + 临时库）→ 客户 web/ 原样 serve → 经客户 API 种子业务 → 客户页静态断言（AGENTS.md 约束面）+ 客户自测 `test_http_api.py` 照跑；**客户源码零改动**（铁律 1 恒空复核） |
| C9 | 诚实性分层留痕 | observing | 投影不写死旧状态（每次请求实时构建）；静态断言只证明结构与取数面，不冒充渲染测试（如实声明）；「客户页原样跑通」不写成「本仓交付客户页」；review 不显示成 completed 或「接近完成」；不采纳清单（control_surface/evolution/lane 等未建面）如实入账不粉饰 |

登记项构成（`workbench/evals/l14/` 统一入口，默认门全 blocking——构成在 spec 阶段定形）：投影三面（review 投影 / unknown+freshness / 压缩+列表汇总）、HTTP 面（views 路由 + 静态 serve + 穿越守卫）、面板静态断言、仓面密钥扫描、L13 两 eval 断言面复验、受控 ERP 对账、冻结指纹。

## 3. 实操流程 + Claude 简报

1. **前置（裁决后、动手前）**：讲义定稿入 master（§5 裁决记录补齐）。无对照基准（自建讲义，L13 D9 直承）；回归门 = 全量 `mvn test`。**规模预期如实**：DeliveryView 断言面子集约 400–600 行 Java + HttpApi 增量约 100 行 + `workbench_web/` 三件约 300–400 行 + L14 检查件与合同测试约 800–1000 行——中等规模讲次（小于 L13 一半）。
2. **候选分支 `lesson-14`**：【配合点·to-spec】`/mattpocock-skills:to-spec` 用户显式（spec 落 `docs/specs/`；接缝确认：投影合同字段集 + 面板取数面名单 + 受控实例起法三缝具名）。commit 1 = 合同测试起始红（`workbench_web/` 缺席 / views 列表路由缺席 / DeliveryView 完整投影缺席三面）+ 红证据（capture + ImportEvidence 落账）。
3. **实现转绿**（用户显式 `/mattpocock-skills:implement` 后动笔）：`workbench/delivery/` DeliveryView（逐段对照 `delivery_view.py` 断言面子集——freshness/_STATUS_CONTROL 九态归属表/eval_view/execution_view/integrity 五类/links/list 汇总）→ HttpApi 增量（列表路由 + view() 升级 + WEB_ROOT 静态 serve + 穿越守卫）→ `workbench_web/` 三件（对照上游取数面与身份文案 marker 子集 + flowERP AGENTS.md 约束）→ `workbench/evals/l14/` 登记项。
4. **受控两幕实验**（C2/C8 端到端原件）：幕一 = 真实起 `delivery-serve` → 面板四拍（提交 202 → 列表见 review → 下钻详情 → 具名批准 completed）请求响应与投影原件；幕二 = 受控客户实例对账四快照原件；落 `lesson-14-submission/14-web/`（永不移动）。
5. **verify 集成**：`./bin/wb workbench-task-run CASE-WB-L14-001 --mode verify --eval-command "<L14Checks 绝对路径命令，本地展开>" --execution-timeout 900 --actor Claude`（L08–L13 先例同形；actor 填实际执行者）→ `workbench-status` 密封（**先探输出体量再采**——L13 教训⑤ 52.76MB）。
6. **收口**：`code-review` 双轴（master...lesson-14）→ 修复轮（若有）→ 用户按 §4 具名验收 → `git merge --no-ff lesson-14`（合并信息含验收人与结论）→ roadmap 阶段 5 行推进 + 讲义导航 + CLAUDE.md（架构行 delivery 增量 + workbench_web 顶层 + evals l14 + 待建设清单改口）；收口后提请用户 push。

### Claude 简报（第 3 段粘贴用）

> 在 lesson-14 候选分支上：①以合同测试（workbench_web/ 缺席 / views 列表路由缺席 / DeliveryView 完整投影缺席三面）采起始红，capture + ImportEvidence 落账；②实现 `workbench/delivery/` DeliveryView（对照 delivery_view.py 断言面子集：freshness——review 等待不 stale、unknown 显式不可信、integrity 五类 issues、include_detail 压缩、列表九计数汇总）+ HttpApi 增量（GET /api/v1/delivery/views?limit=20 + view() 完整投影升级 + WEB_ROOT 静态 serve + 路径穿越守卫 404 + mimetypes + no-cache）+ 本仓顶层 `workbench_web/` 静态三件（取数面五条 API、零 CDN、加载/空/失败态、无凭据）+ `workbench/evals/l14/` 登记项（含 no_committed_secrets 仓面扫描与 L13 两 eval 断言面复验）；③受控两幕实验原件落 lesson-14-submission/14-web/（面板四拍 + 受控客户实例对账四快照——subprocess python 起客户 server，客户 web/ 原样跑零改动）；④verify 集成 CASE-WB-L14-001（actor 实际执行者，eval-command 绝对路径）+ status 密封（先探体量）。vendors/ 只读恒空复核；投影实时算不写死旧状态；review 不因等待而 stale；未知状态禁止显示为成功；页面与仓库无凭据；静态断言不冒充渲染测试；客户页原样跑通不写成「本仓交付客户页」；上游未建面（control_surface/evolution/lane/workflow_graph 块）不采纳不入账为已建。

## 4. 人审清单

- **看哪个 diff**：commit 1 = 三面起始红（无实现）；实现分段 commit（DeliveryView → HttpApi → workbench_web → Checks）。DeliveryView 词面逐段对照上游 `delivery_view.py`（九态归属表 _STATUS_CONTROL、freshness 阈值与 stale-sensitive 集、integrity 五类词面、links 三条）；HttpApi 静态 serve 对照 `workbench_server.py:375-399`（穿越守卫条件 `WEB_ROOT not in parents` 同形、no-cache、404 双守卫）；`workbench_web/` 三件对照上游取数面与 marker 集。实验原件：两幕请求响应 + 四快照——出现断言面之外的文件改动即红旗。
- **跑哪些门**：亲手 `mvn test`（全量含六重放 + L07–L13 合同 + 本讲投影/HTTP/面板静态断言）；亲手起 `delivery-serve` 用**浏览器**开面板走四拍（提交 → 列表 review → 下钻事件 → 批准 completed——页面真取数、无假数据）；`L14Checks` 默认门全绿 rc 0（含密钥扫描与 L13 面复验）；`workbench-status --require-red-green-evidence`；核对指纹六件；核对 D1/D4 不采纳清单（上游后期增量与未建面未被混入）。
- **什么算作弊**：投影视图写死旧状态或缓存快照（constructibility 点名坑——面板显示的状态与库不一致）；面板硬编码业务数据/演示结果冒充 API 取数；review 显示成 completed 或「接近完成」；未知状态被显示为成功；穿越守卫缺失或被绕过（`/` 外路径读到仓库其他文件）；凭据进前端或仓库（markers 扫描红）；把客户 web/ 拷进本仓冒充「交付 ERP 操作页」（客户源码必须零改动）；对账只对预置快照不起真服务；静态断言写成「已渲染验证」；不采纳面（control_surface 等）被混入冒充对照范围。

## 5. 候选表（决策点，一次列全，等具名确认）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| D1 | **范围与对照源**（自建讲义第二例，L13 D1 先例直承） | 范围 = 绑定 eval 断言面三件（两件为 L13 已建面的复验 + no_committed_secrets 新面）+ 合同验收三条 + 参考件断言面子集（`delivery_view.py` 投影块 + `workbench_server.py:375-399` serve 段 + `workbench_web/` 取数面与身份文案 + `tests/test_delivery_view.py` 五测 + `tests/test_workbench_web_dashboard.py` 静态断言模式）；**不采纳清单如实入账**：投影的 control_surface / evolution / lane（cockpit）/ workflow_graph 块（上游 main 后期增量或跨讲面，L15/检查点再议）、上游面板的 course/current、cockpit/upgrade、execution/plans、preview、artifacts、patch、workflow-handlers 端点与 initiatives/backups/projects/daily/desktop 多面板件、capabilities 的 code_execution 簇（L13 已裁维持） | 按 workbench_server.py 全量路由复刻（范围失控且混入未建面，不取）；等上游讲义上架再开工（时间不可控且 L13 先例已裁自建路线） |
| D2 | **写集映射与落点**（web/ 与 workbench_web/ 首次进写集） | `web/` → **受控客户实例运行面**（客户 server.py + web/ 原样跑，零源码改动——L13「flowerp/ → 临时库运行面」同型）；`workbench/` → `src/main/java/workbench/delivery/`（DeliveryView 新件 + HttpApi 增量）+ `src/main/java/workbench/evals/l14/`；`workbench_web/` → **本仓新顶层 `workbench_web/` 静态三件**（index.html + app.js + styles.css——写集字面落位）；`tests/` → `src/test/java/` + 面板静态断言测试 | 本仓顶层 `web/` 自建 ERP 页（重写客户产品，违背客户真理与只读铁律精神，不取）；面板文件塞进 `src/main/resources`（写集字面断裂且上游是顶层目录，不取） |
| D3 | **ERP 操作页承载**（erp_increment 的「交付」形态，本讲特色裁决） | 受控实例承载：subprocess python 起客户 `App(临时库)` + `ThreadingHTTPServer`（客户 test_http_api 同形起法，随机端口）→ 客户 web/ 由客户 server 自 serve → 四向对账（客户页静态断言 ↔ 客户 API ↔ 客户 SQLite ↔ 本仓 view 投影 business_refs 关联）+ 客户自测 `test_http_api.py` 照跑；「交付」如实落账 = **跑通 + 对账 + 客户源码零改动** | 完全跳过 ERP 面（erp_increment 与写集 web/ 无承载，合同面残缺，不取）；对客户 web/ 做注入式改造（注入装置先例是缺陷教学件，不是交付形态，不取） |
| D4 | **投影合同子集**（delivery_view.py 348 行的翻译边界） | 采纳块：schema/task_id/requirement_id/request/business_refs/task 投影/status（九态归属 + unknown 不可信）/freshness（300s + review 不 stale）/policy/execution/eval/review/feedback/events/allowed_actions/integrity 五类/links/list 汇总九计数/include_detail 压缩；**裁掉块落账**：control_surface（未建）、evolution（L15 面）、lane（cockpit 未建）、workflow_graph（L12 治理层已裁）、delivery_summary.risks 的 injected_process_runner/authorization_policy 依赖项（未建面字段——可承项按本仓 TaskStore 字段翻译，形态差异如实记录） | 全字段逐字翻译（拖入四个未建模块依赖，范围失控，不取）；连 status/freshness 也简化（投影诚实性是本讲灵魂与 constructibility 点名坑，不取） |
| D5 | **面板最小面**（workbench_web 三件的范围） | 单页四区（身份区：个人研发工作台/FlowERP 是客户项目案例；合同区；任务列表区：views 列表 + 汇总计数 + 状态归属人；详情区：view 下钻——事件时间线/Eval 摘要报告路径与 SHA-256/integrity 块/动作按钮「提交并复验」「批准完成」）；取数面五条 API；零依赖零 CDN；加载/空/失败三态（flowERP web/AGENTS.md 约束承袭）；无 apiKey/secret；不 JSON.stringify 整个 detail | 复刻上游多面板（initiatives/drafts/learning/backups/workspace-dashboard——不在断言面，不取）；引前端框架或 CDN（离线铁律与零依赖白名单，不取） |
| D6 | **静态 serve 面** | 内建进 `delivery-serve`（上游同一 workbench_server 承载，**无新 REGISTRY 命令**）：WEB_ROOT = 本仓 `workbench_web/`；`/` → index.html，其余相对路径；resolve 后**不在 WEB_ROOT 内 → 404**（穿越守卫同形）+ is_file 守卫；Content-Type 走文件名映射（对照 mimetypes.guess_type，Java 侧按后缀表实现同形）；`Cache-Control: no-cache`；API 路由优先于静态回退 | 新增 `web-serve` 独立命令（上游无此分裂，不取）；静态件交外部服务器（面板与 API 跨源，取数面与守卫面断裂，不取） |
| D7 | **检查件与 no_committed_secrets 形态** | `workbench/evals/l14/` 统一入口（族形承袭，构成 spec 阶段定形，C 表已列九面）；no_committed_secrets Java 对应物扫**本仓**根：markers 三件 + 忽略集 `.git/.tmp/.runtime/.cache` 同形**+ 补 `target/`**（Java 构建产物——形态差异落账）+ 后缀名单 `.py/.md/.json/.toml/.yml/.yaml/.html/.txt` 同形**+ 补 `.java/.js`**（本仓源码载体是 Java、面板是 js——上游是 Python 仓故无此二项；不补则主源码面扫不到，形态差异落账而非静默沿用） | 逐字沿用上游名单不补（Java/js 扫描面缺席 = 凭据检查空转，不取）；引扫描库（白名单零新增铁律，不取） |
| D8 | **CI 面：本讲不动 L08 工作流** | 保持 `.github/workflows/l08-eval.yml` 终态不动（L09–L13 D8 直承）；本讲 CI 覆盖 = push lesson-14 触发现有 Run + 编译面；L14Checks 远程复验缺口如实声明（本地全量门承载）；`delivery-serve` 与受控客户实例均不进 CI（一次性 Run 环境与常驻/多进程语义不匹配） | 本讲扩工作流（跨讲合同变更，不取）；CI 内起双服务做端到端（Run 环境语义不匹配，不取） |
| D9 | **命名与证据** | 分支 `lesson-14`；证据账 `docs/replication/evidence/L14.md`；任务 `CASE-WB-L14-001` @ `.runtime/course/L01-workbench-java/`；采集根 `lesson-14-submission/`（含 `14-web/` 子根：面板四拍 + ERP 对账四快照原件，永不移动）；讲义文件名 `L14-交付状态Web面板.md`（上游 title「让交付状态在 Web 面板可见」紧凑化） | 文件名直用上游 title 全句（可，备选）；任何 -java 后缀；复用 lesson-13-submission（跨讲混链） |
| D10 | **无对照基准的验收门替代**（L13 D9 直承 + 本讲新面） | ①合同测试三面起始红 ②投影与 HTTP 合同测试（对照 test_delivery_view 五测与 serve 段）③面板静态断言（对照 test_workbench_web_dashboard 模式——如实声明：证明结构与取数面，不冒充渲染测试）④L14Checks 默认门 ⑤受控两幕实验原件 ⑥指纹六件冻结对账 ⑦全量 `mvn test`。如实声明：无字节级对照基准 + 无上游讲义（自建第二例）；上游 L14 讲义日后上架则检查点对账补记 | 现在补建对照基准（硬造即自造对照，不取）；引无头浏览器补「真渲染」证据（破坏依赖白名单且上游自己也不用，不取） |

> **裁决记录（2026-10-02）**：D1–D10 推荐方案**全部接受**。用户原话：「没问题，继续」（批量确认，逐字入账）。起草日现查：CodexFDE pin `406f7aa`（检查点 0003，fetch 后零新提交）；上游 L14 讲义未上架——沿 L13 先例走**检查点自建讲义（第二例）**（L13 会话已裁决自建路线，本讲开局 fetch 现查确认无新上游后直承）。绑定 eval 三件在场（`eval/cases.py:219-227` no_committed_secrets / `:262-333` delivery_evidence_and_review_controls / `:479-483` web_api_and_persistence_projection_agree——后两件断言面为 L13 已建面的复验）；参考件断言面子集在场（`workbench/delivery_view.py` 348 行 + `tests/test_delivery_view.py` 五测 + `tests/test_workbench_web_dashboard.py` 静态断言模式 + `workbench_server.py:375-399` serve 段）；客户面在场（`vendors/flowERP/web/` 三件 + AGENTS.md 约束 + `flowerp/server.py` WEB_ROOT 自 serve + `tests/test_http_api.py` 起法先例）。客户真理无新面（erp_increment 的「交付」= 受控实例跑通 + 对账 + 零源码改动，D3）。
