# 会话交接：L05 Java 重走已完成验收 → 下一任务 L06 重走

> 生成于 2026-09-30，交接到全新会话。本文件只做指针与状态，不复制既有工件内容——先读路径，再动手。

## 当前状态（事实）

- 分支 master @ `367780a`（L05 收口提交），工作树仅一处**已裁定未跟踪项**：仓库根 `java/`（L02-java §6 裁定，勿动勿入库）；**领先 origin 9 提交未 push**（push 决定权在用户，每次可问一句，不催）
- **L05 Java 重走已具名验收并合并**（`0b5db7b`，验收人 XianReallyHot-ZZH，2026-09-30，触点 3 批复「没问题，继续」）：`mvn test` **91/91 绿**（84 既有 + L05 合同 6 + golden l05 重放 1）；golden l05 **7 场景字节级对照**（双跑指纹 `3d9b623c654bff89b88d45a716768a506812db46b176e9edf2c8c77977c8e576`，**零新掩码**）；L05 两工具建成 `workbench/evals/l05/`（`BuildDefectBaseline`/`FlowerpProbe`/`ReceivingScenarioCheck`——独立 main 不进 REGISTRY，**子进程 python 驱动缝**（冻结件 in-process import 的结构翻译点），预期计算与断言留 Java）；证据链全量重演（eval 两连红→diff→冻结→绿同命令链 + 观察六枚 + 任务账 14 条证据 `evidence_complete:true` + C5 收口复核逐字节一致），**Java 建树与 Python 时代指纹互证**（基线 service.py `1a2f303c…` / 候选 `6c372dcd…`）；复查轮 S-1–S-3 修（文档侧**零代码改动**）/ T-1–T-7 搁置澄清；证据账 `docs/replication/evidence/L05-java.md`（§G 前置 + §R 候选 + §10 结论表 + §11 台账 + §12 口径 + §13 复查轮 + 具名验收行）
- 重走线进度：L01 ✅ → L02 ✅ → L03 ✅ → L04 ✅ → L05 ✅ → **L06 待逐讲推进**（重走线最后一讲；完成后 Python 载体 tag 退役，ADR-0006）
- 双轨健全：Python 全量 103 绿（冻结基线，L05 全程冻结面 **0 字节**触碰）+ Java 全量 91 绿；vendors 双查恒空

## 下一任务：L06 重走（仓库规则）

流程与 L01–L05 完全同形（每讲用户三个触点：①审定移植注记与 golden 方案 → ②显式调 `/mattpocock-skills:implement` → ③具名验收）。

**L06 的关键差异（起草注记要回答的第一个问题）**：L06 = 统一 Harness——Python 时代交付 `evals/harness.py` + `evals/report_contract.py`（统一运行器 + 报告合同，L07 Hook 入口，报告 schema 1.0 对齐客户为 L08「同一 Eval 身份」铺垫）+ `evals/l06/`（口径检查驱动 + CSV 缺陷基线构造 + 假绿探针）。重走侧必须先答：① **这些全是 `evals/` 冻结面，Java 线零触碰**——Java 侧统一运行器正是 CLAUDE.md 待建设清单的 L06-java 正题（L05 份额已建成），"重走 L06" = 在 Java 线**建成** harness + 报告合同对应物；注记须划清 golden 适用面（冻结 Python `evals/harness.py`/`report_contract.py` 的 stdout/退出码/报告文件有真实冻结输出可对照——golden 场景面延续 L05 开出的「工具 stdout + main 字段」先例，还是含报告文件对照，起草时定；报告含 `generated_at`/`duration_ms` → 掩码按 manifest 声明，l04 `<TS>` 先例）；② 客户真理现查（`vendors/flowERP/eval/harness.py` 的 `run_suite` 本身就是统一 harness、schema 1.0——客户 eval 照跑不重写不删弱）；③ **假绿辨别力探针**（上游教学缺陷：`blocking_failed` 恒 0 的运行器变体上合同套件红）与教学链次序（D5：起始红→合同绿→假绿探针→可信红→修业务一行→全绿→迁移）在 Java 线真实重演不补拍；④ L06 有本仓库 Python 时代执行期裁决（D1–D6 + R1 驱动快照修正，见其证据账 §0/§5）——移植表逐条过。

