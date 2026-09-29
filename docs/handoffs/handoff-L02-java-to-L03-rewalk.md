# 会话交接：L02 Java 重走已完成验收 → 下一任务 L03 重走

> 生成于 2026-09-29，交接到全新会话。本文件只做指针与状态，不复制既有工件内容——先读路径，再动手。

## 当前状态（事实）

- 分支 master @ `9c75359`，工作树干净（仅两个已裁定的未跟踪项，见"已闭合事项"）；**领先 origin 19 提交未 push**（用户 2026-09-29 表态继续留本地，后续会话可再问一句）
- **L02 Java 重走已具名验收并合并**（`d423e0a`，验收人 XianReallyHot-ZZH）：40/40 绿（31 既有 + L02 翻译件 5 + Java 线红点组 3 + golden l02 重放 1），N0/N1/边界/迁移四会话行为对照齐，golden l02 15 场景字节级一致（双跑指纹 `ed8aad6c…`），复查轮双轴闭环（S1–S4 修、P1–P4 处置在案）
- 重走线进度：L01 ✅ → L02 ✅ → **L03–L06 待逐讲推进**（不跨讲批量，每讲三触点），L07 顺延至重走完成（ADR-0006）
- 双轨健全：master 上 Python 全量 103 绿（冻结基线）+ Java 全量 40 绿

## 下一任务：L03 重走（仓库规则）

流程与 L01/L02 完全同形（每讲用户只有三个触点：①审定注记与 golden 方案 → ②显式调 `/mattpocock-skills:implement` → ③具名验收）。

**L03 与 L02 的关键差异（起草注记要回答的第一个问题）**：L03 是重走线**第一个有实质实现代码增量的讲**——Python 时代交付了 `workbench/spec.py` 六段式 Spec 解析器 + `workbench.cli` 的 `spec` 结构闸门（冻结合同 write_scope 含 `workbench/spec.py`、`workbench/templates/SPEC_TEMPLATE.md`）。L02 的"重建什么"难题（无代码增量）在 L03 不存在：Java 侧重走 = 把冻结的 `workbench/spec.py` 按原讲合同在 `src/main/java` 重新实现（L01-java 已建的同形载体直接踩），红点 = Java spec 命令能力缺失（天然起始红，同 L01 形态）。

**起草注记前必须先读**：

| 主题 | 路径 |
|---|---|
| 换语言决策与全部 Consequences | `docs/adr/0006-java-rewalk.md` |
| 重走模板（移植注记/golden 方案/人审增量的结构基准） | `docs/lessons/L01-工作台自举.md` 附录 A + `docs/lessons/L02-仓库规则.md` 附录 A（L02 版含"重建什么"分析范式与 C 表翻译法） |
| 重走证据账模板（G/R 段式、C 表、复查轮、验收行） | `docs/replication/evidence/L02-java.md`（最新先例：含 §5 复查轮 + §6 具名验收 + 未解决问题清单段） |
| L03 Python 口径包袱 | `docs/lessons/L03-*.md` 与 `docs/replication/evidence/L03.md`（先 `ls docs/lessons/` 取准确文件名） |
| 冻结的 Python 实现面（只读对照，逐字等价的对象） | `workbench/spec.py`、`workbench/templates/SPEC_TEMPLATE.md`、`workbench/cli.py` 的 spec 命令注册 |
| golden 生成器模式（l03 按其合同面另写；just-in-time，勿批量预生成） | `tools/generate_golden_l01.py` + `tools/generate_golden_l02.py`（l02 版含"审定表行按单命令粒度展开"的教训，见 L02-java §G） |
| golden 重放测试模式 | `src/test/java/workbench/testsupport/GoldenReplay.java`（l01/l02 已共享化，l03 只需新加一个 ReplayTest 委托类） |
| fixture 逐字等价机检模式 | `tools/export_course_contracts_json.py` + `src/test/resources/coursecontracts/frozen-python-projection.json`（L03 条目已在投影内，commit 1 即绿的就位类） |
| 词汇表（措辞必须继承） | `CONTEXT.md` |
| 铁律/结构约定/FlowERP 业务边界/双轨纪律 | 根 `CLAUDE.md`（L02 重走后已含 Java 双轨块与冻结纪律——注意它现在是新鲜的） |
| 重走线状态 | `docs/replication/README.md` 重走线行 |
| 上游现查 | 起草前 `git -C vendors/CodexFDE fetch` 现查（L02 起草时 `origin/main`=`7f67533` 纯文档加深、合同零变化、基线保持 `a74445a`；勿信本快照） |

