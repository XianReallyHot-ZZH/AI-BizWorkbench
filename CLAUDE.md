# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 本仓库是什么

CodexFDE（个人 AI 研发工作台 + 课程仓库）的**渐进式复刻**：以 `vendors/` 下两个 submodule 为只读对照，逐讲（L00–L16）重建属于自己的、日常可用的工作台。课程逐讲是路径，不是终点。对照基线与升级记录以 [docs/research/codexfde-orientation.md](docs/research/codexfde-orientation.md) §10 检查点 changelog 为唯一事实源，上游变更只在检查点显式采纳（ADR-0003），不自动同步。

词汇表在根目录 [CONTEXT.md](CONTEXT.md)——**措辞必须继承它**（复刻/上游/候选分支/起始红/具名验收/证据/合同 fixture），避免各词条的 _Avoid_ 替代词。

## 铁律（先读这里）

1. **`vendors/` 只读**。CodexFDE 与 flowERP 是 submodule，任何复刻产物不得写回。`git -C vendors/CodexFDE status --short` 与 flowERP 同查必须恒空。
2. **待审核 ≠ 已接受**（逐字使用）。候选通过全部门槛也只是待审核，直到用户具名验收。Claude 不代签、不自行 merge。
3. **证据 = 命令 + 退出码 + 失败后权威状态**。AI 自述、绿色截图不算证据；失败记录一律保留，不删不改凑通过。
4. **冻结合同 fixture** 是上游 LESSONS 数据的逐字数据，不许"修好"它——发现与上游分歧走检查点显式采纳。载体（ADR-0006）：Python 阶段为 `workbench/course_contracts.py` 逐字拷贝；Java 重走后为 `CourseContracts.java` 常量类，以**字符串内容逐字等价 + 完整性机检测试**锁定——重走线完成后（2026-09-30）Python 载体退役，对照源 = tag `python-carrier-final`，机检投影 `frozen-python-projection.json` 已入库随测。
5. **用户显式技能**（`/mattpocock-skills:to-spec`、`:implement`、`:handoff`、`:teach`）到点必须停下提醒用户调用；降级路径仅在用户明确同意后走（见 docs/lessons/README.md 技能编排表）。
6. 换会话用 `/mattpocock-skills:handoff` 生成交接文档，不以裸 `/clear` 为默认。

## FlowERP 业务边界（L02 固化）

以下边界在涉及 FlowERP 业务实现时适用（`vendors/flowERP` 对照、L04+ 候选内业务代码），纯文档改动不受约束：

1. 可用库存不能为负，预占必须原子化；缺货在创建时拒绝，不做"先记账后修正"。
2. 同一个入库幂等键只能生效一次；再次入库用新幂等键，而不是让键失效。
3. 订单只能按状态机迁移，取消时释放预占；释放是取消的后件义务，不可省略。
4. 采购补货必须经具名审批后才能入库；自动检查通过只推进到"待批准"，不构成批准。
5. 任务、Eval 和反馈必须可追溯，失败不能显示成成功（铁律 2/3 的业务面：自动检查不冒充人工接受，失败记录一律保留）。

越界请求明确判拒，拒绝后原数据不变；方案确需放宽某条边界时，走候选分支 + 具名验收，不走默认放行。

## 常用命令