**起草注记前必须先读**：

| 主题 | 路径 |
|---|---|
| 换语言决策与全部 Consequences | `docs/adr/0006-java-rewalk.md` |
| 重走模板（最演进版） | `docs/lessons/L05-失败优先Eval.md` 附录 A（**最新先例**：工具面 golden、修正注机制、C5 冻结叙事下的复查轮零代码改动裁定）+ `L04-受控执行.md` 附录 A |
| 重走证据账模板（最新先例） | `docs/replication/evidence/L05-java.md`（§G/§R/§10–§13 全形态） |
| L06 Python 口径包袱 | `docs/lessons/L06-Harness分级判决.md` 与 `docs/replication/evidence/L06.md`（两级裁决与假绿探针实证在案） |
| 冻结的 Python 实现面（只读对照） | `evals/harness.py`、`evals/report_contract.py`、`evals/l06/`（build_defect_baseline / checks / stock_consistency_check）、`workbench/cli.py` 注册段、`tests/test_l06_eval_harness.py`（合同测试九项语义的对照源） |
| 客户项目（只读） | `vendors/flowERP`（其 `eval/harness.py` `run_suite` 是业务权威——客户真理；现查勿信快照） |
| golden 生成器模式（最新） | `tools/generate_golden_l05.py`（工具 stdout 面 + 场景 main 字段 + 盲区内联手术）；l06 按其合同面另写，just-in-time 勿批量预生成 |
| golden 重放支撑 | `src/test/java/workbench/testsupport/GoldenReplay.java`（main 字段已数据驱动 + setup 块 + 空 scratch 清单语义）、`GoldenL05ReplayTest`（自行清场 + Java 构造器物化夹具先例） |
| 子进程驱动缝先例 | `src/main/java/workbench/evals/l05/FlowerpProbe.java`（`--python` 默认 `.venv/bin/python` 相对 cwd、内部绝对化；单方法缝设计） |
| fixture 逐字等价机检 | `tools/export_course_contracts_json.py` + `frozen-python-projection.json`（L06 条目已在投影内） |
| 词汇表 / 铁律 / 双轨纪律 | `CONTEXT.md` / 根 `CLAUDE.md`（Java 面已含 evals 包行与待建设清单最新口径） |
| 重走线状态 | `docs/replication/README.md` 重走线行 |
| 上游现查 | 起草前 `git -C vendors/CodexFDE fetch` 现查（L05 起草时 `origin/main`=`7f67533` = 基线；勿信本快照） |

L06 特有注意点（从 L05 经验外推，起草时核实）：
- `evals/` 冻结面零触碰硬检查入人审清单（`git diff master...lesson-06-java -- evals workbench tests pyproject.toml tools/capture_evidence.py` 为空）
- **C5 冻结叙事裁定承袭**：已冻工具源的打磨在复查轮不采纳（L05 §13 T-1/T-6 搁置检查点）——L06 建成 harness 后同样适用；冻结时序 = 红绿链内初冻 + 收口复核
- golden 共享件已就位（main 字段 + `Cli.runMain` + `deleteRecursively` public），预期零共享件改动；若需扩展须行为零变化 + 五套旧重放回归证据
- 报告文件对照（若有）：`generated_at`/`duration_ms` 不可复现 → manifest 声明掩码；报告 x 模式不覆盖旧证据（Python 合同语义）
- 任务号建议 `CASE-WB-L06-JAVA-001`（owner/actor 执行前向用户索取，不代填；先例 owner=XianReallyHot-ZZH、verify actor=Claude）；采集 `--submission-root lesson-06-submission/java`（完整相对路径）；导入用 Java `ImportEvidence`；**链入账口径沿 JD7 先例**（001 装本讲合同对应的同命令链；mvn 工具建设链封条随分支 commit 不入账）
- L05 现场勿混用（`.runtime/course/L05-*-java/` 已占用）；L06 现场用新根 `.runtime/course/L06-*-java/`
- **退出码采集纪律（L05 J2 教训）**：命令勿用管道尾部判 rc（zsh `PIPESTATUS` 无效），重定向落日志 + `echo $?`；失败现场一律保留
- 秒级时间戳漂移面（l04 G4 教训）：golden 场景涉及任务/报告排序时，编号按创建序字典序；报告内 `generated_at` 一律掩码