L03 特有注意点（从 L02 经验外推，起草时核实）：
- `workbench/templates/SPEC_TEMPLATE.md` 在 write_scope 里——Java 侧落位（`src/main/resources/`?）要在注记里定并说清与 Python 冻结件的关系
- Python L03 证据账有其时代裁定（如 spec 拒绝词面口径），注记须逐条列"移植/不移植"
- golden l03 场景面 = spec 命令的结构闸门行为（合法 spec 六段通过 / 缺段拒 / 词面），从冻结 Python 采出；`mvn test` 链命令惯例不变
- Spec 拒绝路径的错误词面必须与 Python 逐字一致（L01-java A2 C3–C8 口径），golden 会锁死

## 流程纪律（新会话必守，全文见根 CLAUDE.md）

铁律 1–6 全部有效。特别提醒：`vendors/` 只读；待审核 ≠ 已接受、不代签不自行 merge；证据 = 命令+退出码+失败后权威状态（失败与无效捕获原样保留——L02-java 有错位封条保留工作树的先例）；用户显式技能到点必须停下提醒（配合点 1 复认、配合点 2 implement）；候选表/候选分支机制照 ADR-0004；换会话走本技能。

## Suggested skills（新会话按需调用）

- `to-spec`（**用户显式**；L03 讲义合同段到点提醒，未配置 issue tracker，降级=合同 fixture+讲义合同段，需用户明确同意——L02-java 已有降级复认先例，但每次都要重新问）
- `implement`（**用户显式**；触点 2 必须，未授权不得裸做）
- `tdd` / `code-review` / `diagnosing-bugs` / `codebase-design`（模型自调：红绿循环 / 验收前双轴复查 / 遇障诊断 / spec.py 的 Java 接口设计）
- `handoff`（下次换会话再走一次）

## 环境事实

- JBR Java 21.0.10（javac 同）+ Maven 3.9.6；Python 侧 `.venv`（3.13，anaconda 托管，L00 既记偏离）
- Java CLI 包装：`./bin/wb <子命令>`；测试命令 Java `mvn test`（**40 预期绿**，随讲次增长）；Python `-X utf8 -m unittest discover -s tests`（103 预期绿，冻结基线）
- 采集工具：`tools/capture_evidence.py --submission-root lesson-0N-submission/java <phase> -- <命令>`（**参数是完整相对路径**——L02 曾误读为子名产生错位封条）；导入用 `java -cp "target/classes:$(cat target/child-classpath.txt)" workbench.tools.ImportEvidence <meta.json> --runtime-dir … --task-id …`
- golden 工作流要点：掩码 `<TS>`/`<WORKBENCH_ID>`、`observed_at` 逐字保留、canonical JSON = `sort_keys + ensure_ascii=False + indent=2 + 换行`；生成器幂等双跑指纹一致入 §G；golden 前置落 master（L01/L02 先例），永不手改
- 任务账：Java 活账本 `.runtime/course/L01-workbench-java/`（沿用）；L03 任务号建议 `CASE-WB-L03-JAVA-001`（执行前向用户索取 owner/actor，不代填——L02 先例 XianReallyHot-ZZH）

## 已闭合事项（查询用，不需重开）

- L02-java 三项验收裁定：仓库根 `java/03-failure/…accdd5fa` 错位封条**保留工作树**（不删不移，output_file 绝对路径移动即断）；CLAUDE.md 四处事实性补记（adr 0006 / replication 行 / 测试口径行 / lesson-02-submission 树行）**追认**；`.idea/` **不入库**。全部记录在 `docs/replication/evidence/L02-java.md` §6
- L02-java 遗留待办（下下讲或工具整合讲次处理）：`.idea/` 与仓库根 `java/` 的 .gitignore 归置——非 L03 写集，勿顺手做
- L01-java 的两条验收裁定与已知行为边界（parseAware 更严/now() 无微秒）——见 `docs/replication/evidence/L01-java.md` 偏差与采纳记录；L03 golden 若触达时间解析，显式采纳口径
- N0/N1 会话模型先例：`glm-5.3-flash[1M]`（用户两次课都选它；L03 若有 N0/N1 类行为对照仍需执行前确认）