```bash
# Python 载体已退役（ADR-0006 尾款，2026-09-30，tag python-carrier-final）：
# workbench/ tests/ evals/ pyproject.toml 已移出工作树；对照/再生（含 golden 重生成、
# 合同投影导出、历史复跑）经 `git checkout python-carrier-final`。.venv 与 python 本体
# 保留——capture_evidence 采集与 evals 工具的子进程 python 探针仍在用（客户实现是 Python）。

# Python 时代账本（只读封存，勿手改；status 重算 SHA-256 的复核随 tag 内 CLI）
sqlite3 "file:.runtime/course/L01-workbench/workbench.db?mode=ro"

# Java 线（重走期唯一活动实现，ADR-0006；状态唯一事实源 = docs/replication/README.md 重走线行，此处不缓存进度）
mvn test                                # 全量门：合同测试 + golden 六重放（l01–l06）+ fixture 机检
mvn test -Dtest=L02WorkbenchRulesTest   # 单类

# 工作台 CLI（Java 版，经 bin/wb 包装：classpath = target/classes + 依赖清单落盘）
./bin/wb workbench-status \
  --runtime-dir .runtime/course/L01-workbench-java \
  --require-project PERSONAL-WORKBENCH --require-task CASE-WB-L01-JAVA-001 \
  --require-red-green-evidence
```

证据采集/导入用 vendor 工具（只执行不修改）：`vendors/CodexFDE/docs/courses/L01/tools/evidence.py` 与 `import_evidence.py`（后者用 `sys.executable`，必须以本仓库 `.venv` 的 python 运行）。捕获目录 `lesson-01-submission/` **随 Git 提交且永不移动**——meta.json 里的 `output_file` 是绝对路径，移动即断封条。

## 每讲工作流（候选分支机制，ADR-0004）

1. **讲前**：`git -C vendors/CodexFDE fetch` 现查上游（勿信快照），起草 `docs/lessons/LNN-标题.md`（四段结构见 docs/lessons/README.md），**用户审完讲义才动手**。
2. **候选分支 `lesson-NN`**：commit 1 = 合同测试（起始红）+ 红证据；实现后采 Diff + 后绿（与红**同一测试命令**、observed_at 严格递增）再 commit 2。起始红必须先于实现真实存在。
3. **证据**：`docs/replication/evidence/LNN.md`——C 编号逐项结论表 + 命令台账（输出摘要 + 退出码）+ 失败现场 + 末尾具名验收行。
4. **验收与合并**：用户按讲义 §4 清单核对后，验收行入证据账，`git merge --no-ff lesson-NN`（合并信息含验收人与结论），更新 roadmap 状态。
5. 验收前 Claude 自调 `code-review` 复查整条候选分支；发现按 test-first 修复并保留旧证据。

## 完成定义（Definition of Done）

- 每条新业务规则至少一个正常路径 + 一个失败路径用例；纯文档改动不触发完整业务检查。
- 交付交回三样：范围内 Diff、实际检查结果与真实退出码、未解决问题清单。
- 未实现不得写成已实现；缺口标"待建设"，与上游口径一致。

## 架构大图

