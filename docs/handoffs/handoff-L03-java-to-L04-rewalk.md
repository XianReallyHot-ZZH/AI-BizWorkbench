# 会话交接：L03 Java 重走已完成验收 → 下一任务 L04 重走

> 生成于 2026-09-29，交接到全新会话。本文件只做指针与状态，不复制既有工件内容——先读路径，再动手。

## 当前状态（事实）

- 分支 master @ `cae71d4`（handoff 提交），工作树有一处**非本会话来源的未提交改动**：`.gitignore` 被追加 `/.idea/` 一行（来源待用户确认；系遗留待办的一半——仓库根 `java/` 规则仍未加）；另有两个已裁定的未跟踪项：`.idea/`、仓库根 `java/`（L02-java §6 裁定，勿动勿入库）；**领先 origin 27 提交未 push**（用户既定留本地，后续会话可再问一句）
- **L03 Java 重走已具名验收并合并**（`d6c05d2`，验收人 XianReallyHot-ZZH）：59/59 绿（40 既有 + L03 合同 18 + U+2028 行界用例 1），六类拒绝词面与围栏语义逐字同形，golden l03 11 场景字节级一致（双跑指纹 `f5c7a611…`），三份输入复验三态齐 + SHA 只读对照，模板双载体机检锁定，复查轮 S-1–S-5 修 / S-6–S-7 澄清在案；证据账 `docs/replication/evidence/L03-java.md`（§G/R/C 表/§5/§6 全）
- 重走线进度：L01 ✅ → L02 ✅ → L03 ✅ → **L04–L06 待逐讲推进**（不跨讲批量，每讲三触点），L07 顺延（ADR-0006）
- 双轨健全：master 上 Python 全量 103 绿（冻结基线，merge 零触碰）+ Java 全量 59 绿

## 下一任务：L04 重走（仓库规则）

流程与 L01–L03 完全同形（每讲用户三个触点：①审定注记与 golden 方案 → ②显式调 `/mattpocock-skills:implement` → ③具名验收）。

**L04 的关键差异（起草注记要回答的第一个问题）**：L04 是换挡点讲（"委托 Codex 执行一次最小变更"，stage=build）——Python 时代交付 `workbench/execution.py` 受控执行三命令（cli.py 注册缝注释可见落点）+ **首次由工作台驱动 flowERP**（客户真理，ADR-0005）。重走侧两大问题必须先答：① 执行器合同（写集、预算、JSON 事件流、沙箱、退出码，ADR-0002）如何映射到 Claude Code 权限机制；② Java 线驱动真实 `vendors/flowERP` 的方式（子进程 + JSON 报告 + 退出码，语言无关）。另有两笔 L03 尾巴到期：D4（根 `FDE_SPEC.md` 待 L04 按真实需求建立）与 D5（`build_delivery_spec` 需求生成机器 L04 按合同再定）。

**起草注记前必须先读**：

| 主题 | 路径 |
|---|---|
| 换语言决策与全部 Consequences | `docs/adr/0006-java-rewalk.md` |
| 换挡点与执行器合同 | 根 `CLAUDE.md` 末段（"换挡点"）+ `docs/adr/0002-*`（Claude 执行器）+ `docs/adr/0005-*`（客户真理） |
| 重走模板（最演进版） | `docs/lessons/L03-可验收Spec.md` 附录 A（含 golden 红点参与者形态）+ `L01/L02` 附录 A |
| 重走证据账模板 | `docs/replication/evidence/L03-java.md`（最新先例：§5 复查轮含 S-1 实测分歧 test-first 修复体例） |
| L04 Python 口径包袱 | `docs/lessons/L04-受控执行.md` 与 `docs/replication/evidence/L04.md`（先 `ls docs/lessons/` 核对文件名） |
| 冻结的 Python 实现面（只读对照） | `workbench/execution.py`、`workbench/cli.py` 的 execution 注册段、flowERP 驱动缝 |
| 客户项目（只读，L04 才由工作台首次驱动） | `vendors/flowERP`（其自身 eval 才是业务权威——客户真理） |
| golden 生成器模式 | `tools/generate_golden_l03.py`（最新；l04 按其合同面另写，just-in-time 勿批量预生成） |
| golden 重放测试模式 | `src/test/java/workbench/testsupport/GoldenReplay.java`（l03 零共享件改动先例） |
| fixture 逐字等价机检 | `tools/export_course_contracts_json.py` + `frozen-python-projection.json`（L04 条目已在投影内） |
| 词汇表 / 铁律 / 双轨纪律 | `CONTEXT.md` / 根 `CLAUDE.md` |
| 重走线状态 | `docs/replication/README.md` 重走线行 |
| 上游现查 | 起草前 `git -C vendors/CodexFDE fetch` 现查（L03 起草时 `origin/main`=`7f67533` = 本讲基线；勿信本快照） |

