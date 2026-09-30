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
│   ├── evals/                     统一运行器根两件（L06-java：EvalHarness 分级/报告/x 模式/退出码 + ReportContract 报告合同——evals/harness.py 与 report_contract.py 对应物，L07 Hook 入口，报告 schema 1.0 对齐客户）+ 评测工具子包 l05 两件（缺陷基线构造器+收货场景驱动）与 l06 三件（CSV 缺陷基线+口径交叉检查+五登记项收口 Checks）——独立 main 不进 REGISTRY，经子进程 python 驱动客户实现（冻结件 in-process import 的结构翻译点），对外契约逐字同形由 golden l05/l06 锁
│   ├── coursecontracts/           CourseContracts.java 冻结合同 fixture（对照源 = tag python-carrier-final + 机检投影锁定）
│   └── tools/                     ImportEvidence（复刻 vendor import_evidence.py 语义，重算 SHA-256 验封）
├── src/test/java/                 合同测试 + golden 重放（testsupport/Cli 经真实 CLI 子进程驱动，不绕入口直调）
├── src/test/resources/golden/     对照基准 l01–l06 六套（场景清单见各 manifest.json）——Python 冻结版采出，永不手改
├── src/test/resources/coursecontracts/frozen-python-projection.json  Python 载体全字段投影（机检对照件）
├── pom.xml                        Maven + Java 21 + JUnit5/AssertJ；运行时依赖白名单：Jackson、sqlite-jdbc
├── bin/wb                         CLI 包装脚本（classpath = target/classes + build-classpath 落盘清单）
├── tools/                         capture_evidence.py（证据采集，标准库自足）+ golden 生成器 + 合同投影导出（执行需 tag checkout）
├── docs/
│   ├── adr/              复刻决策（0001 全新实现 / 0002 Claude 执行器 / 0003 检查点 / 0004 候选分支 / 0005 客户真理 / 0006 Java 重走）
│   ├── lessons/          每讲复刻讲义 + 技能编排表 + 重走移植注记（附录 A）
│   └── replication/      路线图与状态 + 每讲证据账（evidence/LNN.md；重走证据 LNN-java.md）
├── lesson-01-submission/ evidence.py 原始捕获（meta.json + output.txt，入库不移动）
├── lesson-02-submission/ L02 采集（Python 时代顶层 + java/ 子根，均入库不移动）
├── lesson-06-submission/ L06 采集（java/ 子根）
└── learning/             教学工作区（使命/课程/学习记录，服务"验收人读懂代码"）

（Python 载体已退役，ADR-0006 尾款 2026-09-30：workbench/ tests/ evals/ pyproject.toml 移出工作树，
 终态冻结于 tag python-carrier-final——对照/再生经 tag checkout；Python 冻结面时代建成的能力
 ——evals/ 评测、记忆系统、工作台看板——的 Java 对应物进度见 docs/replication/README.md 各行）

vendors/CodexFDE          只读：课程合同、参考实现、讲义、L01 证据工具
vendors/flowERP           只读：客户项目，L04 起由工作台驱动（客户真理：其自身 eval 才是业务权威）
```

**结构约定**：

- 调用方向：命令入口（`workbench/cli.py`，REGISTRY 注册缝 + 顶层异常边界）→ 任务/证据逻辑（`workbench/bootstrap.py`）→ sqlite 存储（`.runtime/` 运行库；读命令不建库不建目录）。
- 新增命令：沿 REGISTRY 注册并复用既有存储入口；错误词面对齐上游（`required_task_missing` 等）。
- 测试口径：工作台行为类测试经 CLI 公开接口以真实子进程运行，不绕入口直调内部函数；纯文本与合同 fixture 类测试直接读文件即可（Java 线同口径：`testsupport/Cli` 子进程 / 文本直读）。
- 待建设清单（双轨口径）：Python 侧 `eval.harness` 已随 L05/L06 建成并冻结（`evals/`），Python 实现面随重走线完成进入退役窗口（ADR-0006 尾款，退役细则单独确认后执行）；Java 侧 eval 能力已齐——L05-java 两工具 + L06-java 统一运行器根两件与 l06 三件（`workbench/evals/`），`course-status` 等上游命令待建设（V0 受控执行三命令已随 L04-java 建成，`workbench/execution/`）——引用时如实说明尚未复刻，不写成已建成。
- 双轨纪律（重走期，ADR-0006；**重走线已完结，2026-09-30 起 Python 载体退役**）：重走期的「Python 实现面冻结不触碰」纪律随退役转译为——`workbench/`、`tests/`、`evals/`、`pyproject.toml` 不得在工作树复活（对照源 = tag `python-carrier-final`，机检护栏在 L02WorkbenchRulesTest）；`.runtime/course/L01-workbench/`（Python 时代账本）恒只读封存，Java 线账本用 `.runtime/course/L01-workbench-java/`；`tools/`（capture_evidence、golden 生成器、合同投影导出）与 `lesson-*-submission/`、`docs/` 照常保留——生成器执行需 tag checkout，如实说明。

**信用内核**（L01 钉死，后续讲次全部踩在上面）：账本只追加、失败不可抹、快照不随原文件变、`status` 每次重算 SHA-256 复核、链判定三规则（同命令红绿 / Diff 严格居间 / 全局最新须为成功绿）、`acceptance: pending_human_review` 恒待人签。改动任何一处都要意识到全链信用随之变动。

**换挡点**：L01–L03 由人直接监督 AI 建工作台；L04 起改为"通过工作台组织协同"——届时执行器合同（写集、预算、JSON 事件流、沙箱、退出码）按 ADR-0002 映射到 Claude Code 权限机制。
