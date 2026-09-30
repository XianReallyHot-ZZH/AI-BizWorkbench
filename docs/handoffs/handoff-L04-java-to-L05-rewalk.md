# 会话交接：L04 Java 重走已完成验收 → 下一任务 L05 重走

> 生成于 2026-09-30，交接到全新会话。本文件只做指针与状态，不复制既有工件内容——先读路径，再动手。

## 当前状态（事实）

- 分支 master @ `b426315`（L04 收口提交），工作树仅一处**已裁定未跟踪项**：仓库根 `java/`（L02-java §6 裁定，勿动勿入库；`.idea/` 已 gitignore）；**领先 origin 9 提交未 push**（上会话末曾为 27+，用户其间推过——push 决定权在用户，每次可问一句，不催）
- **L04 Java 重走已具名验收并合并**（`85155c3`，验收人 XianReallyHot-ZZH，2026-09-30）：`mvn test` **84/84 绿**（59 既有 + L04 合同 24 + golden l04 重放），golden l04 **47 场景字节级对照**（双跑指纹 `5b7ebbcdb44ca977…`，重放测试 commit 1 红 = 预期红点），V0 受控执行三命令建成（execution 包五件，词面与检查序与冻结 execution.py 逐位同形），B 段由 Java V0 真实组织 `claude -p` 交付（probe 六场景 N0+终验双 rc 0、eval V2 权威账对照 rc 0、实测写集无越界、事件流 26,991 行落盘），A/B 双任务账四词面密封 + A-before-B 时序机器可证，复查轮 S-1–S-5 修 / S-6–S-7 澄清（S-6 bootstrap 三件加法扩展随验收补认）；证据账 `docs/replication/evidence/L04-java.md`（§G/R/§10 结论表/§11 台账/§12 复查轮全）
- 重走线进度：L01 ✅ → L02 ✅ → L03 ✅ → L04 ✅ → **L05–L06 待逐讲推进**（不跨讲批量，每讲三触点），L07 顺延（ADR-0006）
- 双轨健全：master 上 Python 全量 103 绿（冻结基线，merge 零触碰）+ Java 全量 84 绿；vendors 双查恒空

## 下一任务：L05 重走（仓库规则）

流程与 L01–L04 完全同形（每讲用户三个触点：①审定移植注记与 golden 方案 → ②显式调 `/mattpocock-skills:implement` → ③具名验收）。

**L05 的关键差异（起草注记要回答的第一个问题）**：L05 = 失败优先 Eval——Python 时代交付 `evals/` 评测目录（**已冻结为对照基准的一部分**：`eval.harness` 随 L05/L06 建成并冻结，双轨口径见 CLAUDE.md 待建设清单）+ 缺陷基线两连红 + 同命令绿 + 冻结指纹 + 盲区留痕（eval 用例身份 `receiving_is_idempotent`，客户真理现查后增量重定位为辨别力证明）。重走侧必须先答：① **`evals/` 是 Python 冻结面，Java 线不能触碰**——Java 侧 eval.harness 正是 L05+ 待建设件，"重走 L05"意味着在 Java 线**建成** harness 而非对照重放，注记须把"golden 字节对照"在 L05 的适用范围划清（哪些场景有冻结 Python 输出可对照、哪些是 Java 新建面）；② 客户真理现查纪律（flowERP 自身 19 项 blocking eval 才是业务权威，照抄隔离机制）；③ 缺陷基线的"先红后修"叙事在 Java 线如何真实重演（不补拍不造红）。

**起草注记前必须先读**：

| 主题 | 路径 |
|---|---|
| 换语言决策与全部 Consequences | `docs/adr/0006-java-rewalk.md` |
| 重走模板（最演进版） | `docs/lessons/L04-受控执行.md` 附录 A（golden 掩码按 manifest 数据驱动 + setup 块候选仓 + errno/argparse 不入 golden 的 JD6 体例）+ `L03` 附录 A |
| 重走证据账模板（最新先例） | `docs/replication/evidence/L04-java.md`（§10 C 编号逐项结论表 + §11 命令台账 + §12 复查轮体例——S-5 教训：结论表是 C14 硬要求，起草时就按此形态写） |
| L05 Python 口径包袱 | `docs/lessons/L05-失败优先Eval.md` 与 `docs/replication/evidence/L05.md`（两级裁决记录与探针实证在案） |
| 冻结的 Python 实现面（只读对照） | `evals/`（评测目录）、`workbench/execution.py`（Java 同形对照件）、`workbench/cli.py` 注册段 |
| 客户项目（只读） | `vendors/flowERP`（其自身 eval 才是业务权威——客户真理；`vendors/flowERP/eval/` 现查） |
| golden 生成器模式 | `tools/generate_golden_l04.py`（最新：掩码块扩展 + setup 块 + 任务编号按创建序字典序命名）；l05 按其合同面另写，just-in-time 勿批量预生成 |
| golden 重放测试模式 | `src/test/java/workbench/testsupport/GoldenReplay.java`（**现已数据驱动**：掩码按 manifest normalization 声明 + setup 块；l05 若需再扩展须行为零变化 + 三套旧重放回归证据） |
| fixture 逐字等价机检 | `tools/export_course_contracts_json.py` + `frozen-python-projection.json`（L05 条目已在投影内） |
| 词汇表 / 铁律 / 双轨纪律 | `CONTEXT.md` / 根 `CLAUDE.md` |
| 重走线状态 | `docs/replication/README.md` 重走线行 |
| 上游现查 | 起草前 `git -C vendors/CodexFDE fetch` 现查（L04 起草时 `origin/main`=`7f67533` = 本讲基线；勿信本快照） |