## 流程纪律（新会话必守，全文见根 CLAUDE.md）

铁律 1–6 全部有效。特别提醒：`vendors/` 只读；待审核 ≠ 已接受、不代签不自行 merge；证据 = 命令+退出码+失败后权威状态（失败与错位封条原样保留）；用户显式技能到点必须停下提醒（配合点 1 复认、配合点 2 implement）；候选分支机制照 ADR-0004；`git add` 用精确路径，**不用 `add -A`**（会把已裁定未跟踪的仓库根 `java/` 误暂存）；源码里字面不可见 Unicode 一律显式转义（可见 CJK 字面有 L04/L05 先例背书）；复查轮处置原则 = 行为零变化修正 + 披露补记 + 旧证据全保留（已冻工具源打磨不采纳，走检查点）。

## Suggested skills（新会话按需调用）

- `to-spec`（**用户显式**；L06 讲义合同段到点提醒，降级需每次重新确认——L02/L03/L04-java/L05-java 均有降级复认先例）
- `implement`（**用户显式**；触点 2 必须，未授权不得裸做）
- `tdd` / `code-review` / `diagnosing-bugs` / `codebase-design`（模型自调：红绿循环 / 验收前双轴复查 / 遇障诊断 / 接口设计——L05 同位先例见其会话与证据账 §13）
- `handoff`（下次换会话再走一次，落 `docs/handoffs/`）

## 环境事实

- JBR Java 21.0.10（javac 同）+ Maven 3.9.6；Python 侧 `.venv`（3.13，anaconda 托管，L00 既记偏离）
- Java CLI 包装 `./bin/wb <子命令>`；测试命令 Java `mvn test`（**91 预期绿**，随讲次增长）；Python `-X utf8 -m unittest discover -s tests`（103 预期绿，冻结基线）
- 采集：`tools/capture_evidence.py --submission-root <完整相对路径> <phase> -- <命令>`；导入：`java -cp "target/classes:$(cat target/child-classpath.txt)" workbench.tools.ImportEvidence <meta.json> --runtime-dir .runtime/course/L01-workbench-java --task-id …`
- Java 工具直跑（不进 REGISTRY）：`java -cp "target/classes:$(cat target/child-classpath.txt)" workbench.evals.l05.<Main> …`（L06 对应物同法）
- golden 工作流要点：场景可选 `main` 字段（默认 CLI 入口）、canonical JSON、幂等双跑指纹一致入 §G（**首跑即留**）、golden 前置落 master 永不手改、manifest 必须保留 normalization 块（重放测试数据驱动断言）
- L05 交付物落位（查询用）：`lesson-05-submission/java/`（全封条）、`src/main/java/workbench/evals/l05/`、`src/test/resources/golden/l05/`、`docs/replication/evidence/L05-java.md`、任务账 `CASE-WB-L05-JAVA-001`（accepted，14 条证据）

## 已闭合事项（查询用，不需重开）

- L05-java 裁定与记录：附录 A 全案（A6.1 移植 + A6.2 JD1–JD8 + 修正注①②③）+ 复查轮 §13（S-1–S-3 修 / T-1–T-7 搁置）——全部随验收闭合
- 前置期两次真实失败（J2 重载歧义编译失败 / J4 跨包可见度）与驱动盲区断言首跑失败现场——均在 §G/§R/commit message 留痕，保留不删
- L04-java 及更早验收裁定：见各证据账与 `handoff-L04-java-to-L05-rewalk.md` 已闭合节
- `.idea/` 与仓库根 `java/` 的 .gitignore 归置——L02-java 遗留待办，继续留后续讲次或工具整合讲次处理，非 L06 写集
- master 领先 origin 的 push 决定权在用户（现领先 9，每次可问一句，不催）
