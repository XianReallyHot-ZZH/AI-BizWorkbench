# L01-java 重走证据账（lesson-01-java 前置与候选）

> ADR-0006 重走线首讲证据。本账只追加；Python 时代证据见 [L01.md](L01.md)，不重开。

## G. 对照基准（golden）生成 —— 2026-09-29（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 工作台（master @ e3d09bf；Python 实现、测试、pyproject 零改动）
- 生成工具：`tools/generate_golden_l01.py`（只用标准库；22 场景单会话按序驱动真实 CLI 子进程，与合同测试同形，不绕入口直调）

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l01.py`（首跑） | 先 1 后 0 | **失败现场**：s01 采集后因清场时未重建 `GOLDEN_DIR` 抛 `FileNotFoundError` 中止；修复（补 `mkdir(parents=True)`）后重跑，22/22 场景按预期退出码通过，EXIT=0 |
| G2 | 同命令（第 2 跑，确定性比对） | 0 | 22/22 ok，EXIT=0 |
| G3 | `find src/test/resources/golden/l01 -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`（两跑各一次） | 0 | 两跑目录指纹同为 `a694bf72e9f941a634b4d0917f9bfac17d69b58cce950ea69156aaae719eb8e5` → **golden 字节级确定** |

### 场景清单（22，逐条见 `manifest.json` 的 scenarios 数组）

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 身份三态 | s01–s03 | init 成功 / 同 owner 幂等（F13 同形）/ 跨 owner 拒绝 |
| 项目两态 | s04–s05 | 登记成功 / 重复编号拒绝 |
| 任务三态 | s06–s08 | 带 spec 快照创建 / 缺所属项目拒绝 / 重复编号拒绝 |
| 全量查询 | s09 | 未初始化拒绝（另有 s21）；账本全量回显（掩码后） |
| 链五态 | s10、s12、s15、s17 | 缺链 / 仅红 / 红—Diff—绿完整（`evidence_complete:true` + `acceptance:pending_human_review`）/ 绿后新失败失权（F2 全局最新口径） |
| 记录追加 | s11、s13、s14、s16 | red/diff/green/绿后 red 追加均成功（账本只追加） |
| 错误查询 | s18 | `required_task_missing` 词面 |
| 记录质量拒收 | s19–s20 | 缺时区观察时间拒 / 空输出拒 |
| 未初始化不建库 | s21 | 查询后目录仍空（WB-10，生成器内断言） |
| 篡改复核 | s22 | 白盒改写 record 1 输出后 status 报 `output_digest_mismatch`（F3 先例；每次重算 SHA-256 的信用内核面） |

### 规范化与掩码规则（Java 测试侧须按 `manifest.json` 的 normalization 块复现）

- 掩码字段（不可复现）：`created_at` / `recorded_at` → `<TS>`（墙钟）；`workbench_id` → `<WORKBENCH_ID>`（uuid4）
- `observed_at` **逐字保留**——它是合同字段（链时序语义），生成时用固定字面量
- canonical JSON：`sort_keys=True, ensure_ascii=False, indent=2` + 末尾换行；非 JSON 逐字保留（防御性）
- golden 共 23 个文件（22 份 stdout + manifest.json）；**生成后永不手改**，要变只能重生成并留证据

### 落位说明（对附录 A4 的微修正）

golden 前置生成并随本次提交落 master；`lesson-01-java` 自 master 分出即继承，commit 1 的 Java 测试直接消费。A4 原"golden 随 commit 1 入库"按此口径执行。

### raw 现场

`.runtime/golden-l01/raw/`（gitignore，不入库）：22 组 stdout/stderr + inputs 固定件。重跑生成器即可复现，不依赖本机留存。

---

## R. 候选 lesson-01-java 证据（2026-09-29，implement 授权后）

### R0 环境与分支形状

- 环境：JBR Java 21.0.10（javac 21.0.10）、Maven 3.9.6、macOS（Darwin 25.6.0）；Python 侧 .venv（3.13，anaconda 托管——L00 既记偏离）
- 分支 `lesson-01-java` 自 master@`f42ccde` 分出；commit 1 = `75eff22`（合同 fixture + 测试 + 骨架 + 起始红），commit 2 = `b297687`（实现 + Diff/后绿，amend 补投影工具与 F3/F4 测试修正）
- 测试口径：25 合同用例 + 1 golden 重放 + 5 fixture 全部经真实 CLI 子进程或纯数据比对，无直调内部

### R1 起始红（C2）

- **红封条**：`lesson-01-submission/03-failure/20260929T020046109407Z-12cb6fca/`（`mvn test`，rc=1）
- 红形态：31 跑 **26 红**（25 合同 + 1 golden，失败集中于 invalid choice rc 2 = 目标能力缺失）+ **5 绿**（fixture 结构与全字段投影等价——C1"就位"类验收先绿是预期）
- 采红前两次失败（环境/配置类，按"先修再采"处理，现场保留）：
  1. pom 误把 `build-classpath` 挂到 exec-maven-plugin（该目标属 maven-dependency-plugin）→ 修正插件后编译通过
  2. golden 生成器清场未重建 GOLDEN_DIR → FileNotFoundError → 补 `mkdir(parents=True)`
- fixture 转录滑手一次：L15 eval 名漏写 "come"（`raw_feedback_cannot_be_blocking` ≠ 投影 `raw_feedback_cannot_become_blocking`），被**全字段投影机检当场抓获**（27 失败中单列一条），修正后红形态纯净

### R2 实现 + 后绿（C3–C6、C9–C10）

- **Diff 封条**：`04-diff/20260929T022033435082Z-e29441c2/`（`git diff -- src pom.xml .gitignore`，rc 0）
- **后绿封条**：`05-green/20260929T022033787349Z-5c671f51/`（`mvn test` 与起始红同一命令，rc=0，31/31；golden 22 场景规范化后逐字节一致）
- 移植滑手两处由红绿回路抓获：F3/F4 两用例移植时凭记忆丢了 `add_project()`，失败形态（项目不存在 → 记录不存在级联）暴露后回读 Python 原文（`tests/test_l01_workbench_bootstrap.py:459/481`）修正——详见偏差记录第 3 条

### R3 自举（C7、C8）

`--owner`/`--actor` = XianReallyHot-ZZH（执行前向用户索取，不代填）；账本 `.runtime/course/L01-workbench-java/`（全新，Python 时代账本零触碰）。

| 步骤 | 命令 | rc | 关键输出 |
|---|---|---|---|
| init | `./bin/wb workbench-init --runtime-dir … --name AI-BizWorkbench --owner XianReallyHot-ZZH` | 0 | workbench_id 0c0baa52…（uuid，账内原样） |
| project-add | `./bin/wb workbench-project-add … PERSONAL-WORKBENCH …` | 0 | ok:true |
| task-create | `./bin/wb workbench-task-create … CASE-WB-L01-JAVA-001 --spec-file docs/lessons/L01-工作台自举.md` | 0 | requirement_summary `4d00d2815fc1…`（spec 快照） |
| 查一：导入前缺链 | observation 封条 `06-observations/20260929T022815458079Z-caaacb47/` | **1** | errors 含 `same_command_red_diff_green_missing: 缺少同命令的红—Diff—绿链` ✓ |
| 导入三封条 | Java `workbench.tools.ImportEvidence <meta.json>` × red/diff/green（重算 SHA-256 验封） | 0,0,0 | observed_at 严格递增 |
| 查二：完整链 | 同一条 status 查询 | 0 | `ok:true`、`evidence_complete:true`、`acceptance:pending_human_review`、`flowerp_connected:false`、3 记录 ✓ |
| 查三：MISSING | observation 封条 `06-observations/20260929T02350…`（MISSING 查询） | **1** | `required_task_missing: 任务不存在：CASE-WB-L01-JAVA-MISSING` ✓ |
| 查四：原任务复查 | 同查询（原任务） | 0 | request/requirement_summary 不变、3 记录 ✓ |

**包装脚本定案**：`bin/wb`（classpath = `target/classes` + build-classpath 落盘清单），不打 fat jar——A2 已记。

### R4 复查轮（code-review 双轴自调，2026-09-29）

- **Standards 轴** 6 发现（2 硬违规 + 4 判断项）；**Spec 轴** 6 发现。处置：
  - 已修（代码）：包循环（Args/JsonOut/PyJson 下沉 `workbench.bootstrap`，新增 `bootstrap.Command` 函数式接口，依赖单向 cli→bootstrap，与 Python 载体同形）；Cli.java 注释插件名更正（maven-dependency-plugin）；Main usage 词面中性化（`usage: workbench …`，不再自称 python）；ImportEvidence BOM 注释如实化 + 复用 `Ledger.sha256`（消除第三份 SHA-256 hex 循环）；`--phase` invalid choice 词面对齐 argparse 引号形
  - 记录处置（证据账）：golden manifest 重生成采纳（偏差记录第 2 条）、tools/ 写集增补待裁定（第 1 条）、commit 2 测试修正待裁定（第 3 条）、parseAware/now() 已知边界（第 4 条）
- 复查修复后重采：**Diff** `04-diff/20260929T024841417268Z-eeacbf5e/` + **后绿** `05-green/20260929T024841547012Z-313d1484/`（`mvn test` 31/31，rc=0）；导入账本后终查 5 记录、链完整、全局最新绿 rc 0
- **补救实证一**（人审清单第 3 条）：修正后测试套件对 commit 1 骨架真跑 → **26 红 rc 1**（observation `06-observations/20260929T024251988418Z-ee34a2f1/`）——测试修正未制造假绿，红先于实现真实存在
- **补救实证二**（A5-5 ImportEvidence 摘要拒收）：篡改件（复制件，原封条未动）→ 拒收词面 + **rc 1**（observation `06-observations/20260929T025144016674Z-6cae1ff2/`）
- 复查过程事故一次（如实保留）：骨架演示 `git checkout HEAD -- Main.java` 覆盖了未提交的复查修复版 Main → 级联编译失败；恢复重写后全绿。两次无效捕获**原样保留**：`06-observations/20260929T024313945272Z-e3ab1636/`（rc 2，target/classes 残留 stub Main.class）、`06-observations/20260929T024420166737Z-04dd3049/`（rc 1 但无拒收词面——meta 副本 output_file 未改指篡改件）

### C1–C11 逐项结论（Java 口径）

| # | 结论 | 证据 |
|---|---|---|
| C1 | ✓ | `CourseContracts.java` 16 讲全字段投影机检 5 绿；投影件由 `tools/export_course_contracts_json.py` 机械导出自冻结 Python 载体 |
| C2 | ✓ | 起始红 `03-failure/20260929T020046109407Z-12cb6fca/` rc=1，26 红全能力缺失类；补救实证一复核 |
| C3 | ✓ | WB01/02/05 绿（跨进程查回、快照不随原文件变） |
| C4 | ✓ | WB03/04/06/10 绿（拒绝后原数据不变；查询不偷偷建库） |
| C5 | ✓ | WB07/07b/08/09/11 + F2/F7 绿（同命令红绿、Diff 严格居间、全局最新绿、跨命令失权） |
| C6 | ✓ | c6/F14/recordId 绿；`workbench-evidence-add` 参数形状与冻结接口一致；ImportEvidence 复刻 vendor 语义 + 拒收实证 |
| C7 | ✓ | R3：`ok:true`、`evidence_complete:true`、`acceptance:pending_human_review`、`flowerp_connected:false` |
| C8 | ✓ | R3 查三/查四 |
| C9 | ✓* | `git diff master...HEAD -- workbench tests pyproject.toml` 为空；vendors 双查恒空；FlowERP 零写动。*tools/ 增补见偏差记录第 1 条，待验收裁定 |
| C10 | ✓ | 无 eval.harness / Web / 自动执行器；`.runtime/course/L01-workbench/`（Python 时代账本）零写入 |
| C11 | ✓ | 本文件；merge 信息含验收人（待具名） |

### 偏差与采纳记录（验收人裁定项）

1. **写集增补（C9）**：`tools/generate_golden_l01.py`（manifest 机检标记）与 `tools/export_course_contracts_json.py`（C1 投影生成件）在 ADR-0006 附录 A2 C9 写集清单之外。理由：二者分别是 A3 golden 与 C1 机检的直接生成件，随候选入库 provenance 才闭环。处置：C9 口径增补"tools/（golden/fixture 生成工具）"，**待验收人裁定**。
2. **golden manifest 重生成（A3）**：分支内 manifest 增 `inputs`/`tamper_before`/`assert_db_absent` 三类机检标记（Java 重放数据驱动所需），经生成器重生成（非手改）。**22 份 stdout 文件字节未动**；目录指纹 `a694bf72…`（§G 原采）→ `60159959de1f9cc2d54a0ef307daa55dcd378ffbd85911fd5715cb09f6ff1fbb`（重生成后）；stdout 22 文件指纹 `382a77b1bc1907c0e2eb39306bbad297a4f0d6a61bc434c83a38f6fb4a675949`。A3 口径"要变只能重生成并留证据"——本条即证据。
3. **commit 2 含测试修正（人审清单第 3 条豁免申请）**：F3/F4 补 `add_project()`（非注释行）。缘由与补救实证见 R2/R4。按清单字面（"仅增注释"）属偏差，**待验收人裁定**。
4. **已知行为边界（备查，非偏差）**：Java `parseAware`（OffsetDateTime.parse）比 Python `fromisoformat` 严（拒无冒号偏移/空格分隔）；`now()` 无微秒。golden 侧均被掩码/固定值覆盖，22 场景字节级一致不受影响；后续讲次若引入相关合同再显式采纳口径。
5. **投影件与生成器跨 commit**：投影 JSON 在 commit 1、生成工具在 commit 2（amend 补入）——provenance 以工具文件头说明 + 本记录闭环。
6. **无效捕获保留**：`06-observations/20260929T024313945272Z-e3ab1636/`（rc 2）与 `20260929T024420166737Z-04dd3049/`（rc 1 无词面）两次失败尝试原样保留，败因见 R4 事故记录。

### 封条清单（lesson-01-submission 内新增，全部随 Git 提交、永不移动）

| 阶段 | 目录 | rc | 用途 |
|---|---|---|---|
| 03-failure | 20260929T020046109407Z-12cb6fca | 1 | 起始红（C2） |
| 04-diff | 20260929T022033435082Z-e29441c2 | 0 | 实现后 Diff |
| 05-green | 20260929T022033787349Z-5c671f51 | 0 | 第一轮后绿（31/31） |
| 06-observations | 20260929T022815458079Z-caaacb47 | 1 | 自举查一：缺链 |
| 06-observations | 20260929T022928837453Z-2d05984b | 1 | 自举查三：MISSING（required_task_missing） |
| 04-diff | 20260929T023943222173Z-23b2b062 | 0 | 复查轮中间态 Diff（--phase 词面修复后，先于骨架演示） |
| 05-green | 20260929T023943359623Z-a6d8984e | 0 | 复查轮中间态后绿（31/31；未导入账本，账内链以 02:48 终态封条收口） |
| 04-diff | 20260929T024841417268Z-eeacbf5e | 0 | 复查修复后 Diff |
| 05-green | 20260929T024841547012Z-313d1484 | 0 | 复查修复后后绿（31/31） |
| 06-observations | 20260929T024251988418Z-ee34a2f1 | 1 | 补救实证一：修正套件对骨架 26 红 |
| 06-observations | 20260929T024313945272Z-e3ab1636 | 2 | 无效捕获（保留） |
| 06-observations | 20260929T024420166737Z-04dd3049 | 1 | 无效捕获（保留） |
| 06-observations | 20260929T025144016674Z-6cae1ff2 | 1 | 补救实证二：摘要篡改拒收 |

注：全部封条目录名与 rc 已逐条核实（`meta.json` 现读），无占位项。

### 具名验收

- 验收人：＿＿＿＿
- 日期：＿＿＿＿
- 结论：接受 / 退回（理由：＿＿＿＿）
- 需同时裁定：偏差记录第 1 条（写集增补）、第 3 条（commit 2 测试修正）
