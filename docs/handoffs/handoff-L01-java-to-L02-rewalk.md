# 会话交接：L01 Java 重走已完成验收 → 下一任务 L02 重走

> 生成于 2026-09-29，交接到全新会话。本文件只做指针与状态，不复制既有工件内容——先读路径，再动手。

## 当前状态（事实）

- 分支 master @ `1e5301c`，工作树干净；**领先 origin 6 提交未 push**（用户未表态，重启前可问一句）
- **L01 Java 重走已具名验收并合并**（`7d2a017`，验收人 XianReallyHot-ZZH）：31/31 绿（25 合同 + golden 22 场景字节级一致 + fixture 全字段投影机检），自举四查通过
- 双轨共存验证成立：master 上 Python 全量 OK（rc 0）+ Java 全量 31/31
- 重走线进度：L01 ✅ → **L02–L06 待逐讲推进**（不跨讲批量，每讲三触点），L07 顺延至重走完成（ADR-0006）

## 权威文件（动手前先读，勿信本文件转述）

| 主题 | 路径 |
|---|---|
| 换语言决策与全部 Consequences | `docs/adr/0006-java-rewalk.md` |
| 重走模板（移植注记/golden 方案/人审增量的结构基准） | `docs/lessons/L01-工作台自举.md` **附录 A** |
| 重走证据账模板（R 段式、C 表、偏差与采纳记录） | `docs/replication/evidence/L01-java.md` |
| golden 生成器模式（L02 需按其合同面另写） | `tools/generate_golden_l01.py` + `src/test/resources/golden/l01/manifest.json`（normalization 块 = 掩码/规范化契约） |
| fixture 逐字等价机检模式 | `tools/export_course_contracts_json.py` + `src/test/resources/coursecontracts/frozen-python-projection.json` |
| 词汇表（措辞必须继承） | `CONTEXT.md` |
| 铁律/结构约定/FlowERP 业务边界 | 根 `CLAUDE.md` |
| 重走线状态 | `docs/replication/README.md` 重走线行 |
| L01 验收后复盘节奏 | `docs/replication/evidence/L01-java.md` R4（复查修复后**重采 Diff+绿并追加导入账本**，链保持合法——L02 起照此模板） |

## 下一任务：L02 重走（仓库规则）

流程与 L01 完全同形（每讲用户只有三个触点）：

1. **起草** `docs/lessons/L02-仓库规则.md` 的"Java 重走移植注记"附录（含 C 项翻译表、golden 方案、写集、人审增量）+ 生成 L02 golden（Python 冻结版仍可跑，just-in-time，勿批量预生成）→ **触点 1：用户审定**
2. **触点 2：用户显式调 `/mattpocock-skills:implement`** → 候选分支 `lesson-02-java`，commit 1 = fixture/测试 + 起始红（vendor `evidence.py` 采集）→ 实现 → commit 2 = Diff/后绿
3. code-review 双轴自调 → 修复（test-first，失败与无效捕获原样保留）→ 重新采绿追加导入 → 自举（L02 是否有自举段按其讲义）→ **触点 3：具名验收** → `--no-ff` 合并 → roadmap/讲义导航收口

**起草注记前必须先读**（L02 特有口径包袱）：
- `docs/lessons/L02-仓库规则.md` 与 `docs/replication/evidence/L02.md`（Python 时代累积口径：规则文件 AGENTS.md→CLAUDE.md 的既定换道、复查轮记录）
- 根 `CLAUDE.md` 的"FlowERP 业务边界（L02 固化）"段——L02 的产物至今活在仓库规则里，Java 重走**重建什么**（预计：越界请求被规则判拒的合同测试 Java 化 + L02 时代裁定移植，而非重写规则文本）是注记起草要回答的第一个问题
- 候选历史：`git log --oneline` 查 L02 时代 lesson-02 分支 merge commit

## 流程纪律（新会话必守，全文见根 CLAUDE.md）

铁律 1–6 全部有效。特别提醒：`vendors/` 只读；待审核≠已接受、不代签不自行 merge；证据 = 命令+退出码+失败后权威状态（失败记录一律保留——L01 有两次无效捕获原样入账的先例）；用户显式技能到点必须停下提醒；换会话走本技能。

## Suggested skills（新会话按需调用）

- `to-spec`（**用户显式**；L02 讲义合同段到点提醒，未配置 issue tracker，降级路径=合同 fixture+讲义合同段，需用户明确同意）
- `implement`（**用户显式**；触点 2 必须，未授权不得裸做）
- `tdd` / `code-review` / `diagnosing-bugs` / `codebase-design`（模型自调：红绿循环 / 验收前双轴复查 / 遇障诊断 / 接口设计）
- `handoff`（下次换会话再走一次）

## 环境事实

- JBR Java 21.0.10（javac 同）+ Maven 3.9.6；Python 侧 `.venv`（3.13，anaconda 托管，L00 既记偏离）
- Java CLI 包装：`./bin/wb <子命令>`（classpath 由 mvn 生命期 build-classpath 落盘）
- 测试命令：Java `mvn test`（31 预期绿，会随讲次增长）；Python `-X utf8 -m unittest discover -s tests`（103 预期绿，冻结基线）
- golden 工作流要点：掩码 `<TS>`/`<WORKBENCH_ID>`、`observed_at` 逐字保留、canonical JSON = `sort_keys + ensure_ascii=False + indent=2 + 换行`；重生成必须留证据（指纹链），永不手改

## 已闭合事项（查询用，不需重开）

L01-java 的两条验收裁定（tools/ 写集增补、commit 2 测试修正豁免）、已知行为边界（parseAware 更严/now() 无微秒）、无效捕获处置——全部记录在 `docs/replication/evidence/L01-java.md` 偏差与采纳记录。