L05 特有注意点（从 L04 经验外推，起草时核实）：
- `evals/` 冻结面零触碰要进人审清单硬检查（`git diff master...lesson-05-java -- evals workbench tests pyproject.toml tools/capture_evidence.py` 为空）
- **`_now()` 秒级精度教训（L04 golden 前置期实测）**：账面投影按 `(created_at, task_id)` 排序，同秒组组成随跑随机——**任务编号必须按创建序字典序命名**（`-01`…`-NN`），否则 golden/账面跨跑漂移（根因与修复见 `evidence/L04-java.md` §G G4）
- golden 首跑即留指纹（L03 漏留、L04 已执行）；errno/argparse/真实 claude 词面不入 golden（JD6）；掩码口径分歧逐字写 manifest mask_note
- eval/执行器命令跨 cwd 一律绝对路径（L04 Python 时代首跑丢记录教训，移植为起始纪律）
- Python 时代 L05 有 N0/N1 类行为对照会话则模型/权限参数执行前向用户确认（L04 B 段先例：默认模型 + bypassPermissions，stderr 有 unrecognized_model 别名回退留痕属已知现象，如实记录）
- 任务账：Java 活账本 `.runtime/course/L01-workbench-java/`（沿用）；任务号建议 `CASE-WB-L05-JAVA-001`（owner/actor 执行前向用户索取，不代填）；采集 `--submission-root lesson-05-submission/java`（完整相对路径）；导入用 Java `ImportEvidence`
- L04 时代 B 段运行现场在 `.runtime/course/L04-delivery-java/`（候选 clone + 事件流），Python 时代 L05 现场在 `.runtime/course/L05-*`——Java L05 现场用新根，勿混用

## 流程纪律（新会话必守，全文见根 CLAUDE.md）

铁律 1–6 全部有效。特别提醒：`vendors/` 只读；待审核 ≠ 已接受、不代签不自行 merge；证据 = 命令+退出码+失败后权威状态（失败与错位封条原样保留）；用户显式技能到点必须停下提醒（配合点 1 复认、配合点 2 implement）；候选分支机制照 ADR-0004；`git add` 用精确路径，**不用 `add -A`**（会把已裁定未跟踪的仓库根 `java/` 误暂存）；源码与文档里的字面不可见 Unicode 一律显式转义（L03 S-1 教训）；复查轮处置原则 = 行为零变化修正 + 披露补记 + 旧证据全保留。

## Suggested skills（新会话按需调用）

- `to-spec`（**用户显式**；L05 讲义合同段到点提醒，降级需每次重新确认——L02/L03/L04-java 均有降级复认先例）
- `implement`（**用户显式**；触点 2 必须，未授权不得裸做）
- `tdd` / `code-review` / `diagnosing-bugs` / `codebase-design`（模型自调：红绿循环 / 验收前双轴复查 / 遇障诊断 / 接口设计——L04 同位先例见其会话）
- `handoff`（下次换会话再走一次）

## 环境事实

- JBR Java 21.0.10（javac 同）+ Maven 3.9.6；Python 侧 `.venv`（3.13，anaconda 托管，L00 既记偏离）
- Java CLI 包装：`./bin/wb <子命令>`；测试命令 Java `mvn test`（**84 预期绿**，随讲次增长）；Python `-X utf8 -m unittest discover -s tests`（103 预期绿，冻结基线）
- 采集工具：`tools/capture_evidence.py --submission-root <完整相对路径> <phase> -- <命令>`；导入：`java -cp "target/classes:$(cat target/child-classpath.txt)" workbench.tools.ImportEvidence <meta.json> --runtime-dir .runtime/course/L01-workbench-java --task-id …`
- golden 工作流要点：掩码 `<TS>`/`<WORKBENCH_ID>`（+按 manifest 声明可扩展）、canonical JSON、幂等双跑指纹一致入 §G（**首跑即留**）、golden 前置落 master 永不手改、manifest 必须保留 normalization 块（GoldenReplay 数据驱动断言）
- L04 交付物落位（查询用）：`lesson-04-submission/java/`（五工件 java 子根 + probe 报告 + 封条）、`src/main/java/workbench/execution/`、`src/test/resources/golden/l04/`、`docs/replication/evidence/L04-java.md`

## 已闭合事项（查询用，不需重开）

- L04-java 裁定与记录：附录 A6 全案（A6.1 移植 + JD1–JD8）+ 复查轮 §12（S-1/S-3/S-4 修，S-2/S-7 澄清，S-5 表格补齐，S-6 补认）——全部随验收闭合
- L03-java 及更早验收裁定：见各证据账 §5/§6 与 `handoff-L03-java-to-L04-rewalk.md` 已闭合节
- `.idea/` 与仓库根 `java/` 的 .gitignore 归置——L02-java 遗留待办，继续留后续讲次或工具整合讲次处理，非 L05 写集
- master 领先 origin 的 push 决定权在用户（现领先 9，每次可问一句，不催）