```
本仓库（工作台 = Java 实现，复刻产物；状态唯一事实源 = docs/replication/README.md）
├── src/main/java/workbench/       Java 实现本体（与退役 Python 载体同形分层：cli → bootstrap → sqlite 存储）
│   ├── cli/                       Main + CommandRegistry：入口注册缝 + 顶层异常边界（JSON 错误契约）
│   ├── bootstrap/                 五命令逻辑（Args/Ledger/JsonOut/PyJson/BootstrapCommands/Command）
│   ├── spec/                      六段 Spec 结构解析器（L03-java，词面与检查序与冻结版逐字同形）
│   ├── execution/                 V0 受控执行三命令（L04-java，与 Python execution.py 同形：run/review/show + Shlex/ProcessRunner/WorkspaceInspector/WriteScope）
│   ├── evals/                     统一运行器根两件（L06-java：EvalHarness 分级/报告/x 模式/退出码 + ReportContract 报告合同——evals/harness.py 与 report_contract.py 对应物，报告 schema 1.0 对齐客户）+ 评测工具子包 l05 两件（缺陷基线构造器+收货场景驱动）与 l06 三件（CSV 缺陷基线+口径交叉检查+五登记项收口 Checks）与 l07 三件（L07：OrderChecks 六登记项统一入口 + OrderProbe 订单面探针——断言与预期留 Java，经子进程 python 驱动客户实现；QualityGate Stop 处理器——**进 REGISTRY 的 `quality-gate` 命令（L07 D2 新先例，其余 evals 工具仍独立 main）**，stdin 事件→cwd 校验→重入短路→子进程调统一入口→协议翻译，事件留痕 .runtime/hooks/；待审投影 hook_staging/，安装件 .claude/settings.json 已入库生效）与 l08 四件（L08：SalesChecks 八登记项统一入口——本地/CI 同一 Eval 身份；AtomicProbe 原子预占三模式探针；CiEvidence 证据信封——**进 REGISTRY 的 `ci-evidence` 命令（L08 D3）**；InjectDefect 教学注入装置——上游 diff 逐字 + 锚点唯一性 + 幂等护栏，只写拷贝树永不写回 vendors；远端面 `.github/workflows/l08-eval.yml` 终态 + `CI_GATE_SPEC.md` 门规格，真实 Run 受控对照证据在 lesson-08-submission/ci/）与 l09 三件（L09：CancelProbe 取消面探针——入门六模式+正式面，断言留 Java；CancelChecks 九登记项统一入口——五取消拍+客户 cancellation_releases_reservation 原名照跑+l09_sales_cancel_formal 正式面护栏+mapper_boundaries 六断言+冻结指纹，--case 选跑=Repair Task reproduce 形态面；InjectCancelDefect 替换式注入——上游 diff pin 内缺席自拟如实记录，只写拷贝树永不写回 vendors）与 l10 三件（L10：ShipProbe 发货面探针——五模式 legal/draft/cancelled/double/refuse-all，三量走 service.product() 计算列，断言留 Java；ShipChecks 六登记项统一入口——四发货拍+客户 illegal_transition_is_blocked 原名照跑+发货面源件全冻指纹（service.py+sales.py）；InjectShipDefect 上游逐字锚点注入——GOOD/BAD 行逐字源在 candidate_loop_lab.py:24-25，只写拷贝树永不写回 vendors）与 l11 三件（L11：PurchaseProbe 采购面探针——七模式临时库五表快照，夹具 PR-OTHER SQL 直插 integration_lab 同形，断言留 Java 三量走 product() 计算列；PurchaseChecks 九登记项统一入口——六采购拍 + 客户 purchase_request_preserves_reason 原名照跑 + write_sets_reject_conflict Java schedule 核直测 + 冻结指纹三件，premature-stock 必红选跑项不进默认门；InjectPurchaseDefect **插入式**注入 + --restore 恢复——上游逐字锚点 integration_lab.py:87-88，diff 互逆可对账，只写拷贝树永不写回 vendors）与 l12 两件（L12：ApprovalProbe 审批入库面探针——九模式临时库五表阶段快照，**触发器注入 :66-67 逐字源内联**（运行时数据库行为——不建注入装置件、不建常驻拷贝树），断言留 Java 三量走 product() 计算列；ApprovalChecks 十件默认门统一入口——七业务拍 + 客户 purchase_requires_approval/receiving_is_idempotent 原名照跑 + 冻结指纹三件，**两必红选跑不进默认门**——status_write_failure（R1 两段事务窗口：断言理想不变量五表不变，客户部分提交违反即预期红留证）/key_collision（R4 已消费键静默吞：received 但无 +7——失败显示成成功），客户原样行为不修绿不删报告）与 l13 一件（L13：DeliveryChecks 十件默认门统一入口——八工作台面机检（进程内 HttpApi 起真实端口 + 合成 suite 经 suite_runner 缝注入，上游 fixture 防递归同理）+ 客户 purchase_requires_approval 原名照跑 + 冻结指纹三件；真实检查面由受控 API 实验承载——delivery-serve 缺省 suite 客户照跑全链）与 l14 一件（L14：WebPanelChecks 八件默认门——投影三面（build(task, now) 纯函数缝，合成态不绕状态机）+ HTTP/静态面（穿越守卫/content-type/no-cache）+ 面板静态断言（**取数面六面闭集负面断言**）+ no_committed_secrets 仓面扫描（**markers 运行时拼接构造 + diff-phase 采集件忽略——扫描器自毒实测实录**）+ 受控 ERP 四向对账（子进程 python 起客户 App+server：页静态↔API↔product_master SQLite↔投影 business_refs + 客户自测 test_http_api 照跑）+ 冻结指纹七件；两件 L13 面绑定 eval 经 l13 DeliveryChecks 默认门照跑复验）与 l15 一件（L15：L15Checks 十件默认门——l15_raw_feedback_governance〔eval 2 四拍 + EVALS blocking 静态断言——客户 harness.py 文本机检〕/l15_evolution_state_machine/l15_evolution_verify_gate〔governed 正反拍〕/l15_memory_source_and_version〔两入口+superseded 原子+旧快照不改写〕/l15_memory_recall_governance〔项目隔离/撤回不注入/同源/如实为空/预算〕/l15_memory_bind_and_runs〔双唯一/五相位/outcome 具名/漂移〕/l15_view_evolution_block〔投影补块+九计数〕/l15_dynamic_spec_and_start〔冻结关联/注入/重名/起始三态〕/l15_erp_upgrade_before_after〔R1：拷贝客户树→前拍探针 invariant violated 红〔L12 同款触发器逐字〕→锚点三写合单事务→后拍绿+客户 eval 前后照跑双绿+双 submodule 恒空〕/l15_frozen_checks〔指纹九件——S01 冻结面经 tag git show 现算+上游 evolution/feedback/delivery_view+客户 harness/cases/service+本讲源件两件〕；delivery_evidence_and_review_controls 复验 = l13 门照跑并列两门〔L14 口径〕；DynamicSpecFreeze 纯核〔delivery 包：dynamic_eval_required 首见承载——freeze 冻结关联/词边界/注入+dynamicNameCheck 重名拒绝+startEvidence 起始红形式化，无 CLI 面 L11 先例〕）
│   ├── repair/                    修复映射器（L09-java：RepairMapper 严格三态 repair_required/no_blocking_repair/invalid_report——上游 agent/repair.py 对应物，报告协议校验+必需用例不降级+退出码一致+写集路径安全+来源 SHA-256 原始字节，human_review 恒 pending 不代签；**进 REGISTRY 的 `repair-map` 命令（L09 D1，L10 修复 Loop 前奏）**；复现/验收命令指向统一入口 CancelChecks，L10 起 Context.checksMainClass 参数化（L09 缺省零变化））
│   ├── loop/                      修复 Loop 控制器（L10：LoopRunner——上游 agent/loop.py 对应物，一轮=一次检查决策轮，固定决策序 有效性→达标→无进展→时间→Token→任务→执行；五停止条件 + 三预算不互换 + Token 软边界 + 缺正用量停核算；**上游四缺口全补**：executor 异常结构化 stopped_executor_error / 空报告拒绝不误判 converged / pending_verification+remaining_failures_source+两候选指纹分开 / 检查子进程独立超时（WORKBENCH_LOOP_SUITE_TIMEOUT 测试缝）；每轮任务复用 RepairMapper 严格版，dry-run 默认只生成任务零执行，patch 教学替身/claude 无头接线未真实调用；**进 REGISTRY 的 `loop-run` 命令（L10 D1）**；三轨迹：converged/stopped_no_progress/stopped_max_rounds+待复验+循环外审计单独目录）
│   ├── schedule/                  声明检查器纯核（L11：Schedule——上游 agent/schedule.py 对应物，Subtask 读写资源版本声明 + 路径规范化（反斜杠/casefold/通配符拒绝/根 "." 全匹配）+ 三交集判定（写写/写读双向，读读共享允许）+ resource: 前缀冲突 + input_version 统一；**无 CLI 面如实不造**——上游即纯库件唯 loop/graph 有 main，不进 REGISTRY，调用面 = write_sets_reject_conflict 登记项 + 合同测试；边界：只查申报路径不扫描真实行为——undeclared-read 假安全由测试断言与整合层承载）
│   ├── graph/                     显式状态图控制器（L12：GraphRunner——上游 agent/graph.py 对应物，**agent 家族第四件收齐（repair→loop→schedule→graph）**；REGISTRY `graph-run`：六状态机逐句对齐（develop→test→rework/human_review→awaiting_human_review→completed/stopped/failed）+ 等待点具名决定留痕（reviewer/decision/reviewed_at）+ 退出码 0/3/2（**3 = 正常等待不是失败**）+ `_result` 十字段状态文件同形 + `--suite-command` 子进程缝（LoopRunner 先例）+ RepairMapper 严格版复用（上下文按需装配，缺参显式 failed）；上游四缺口如实暴露不粉饰——move 不验边/空报告零阻断进等待/批准未绑定候选/姓名不认证身份，治理层（上游 workbench/workflow_graph.py）留后续讲次两层不混称；malformed/save 走顶层边界 rc 1）
│   ├── delivery/                  任务 API 与异步交付七件（L13，operate 首讲：TaskStore——三表同形/九态合法迁移表/提交键幂等 fingerprint/并发守卫 version 乐观锁/completed 双守卫/recover 恢复面，读写连接分面读不抢写锁；SpecFactory——六段模板逐字翻译+信号词路由+REQ 校验自动编号，parse-before-publish；Feedback——观察自动接受具名+签名去重；Workflow——四段链 prepare→start→execute→evaluate，绿报告只进 review 永不自动 completed，报告落盘+SHA-256；DeliveryAutomation——worker 线程+有界重试+失败自动沉淀+wait 软硬超时 version 续期，自动化停在人审前；HttpApi——com.sun.net.httpserver 四端点+415/400/404/409 错误映射+Idempotency-Key+verify-only（依赖白名单零新增）；DeliveryServe——**进 REGISTRY 的 `delivery-serve` 常驻服务（L13 D2，第五个工作台命令）**，--suite-command 子进程缝，缺省 suite=客户 purchase_requires_approval 原名照跑+normalizeReport 平铺→summary 包装；独立运行库 .runtime/course/L13-delivery/ 与 L01 信用账本不混库；**L14 增量两处**：DeliveryView——完整投影纯函数（九态归属表逐字 + freshness——review 人工等待不 stale + unknown 内联硬词面 + integrity 五类 + 列表八计数压缩汇总；每次调用实时构建不缓存——constructibility 点名坑的代码面否定）；HttpApi——views 列表路由 + view() 完整投影升级 + verify 仅复验路由 + WEB_ROOT 静态 serve（resolve 穿越守卫 + no-cache——面板数据源兑现））
│   ├── evolution/                 演进记录账（L15：EvolutionStore——上游 workbench/evolution.py 490 行逐句对应物；evolutions+evolution_events 两表 + 六状态机 proposed→approved→asset_changed→verified〔rejected/deferred 终态〕+ create 门「只有具名接受的反馈才能提升为进化记录」+ review 具名+理由 + assets 七类型去重 + **verify 重门**——候选≠源任务/共享 business_ref/晚于进化/completed+具名 approve/逐项 blocking/报告受控 reports 目录 sha256 重算 + learning/ 资产 **governed 分支**〔绑定+outcome passed+资产覆盖——检查前置化：事务内构造 LearningStore 同进程双连接 SQLITE_BUSY 互锁实测，只读检查移 BEGIN 前落账〕；**进 REGISTRY 的 `evolution` 一件五子命令（上游 argparse 单入口同形，--db→--runtime-dir 适配）** + 独立 main 双入口）
│   ├── learning/                  记忆系统两件（L15：LearningStore——**S01 冻结面 python-carrier-final:workbench/learning.py 770 行的 Java 对应物**〔Python 冻结面建成能力兑现，ADR-0006〕：五表 assets/events/recalls/bindings/runs + 七命令库逻辑——来源准入〔已验收任务或已接受反馈对应的 Evolution：source_task_id 一致+非 rejected/deferred+失败轨迹在场〕+ feedback 级联自动建走同一道闸门 + 治理〔HUMAN_FORBIDDEN 拒 ai/codex/system/claude/待确认/待定/tbd/pending/agent:*——S01 claude 增补更严；提炼者不得自审；publish 前必须 approve；同 family 旧 active 原子 superseded 不改写旧快照〕+ 召回〔项目隔离+仅 active+applies/excludes+可解释排序+预算 Top-k 超限 excluded 明示+conflict_key 分组「请逐项判断，不自动合并」+同源排除+检索不到如实为空；召回包整体落库〕+ BIND〔决定集逐项对应+plan/task 双唯一+采用资产全文快照〕+ 五相位复验〔不收自报哈希——task_events 引用自取现算；outcome=passed 须具名验收「复用验证缺少具名验收」〕+ 读时重算漂移阻断；**形态适配落账**——delivery 库无 project 列任务面项目校验省略、admission 轻判+evolution verify 重门分层、证据表=task_events；LearningCli——**进 REGISTRY 的 workbench-learn-* 七件**〔S01 argparse 参数逐字+--evolution-id/--feedback-id 增量〕；另 FeedbackCli 三子命令〔spec 偏差 +1，上游 feedback.py main 同形——经 REGISTRY `feedback`〕）
│   ├── coursecontracts/           CourseContracts.java 冻结合同 fixture（对照源 = tag python-carrier-final + 机检投影锁定）
│   └── tools/                     ImportEvidence（复刻 vendor import_evidence.py 语义，重算 SHA-256 验封）
├── src/test/java/                 合同测试 + golden 重放（testsupport/Cli 经真实 CLI 子进程驱动，不绕入口直调）
├── src/test/resources/golden/     对照基准 l01–l06 六套（场景清单见各 manifest.json）——Python 冻结版采出，永不手改
├── src/test/resources/coursecontracts/frozen-python-projection.json  Python 载体全字段投影（机检对照件）
├── pom.xml                        Maven + Java 21 + JUnit5/AssertJ；运行时依赖白名单：Jackson、sqlite-jdbc
├── bin/wb                         CLI 包装脚本（classpath = target/classes + build-classpath 落盘清单）
├── workbench_web/                 交付面板静态三件（L14：index/app/styles——六面取数闭集、零依赖零 CDN、无凭据；delivery-serve 直接 serve）
├── tools/                         capture_evidence.py（证据采集，标准库自足）+ golden 生成器 + 合同投影导出（执行需 tag checkout）
├── docs/
│   ├── adr/              复刻决策（0001 全新实现 / 0002 Claude 执行器 / 0003 检查点 / 0004 候选分支 / 0005 客户真理 / 0006 Java 重走）
│   ├── lessons/          每讲复刻讲义 + 技能编排表 + 重走移植注记（附录 A）
│   └── replication/      路线图与状态 + 每讲证据账（evidence/LNN.md；重走证据 LNN-java.md）
├── lesson-01-submission/ evidence.py 原始捕获（meta.json + output.txt，入库不移动）
├── lesson-02-submission/ L02 采集（Python 时代顶层 + java/ 子根，均入库不移动）
├── lesson-06-submission/ L06 采集（java/ 子根）
├── lesson-08-submission/ L08 采集（含 ci/ 子根：七 Run 元数据 + 五份 artifact 原件 + audit.py 复验对账脚本，永不移动）
├── lesson-09-submission/ L09 采集（业务链红/绿/观察 + 注入面 diff + 链补全 diff，永不移动）
├── lesson-10-submission/ L10 采集（起始红/树红/实现面 diff×2/绿×2——含 mvn 空输出被诚实护栏拒收件留盘，永不移动）
├── lesson-11-submission/ L11 采集（起始红/绿×2/实现面 diff/observations×9——受控整合与真实并行全链；07-parallel/ 两份子任务合同 + 声明检查 jsh + 甲乙原始报告，永不移动）
├── lesson-12-submission/ L12 采集（红/diff/绿×2/observations×5——对账实验与必红选跑留证全链；12-reconciliation/ 等待态+批准后状态文件原件 + 业务门报告 + 对账表，永不移动）
├── lesson-13-submission/ L13 采集（红/diff/绿 + observations×3——verify 首跑 rc 2/信用内核护栏拒绝实录/密封；13-api/ 受控 API 实验四拍原件 + attempt1 失败件（suite 形态分歧实录），永不移动）
├── lesson-14-submission/ L14 采集（红/diff×2/绿×2——含**扫描器自毒两 v1 件** + verify 观察（首跑缺 --execution-timeout rc 1 留盘）；14-web/ 两幕原件——面板四拍 + ERP 四向对账快照与驱动器；密封 meta+SHA 在库、output 118MB 超 GitHub 硬限本地封条完整，永不移动）
├── lesson-15-submission/ L15 采集（红/居间 diff/绿/密封四 record + 15-evolution/ 五拍原件十二件——含 **09a AI 名义 bind 被 human() 门拒现场**〔S01 claude 增补真实链路生效实证〕 + r1-upgrade/ R1 升级前后原件〔探针前红 invariant_ok=false → patch.diff 锚点单事务化 → 后绿 + 客户 eval 双照跑 + vendors 恒空〕；密封 meta+SHA 在库、output 275MB 超 GitHub 硬限本地封条完整，永不移动）
└── learning/             教学工作区（使命/课程/学习记录，服务"验收人读懂代码"）

（Python 载体已退役，ADR-0006 尾款 2026-09-30：workbench/ tests/ evals/ pyproject.toml 移出工作树，
 终态冻结于 tag python-carrier-final——对照/再生经 tag checkout；Python 冻结面时代建成的能力
 ——evals/ 评测与记忆系统已随各讲建成（记忆系统 = L15 `workbench/learning/`，S01 冻结面兑现）、工作台看板 Java 对应物待建设，进度见 docs/replication/README.md 各行）

vendors/CodexFDE          只读：课程合同、参考实现、讲义、L01 证据工具
vendors/flowERP           只读：客户项目，L04 起由工作台驱动（客户真理：其自身 eval 才是业务权威）
```