L04 特有注意点（从 L03 经验外推，起草时核实）：
- flowERP 是 submodule（只读铁律照旧）——工作台"驱动"它 = 子进程调用其 eval/命令，不写回；`git -C vendors/flowERP status` 恒空要与"驱动产生的运行产物落点"区分清楚（起草时查 Python 时代 L04 证据账怎么处理的）
- Python L04 证据账的时代裁定逐条列"移植/不移植"（L03 附录 A6 体例）
- golden l04 场景面 = 受控执行三命令的行为语义，从冻结 Python 采出；错误词面与退出码逐字同形（L01-java/L03 两轮先例）
- **正则/Unicode 教训（L03 复查轮 S-1）**：Java `Pattern.MULTILINE` 行界宽于 Python `re.MULTILINE`（\u0085/\u2028/\u2029）——凡翻译含正则的行为，先比对两侧行界/语义再动笔；源码与文档里的字面 Unicode 字符一律显式转义（本会话三次踩坑）

## 流程纪律（新会话必守，全文见根 CLAUDE.md）

铁律 1–6 全部有效。特别提醒：`vendors/` 只读；待审核 ≠ 已接受、不代签不自行 merge；证据 = 命令+退出码+失败后权威状态（失败与错位封条原样保留——L02/L03 有先例）；用户显式技能到点必须停下提醒（配合点 1 复认、配合点 2 implement）；候选表/候选分支机制照 ADR-0004；`git add` 用精确路径，**不用 `add -A`**（会把两个已裁定未跟踪项误暂存——L03 commit 1 踩过，已纠正）。

## Suggested skills（新会话按需调用）

- `to-spec`（**用户显式**；L04 讲义合同段到点提醒，降级需用户每次重新确认——L02/L03-java 均有降级复认先例）
- `implement`（**用户显式**；触点 2 必须，未授权不得裸做）
- `tdd` / `code-review` / `diagnosing-bugs` / `codebase-design`（模型自调：红绿循环 / 验收前双轴复查 / 遇障诊断 / execution 接口设计——L03 同位先例见其会话）
- `handoff`（下次换会话再走一次）

## 环境事实

- JBR Java 21.0.10（javac 同）+ Maven 3.9.6；Python 侧 `.venv`（3.13，anaconda 托管，L00 既记偏离）
- Java CLI 包装：`./bin/wb <子命令>`；测试命令 Java `mvn test`（**59 预期绿**，随讲次增长）；Python `-X utf8 -m unittest discover -s tests`（103 预期绿，冻结基线）
- 采集工具：`tools/capture_evidence.py --submission-root <完整相对路径> <phase> -- <命令>`（L04 用 `lesson-04-submission/java`；参数是完整相对路径——L02 曾误读产生错位封条）；导入用 `java -cp "target/classes:$(cat target/child-classpath.txt)" workbench.tools.ImportEvidence <meta.json> --runtime-dir … --task-id …`
- golden 工作流要点：掩码 `<TS>`/`<WORKBENCH_ID>`、`observed_at` 逐字保留、canonical JSON = `sort_keys + ensure_ascii=False + indent=2 + 换行`；幂等双跑指纹一致入 §G（**首跑就要留指纹**——L03 首跑漏留，靠第 2/3 跑补证，已如实记录）；golden 前置落 master，永不手改；manifest 必须保留 normalization 块（GoldenReplay 断言其在场）
- 任务账：Java 活账本 `.runtime/course/L01-workbench-java/`（沿用）；L04 任务号建议 `CASE-WB-L04-JAVA-001`（执行前向用户索取 owner/actor，不代填——L01–L03 先例均为 XianReallyHot-ZZH）
- N0/N1 会话模型（若该讲有）：`glm-5.3-flash[1M]`（L02 先例；L03 因 D3 移植无此环节）

## 已闭合事项（查询用，不需重开）

- L02-java 遗留（本讲未动，继续留后续讲次或工具整合讲次处理）：`.idea/` 与仓库根 `java/` 的 .gitignore 归置——非 L04 写集，勿顺手做
- L03-java 验收裁定与记录：错误公式人工退回结论已随验收复认（在库 8 预占 3 应为 5）；U+2028 行界分歧已修复并记录（`Pattern.MULTILINE` vs `re.MULTILINE`，围栏半边 splitlines 全集 / 标题半边 \n 的两半边行界同形）；全记录在 `evidence/L03-java.md` §5/§6
- L01-java 已知行为边界（parseAware 更严 / now() 无微秒）——`evidence/L01-java.md` 偏差与采纳记录；后续 golden 若触达同类边界，显式采纳口径
- master 领先 origin 的 push 决定权在用户（每次可问一句，不催）
