# L03-java 重走证据账（lesson-03-java 前置与候选）

> ADR-0006 重走线第三讲证据。本账只追加；Python 时代证据见 [L03.md](L03.md)，不重开。移植注记与 golden 方案见 [L03-可验收Spec.md 附录 A](../../lessons/L03-可验收Spec.md)（2026-09-29 用户审定，"没问题，继续"批量确认：重建口径四层 + A6.1 全部移植裁定 + JD1–JD6 推荐口径 + golden 方案 11 场景）。

## G. 对照基准（golden）生成 —— 2026-09-29（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 工作台（master，Python 实现、测试、pyproject 零改动）
- 生成工具：`tools/generate_golden_l03.py`（只用标准库；11 场景单会话按序驱动真实 CLI 子进程，与合同测试同形，不绕入口直调）
- 场景面 = `spec` 命令结构闸门（纯解析，**无账本场景**——L03 工作台增量只有解析器与 spec 入口）；errno 类场景（文件不存在 / 非 UTF-8 解码）不入 golden：词面系语言绑定，按移植注记 JD6 如实记录偏差、不伪装同形
- 上游现查（起草时，2026-09-29）：`origin/main` = `7f67533`，与本讲对照基线一致（检查点 0002 后无新变更，冻结合同零变化）→ 不触发检查点采纳

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l03.py`（首跑） | 0 | 11/11 场景按预期退出码通过（t02/t06–t11 预期 rc 1，其余 rc 0），EXIT=0 |
| G2 | 同命令（第 2 跑，确定性比对） | 0 | 11/11 ok，EXIT=0 |
| G3 | `find src/test/resources/golden/l03 -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`（第 2、3 跑各一次） | 0 | 两跑目录指纹同为 `f5c7a611694262f14eaee1c9168ad9538f73e36d83540600402c0cbad82d4a7d` → **golden 字节级确定**（首跑未独立留存指纹，确定性由第 2/3 跑连续两跑各取指纹一致证明，如实记录） |

### 场景清单（11，单命令粒度，逐条见 `manifest.json` 的 scenarios 数组）

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 通过面 | t01–t05 | 冻结确认版六字段原文（t01）/ 冻结错误公式版结构通过（t03，**结构通过 ≠ 业务正确**）/ 围栏内标题不误认（t04，检查点 0002 教学节）/ 冻结采购稿（t05，C6 重验） |
| 拒绝面 | t02, t06–t11 | 冻结缺章版（t02，词面 `Spec 缺少必要章节：非目标`——C11 eval `spec_contract_rejects_ambiguity` 承载）+ 合成夹具五类：空章 / 重复 / 乱序 / 未知标题 / 围栏未闭合 / 空文件缺章全列表 |

输入固定件三源：①冻结三工件（`lesson-03-submission/` 的 FDE_SPEC.md / missing-section.md / wrong-formula.md，生成时读取内容冻结入 manifest——golden 直接封存本讲自己的三份输入）+ 冻结采购稿；②合成夹具七件（生成器常量，镜像讲义 §3.G"手工复制并编辑"）。错误词面经 G 批次抽查七条，全部与冻结解析器逐字同形（t02 与 Python 时代台账 #8 先例逐字一致）。

### 规范化与掩码规则（与 golden/l01、l02 逐字相同，Java 测试侧按 `manifest.json` normalization 块复现）

- 掩码块（`created_at`/`recorded_at` → `<TS>`、`workbench_id` → `<WORKBENCH_ID>`）为三套 golden 统一形状，重放测试断言其在场；spec 输出本身无账本时间戳字段
- canonical JSON：`sort_keys=True, ensure_ascii=False, indent=2` + 末尾换行；非 JSON 逐字保留（防御性）
- golden 共 12 个文件（11 份 stdout + manifest.json）；**生成后永不手改**，要变只能重生成并留证据

### 落位与继承

golden 前置生成，连同生成器与本 §G、附录 A 审定稿随前置提交落 master（L01/L02 先例：`evidence/L02-java.md` §G）；`lesson-03-java` 自 master 分出即继承。

### raw 现场

`.runtime/golden-l03/raw/`（gitignore，不入库）：11 组 stdout/stderr + inputs 固定件。重跑生成器即可复现，不依赖本机留存。

---

## R. 候选 lesson-03-java 证据（2026-09-29，implement 授权后）

### R0 环境与分支形状

- 环境：JBR Java 21.0.10（javac 21.0.10）、Maven 3.9.6、macOS（Darwin 25.6.0）；Python 侧 .venv（3.13，anaconda 托管——L00 既记偏离）
- 分支 `lesson-03-java` 自 master@`56882f8` 分出（继承前置 golden）
- 决策记录：附录 A 全案审定（"没问题，继续"批量确认，见本账头注）；**配合点 1 复认（2026-09-29）**——用户显式选择降级路径（每次重新确认，L02-java 先例）；Java 侧决定记录 = `lesson-03-submission/java/decisions-java.md`（J1–J8）
- 本讲无 N0/N1 行为对照会话（D3 移植：`claude -p` 实验降为可选观察，Python 时代亦未使用）

### R1 起始红（C1）

- **红封条**：`lesson-03-submission/java/03-failure/20260929T080651221652Z-b5a9c244/`（`mvn test`，rc=1，observed_at 2026-09-29T08:06:51Z）
- 红形态：**58 跑 14 红**，全部目标能力缺失类——L03 行为组 12（`L03SpecContractTest`：spec 命令 invalid-choice rc 2、六类拒绝/围栏/只读/字段原文断言全部落空）+ `SpecTemplateDualCarrierTest` 1（Java 面模板拷贝缺失）+ `GoldenL03ReplayTest` 1（golden l03 重放不齐——本讲预期红点，移植注记 A1/A2）；护栏组 4 绿（映射表声明就位即绿：冻结工件在场断言）+ fixture 机检 + golden l01/l02 重放 + 既有合同 = 44 绿
- 采红前预检同形状（58 跑 14 红），封条为正式采集（同命令）；commit 1 = `0659024`

### R2 红转绿（配合点 2：用户显式调用 `/mattpocock-skills:implement`）—— 2026-09-29

**实现四件**（commit 2 = `506197f`，写集与移植注记 A2 C12 一致）：

| 交付物 | 内容 |
|---|---|
| `src/main/java/workbench/spec/SpecParser.java` | 解析半边（JD1）：REQUIRED_SECTIONS / ParsedSpec.asMap（键序同 Python as_dict）/ contractHeadings 围栏屏蔽（逐行掩码保 offset、未闭合报错先行、围栏内标题不误认）/ parse 六类拒绝（词面与检查顺序未知→重复→缺章→乱序→空章逐字同形）/ load UTF-8 只读（无默认路径参数——D4/S2）；行界语义：围栏半边 splitlines 全集同形、标题半边仅 \n（附录 A1 实现注记；复查轮 S-1 对齐，见 §5） |
| `src/main/java/workbench/spec/SpecCommands.java` | CLI 适配器：REGISTRY 缝注册；handler 层接住 SpecParseException/IOException（P5 移植——不走顶层边界）；输出包络含 `flowerp_connected`（P3 移植），取 `Ledger.FLOWERP_CONNECTED` 单一来源（S1 起始纪律） |
| `src/main/java/workbench/bootstrap/Args.java` | 位置参数扩展（JD2）：`positionalNames` 构造器 + `positional(i)`；缺参/多余词面沿 argparse 同形；两参构造器行为零变化（40 既有用例回归绿） |
| `src/main/java/workbench/cli/Main.java` | 注册缝 +1 调用（镜像 cli.py L03 注册段）+ `src/main/resources/templates/SPEC_TEMPLATE.md` 逐字拷贝（JD3，SHA-256 与原件一致，机检锁定） |

**红转绿**：`mvn test` 全量 **58/58 绿**（40 既有 + 18 新增）——六类拒绝词面逐字同形、围栏语义、只读不变、字段原文、双载体等价、**golden l03 11 场景字节级一致**（Java spec 命令输出与冻结 Python 逐字节相同，移植注记 A1 预期达成）。

**实现期即时修正（起始纪律内，红绿间实现侧无测试改动）**：`isLineBreak` 的 U+2028/U+2029 曾以字面字符写入源码（语义正确但不可见易误读），实现中即时改为显式 `\u2028`/`\u2029` 转义——属同一写集内的代码成形过程，无断言/词面影响。

### R3 证据链与三份输入复验（C8/C10）

- **链三封条**（同命令 `mvn test`，observed_at 严格递增，Diff 严格居间）：red `…b5a9c244`（08:06:51，rc 1）→ diff `…bf66d43e`（08:21:21，rc 0，暂存区全量 `git diff --cached`——新增件为主，Python 时代台账 #4 同口径如实披露）→ green `…90ad5910`（08:21:24，rc 0，58/58）
- **三份输入复验**（C10，`./bin/wb spec`，三态齐）：
  - 确认版 `FDE_SPEC.md` → rc 0，六字段原文（obs `…c2719373`）
  - 缺章版 `missing-section.md` → rc 1，词面 `Spec 缺少必要章节：非目标`（obs `…ecb06105`；与 Python 时代台账 #8 逐字一致）
  - 错误公式版 `wrong-formula.md` → rc 0 结构通过（obs `…43647e25`）——**解析器不冒充业务裁判**；人工退回结论承袭 Python 时代具名验收（在库 8、预占 3 应为 5，错误公式"可用量 = on_hand"得 8，违反约束与 A2/A8），随具名验收复认
- **C8 真实层**：三文件 SHA-256 运行前后逐字节一致（聚合摘要 `f8691fd2…` 前后相同）；程序层 `rejectionLeavesFileUnchanged`/`successLeavesFileUnchanged` 双绿
- **三句话**（入账）：解析器能证明六段结构完整或残缺；解析器不能证明库存公式和业务决定正确（错误公式结构通过是预期，不是程序故障）；L03 仍未证明库存导出功能已实现（留 L04）
- **全量回归 observation**：`mvn test` 58/58 rc 0（obs `…c11ce339`）；程序检查不替代行为/人审证据（三层分检声明保持）
- **C11**：`spec_contract_rejects_ambiguity` 由 `rejectsMissingSection` + golden t02 缺章拒绝承载；双轨口径如实注明（Python 侧 eval harness 已建成冻结 / Java 侧待建设，未冒充已建成）

### R4 任务账与终核（C12）—— 2026-09-29

- **具名**：`--owner`/`--actor` = `XianReallyHot-ZZH`（用户 AskUserQuestion 确认，不代填）；账本 `.runtime/course/L01-workbench-java/`（L01-java 活账本沿用）
- **任务创建**：`./bin/wb workbench-task-create … CASE-WB-L03-JAVA-001 --spec-file docs/lessons/L03-可验收Spec.md` → `ok:true`，requirement_summary `2715aca8…`（spec 快照）
- **导入前缺链查询**（预期失败也是证据）：observation `…3611586c`（rc **1**，缺链词面）✓
- **链导入**：Java `workbench.tools.ImportEvidence` ×8 全 rc 0（重算 SHA-256 验封）——顺序 red→diff→green（链）→ observation×5（确认版/缺章/错误公式/回归/缺链查询，绿后导入不参与链判定）
- **终态 status 密封采集**：`…89e01657`（rc 0）——`ok:true` / `evidence_complete:true` / `acceptance:pending_human_review` / `flowerp_connected:false`；8 条 observation 在账不破坏链（golden t09/t10 语义活账复现）
- **C13 非目标核查**：候选分支文件清单 = docs（附录/证据账）/ src/main（spec 包、Args、Main、模板）/ src/test（L03 测试三件）/ lesson-03-submission/java/** / golden l03（前置）——无 FlowERP 业务、无 Java eval.harness、无 build_delivery_spec、无根 FDE_SPEC.md；**Python 冻结面零触碰**（workbench/ tests/ pyproject.toml tools/capture_evidence.py 零 diff）；vendor 双查恒空；`.idea/` 与仓库根 `java/` 维持已裁定未跟踪不入库

## C1–C13 逐项结论（Java 口径）

| # | 结论 | 证据 |
|---|---|---|
| C1 | ✓ | 起始红 `…b5a9c244` rc=1：58 跑 14 红全为目标能力缺失类（行为组 12 + 双载体 1 + golden 1）；护栏组 4 绿映射表声明；R1 |
| C2 | ✓ | 原件零重写（护栏 `frozenRequirementArtifactsInPlace` 绿）；Java 侧决定记录 `decisions-java.md` J1–J8（指针化） |
| C3 | ✓ | 结构半边：确认版经 Java CLI rc 0（obs `…c2719373`）+ golden t01 字节锁；业务半边：八条口径在场护栏绿（不重裁定，F2/F3/F4 承袭） |
| C4 | ✓ | v1 未确认状态句在场 + 无 A8；确认版 A6 细则 + A8 在场（护栏绿）；问答零重跑 |
| C5 | ✓ | 双载体机检绿（SHA-256 逐字等价）；C5 断言读 Java 拷贝：六标题在场 + 库存词面零命中（复查轮 S-3 迁移至映射表字面落点）；Python 原件零触碰 |
| C6 | ✓ | 内容面护栏绿（"待确认"在场 + 库存词面零命中）；结构半边重验：Java CLI rc 0 + golden t05 |
| C7 | ✓ | 六类拒绝 + 围栏语义 + U+2028 行界同形合同测试 9 用例绿（词面逐字同形，复查轮 S-1 修复轮）；golden t02/t06–t11 字节锁；检查顺序与冻结版一致 |
| C8 | ✓ | 程序层双绿 + 真实层 SHA 前后一致（R3）；errno/解码词面偏差按 JD6 如实记录（`missingPathReportsJsonContractShape` 只断 JSON 形状） |
| C9 | ✓ | SpecCommands 经 REGISTRY 缝注册（Main +1 调用）；适配器转发解析器异常原词面（同源）；全部行为测试经真实子进程 |
| C10 | ✓ | 三态齐（rc 0 / rc 1 / rc 0）+ 三句话入账（R3）；人工退回结论承袭 Python 具名验收，随具名验收复认 |
| C11 | ✓ | `spec_contract_rejects_ambiguity` 由缺项拒绝用例承载（映射表登记）；harness 双轨口径如实注明 |
| C12 | ✓ | 写集与 A2 C12 映射一致（R4 C13 核查）；CASE-WB-L03-JAVA-001 链完整，终态四词面密封 `…89e01657` |
| C13 | ✓ | 非目标全数未越界（R4 核查清单）；未把结构通过说成业务正确/导出已交付 |
| —（重走线增量） | ✓ | golden l03 11 场景双跑指纹 `f5c7a611…`（§G）+ Java 重放字节级一致（commit 1 红点参与者 → commit 2 转绿，移植注记 A1 预期） |

## 5. 复查轮（code-review 双轴自调，2026-09-29）

双轴子代理并行复查 `master...lesson-03-java`（Standards + Spec，报告全文见会话记录；此处存目与处置）。**处置原则：test-first 修复、旧证据全保留。**

| # | 轴 | 发现 | 处置 |
|---|---|---|---|
| S-1 | Spec·偏差（实测复现） | U+2028 行分隔输入：Python rc 1（re.MULTILINE 只认 \n 行界，整段成未知标题）vs Java rc 0（Pattern.MULTILINE 把 \u0085/\u2028/\u2029 也当行界）——违背 C7"逐字移植" | ✅ 已修：先加失败测试（期望词面 = 冻结 Python CLI 对同一夹具的**实测输出**，独立真值源）→ SpecParser 标题半边改按 \n 手工分行（围栏半边保持 splitlines 全集——两个半边行界均与 Python 同形）→ 17/17 绿 → 全量 59/59 |
| S-2 | Spec·不实引用 | R2 行引用不存在的"JD7"编号（A6.2 审定只有 JD1–JD6） | ✅ 已修：改引附录 A1 实现注记（真实审定文） |
| S-3 | Spec·落点偏离 | C5 断言读 Python 原件，映射表 A2 字面要求读 Java 拷贝 | ✅ 已修：断言迁至 `src/main/resources/templates/` 拷贝（双载体机检已锁逐字等价，语义不变）；修复轮 tests/ 变更在此披露 |
| S-4 | Standards·smell | `sixSectionsForOrderProbe()` 与 `sixSections()` 重复（仅换两行） | ✅ 已修：WRONG_ORDER 改 `section()` 直接拼接（行为零变化，59/59 复绿） |
| S-5 | Standards·缺口 | 证据面未入库：证据账 R 段、`decisions-java.md`、缺链查询/终态 status 两封条在工作区未提交 | ✅ 已修：随本复查轮 commit 入库（分支自包含） |
| S-6 | Standards·流程差异 | 仓库根 `java/03-failure/…accdd5fa` 游离捕获未在账引用 | 澄清（不改）：**L02-java §6 具名验收已裁定**——采红参数误读的错位封条"原样保留工作树，不删不移（output_file 绝对路径移动即断封条）"，非本讲产物，裁定已闭合不重开 |
| S-7 | Standards·smell | `Main` 顶层边界硬编码 `flowerp_connected:false`，与新代码 `Ledger.FLOWERP_CONNECTED` 单一来源不一致 | 核实（不改）：冻结 Python `workbench/cli.py` 顶层边界同样硬编码 `False`——Java 同形系对照复刻，改则偏离对照件；该行不在本 diff 内 |

**修复轮红绿间 tests/ 变更披露**（L03 Python 时代 §3 夹具修正同体例）：+1 用例（U+2028，新增收紧，期望词面取 Python 实测）；C5 断言文件迁移（S-3，等价迁移）；WRONG_ORDER 夹具构造去重（S-4，断言逐字未动）。**无断言放宽。**

**修复轮链事件**（红 `…b5a9c244` 保持不变，链以修复轮绿收口）：**Diff** `04-diff/20260929T084958695068Z-91c031bb/`（rc 0）+ **后绿** `05-green/20260929T084958820642Z-49af4512/`（`mvn test` 与起始红同命令，rc 0，**59/59** = 40 既有 + 18 commit1 + 1 修复轮新增）——observed_at 严格递增，导入账本后全局最新红绿链合法。首绿 `…90ad5910`（58/58）保留在账。

## 6. 具名验收

- 验收人：XianReallyHot-ZZH
- 日期：2026-09-29
- 结论：**接受**（"没问题，继续"；批量确认同时含错误公式人工退回结论复认——在库 8、预占 3 应为 5，错误公式"可用量 = on_hand"得 8，违反约束与 A2/A8，解析器通过不改变业务退回）
- 依据：本证据账 C1–C13 逐项结论（Java 口径）+ §G golden 双跑指纹链（`f5c7a611…`）+ §5 复查轮（S-1–S-5 已修、S-6/S-7 澄清在案，修复轮 tests/ 变更披露无断言放宽）+ 任务账 CASE-WB-L03-JAVA-001 终态四词面密封（`…25327f07`）
- 合并：`git merge --no-ff lesson-03-java`，合并信息含验收人与结论（ADR-0004）
- 未解决问题清单：① `.idea/` 与仓库根 `java/` 的 .gitignore 归置（L02-java 遗留，非本讲写集）；② master 领先 origin 未 push（用户既定留本地）

---