**结构约定**：

- 调用方向：命令入口（`workbench/cli.py`，REGISTRY 注册缝 + 顶层异常边界）→ 任务/证据逻辑（`workbench/bootstrap.py`）→ sqlite 存储（`.runtime/` 运行库；读命令不建库不建目录）。
- 新增命令：沿 REGISTRY 注册并复用既有存储入口；错误词面对齐上游（`required_task_missing` 等）。
- 测试口径：工作台行为类测试经 CLI 公开接口以真实子进程运行，不绕入口直调内部函数；纯文本与合同 fixture 类测试直接读文件即可（Java 线同口径：`testsupport/Cli` 子进程 / 文本直读）。
- 待建设清单（双轨口径）：Python 侧 `eval.harness` 已随 L05/L06 建成并冻结（`evals/`），Python 实现面随重走线完成进入退役窗口（ADR-0006 尾款，退役细则单独确认后执行）；Java 侧 eval 能力已齐——L05-java 两工具 + L06-java 统一运行器根两件与 l06 三件（`workbench/evals/`），本地护栏已随 L07 建成（`quality-gate` REGISTRY 命令 + `workbench/evals/l07/` 三件 + `hook_staging/` 投影，Stop hook 安装生效中），CI 远程复验已随 L08 建成（`ci-evidence` REGISTRY 命令 + `workbench/evals/l08/` 四件 + `.github/workflows/l08-eval.yml` + `CI_GATE_SPEC.md`，**阶段 4 质量链收官**），修复映射已随 L09 建成（`repair-map` REGISTRY 命令 + `workbench/repair/` + `workbench/evals/l09/` 三件，**阶段 5 首讲**——受控修复链走通：注入→红→草案→具名确认→恢复→绿），修复 Loop 已随 L10 建成（`loop-run` REGISTRY 命令 + `workbench/loop/` + `workbench/evals/l10/` 三件——十二控制模式机检 + 三轨迹受控实验：收敛/未收敛/末轮待复验+循环外审计）；声明检查器已随 L11 建成（`workbench/schedule/` 纯核无 CLI 面 + `workbench/evals/l11/` 三件——十模式/三交集机检 + 受控同版整合 + **真实并行双子代理**：合同先行 + 冲突声明被拒留痕 + Agent tool 甲乙活动时间重叠 + 写集回查 + 溯源三分类 R1–R5 移交 L12）；显式状态图已随 L12 建成（`graph-run` REGISTRY 命令 + `workbench/graph/` + `workbench/evals/l12/` 两件——十七+六控制模式机检 + 受控对账实验（等待 rc 3 → 具名批准 rc 0 + 同源机检）+ **R1–R6 对照账三分类**：R1/R4 运行实证必红留证、R2/R3/R5 引用不复现、R6 待验证——客户原样行为不写成复刻缺陷，**agent 家族四件收齐**）；任务 API 已随 L13 建成（`delivery-serve` REGISTRY 命令 + `workbench/delivery/` 七件 + `workbench/evals/l13/` 一件——202 受理/九态异步/幂等提交/全绿停 review/失败沉淀反馈，**operate 阶段首讲 + 检查点自建讲义首例**，范围以绑定 eval 断言面为界；**Web 面板已随 L14 建成**——DeliveryView 完整投影 + HttpApi 静态 serve 与 views/verify 路由 + workbench_web/ 三件 + WebPanelChecks 八件门（L13 因果交接兑现：capabilities/status_url/view_url 三查询面全部落地））；`course-status` 等上游命令待建设，上游 `workbench/workflow_graph.py` 治理层（允许边校验/expected_version/候选绑定）留后续讲次按检查点裁量（V0 受控执行三命令已随 L04-java 建成，`workbench/execution/`）——引用时如实说明尚未复刻，不写成已建成。
- 双轨纪律（重走期，ADR-0006；**重走线已完结，2026-09-30 起 Python 载体退役**）：重走期的「Python 实现面冻结不触碰」纪律随退役转译为——`workbench/`、`tests/`、`evals/`、`pyproject.toml` 不得在工作树复活（对照源 = tag `python-carrier-final`，机检护栏在 L02WorkbenchRulesTest）；`.runtime/course/L01-workbench/`（Python 时代账本）恒只读封存，Java 线账本用 `.runtime/course/L01-workbench-java/`；`tools/`（capture_evidence、golden 生成器、合同投影导出）与 `lesson-*-submission/`、`docs/` 照常保留——生成器执行需 tag checkout，如实说明。

**信用内核**（L01 钉死，后续讲次全部踩在上面）：账本只追加、失败不可抹、快照不随原文件变、`status` 每次重算 SHA-256 复核、链判定三规则（同命令红绿 / Diff 严格居间 / 全局最新须为成功绿）、`acceptance: pending_human_review` 恒待人签。改动任何一处都要意识到全链信用随之变动。

**换挡点**：L01–L03 由人直接监督 AI 建工作台；L04 起改为"通过工作台组织协同"——届时执行器合同（写集、预算、JSON 事件流、沙箱、退出码）按 ADR-0002 映射到 Claude Code 权限机制。
