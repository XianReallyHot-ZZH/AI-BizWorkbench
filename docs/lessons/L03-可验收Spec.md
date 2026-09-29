# L03 - 可验收 Spec

> 延伸阅读（上游原讲义）：`vendors/CodexFDE/docs/courses/L03/`（README / 辅导资料 / 实践操作手册 / 行动卡 / prompts×3 / `skills/spec-acceptance-review/` / examples v1·v2 / 架构选读）。上游标题"把模糊需求变成可验收 Spec"。原讲义口径为 Windows PowerShell + Codex CLI + 外循环直接监督；本讲义为 macOS + Claude Code + 候选分支（ADR-0004）口径。对照基线 `CodexFDE@7f67533`（检查点 0002：2026-09-27 用户裁决从 a74445a 升级；冻结合同无变化，辅导资料新增解析器教学节已并入本讲 §3.F 验收口径）。

## 1. 讲解

L01 的工作台能记账了，L02 把长期规则固化进了 CLAUDE.md。现在第一次面对**客户的新需求**："给我导出一份库存，我要知道还有多少货能卖。"这句话还不能开工——导出哪些列？一行代表商品、仓库还是批次？空库存输出什么？写出失败怎么办？本讲把这句话整理成**可验收的单次合同** `FDE_SPEC.md`，把整理方式提炼成通用模板，并让工作台获得读取合同的**结构闸门**。上游的一句话判断沿用：L01 练习写清一件事，L03 把这种做法变成可重复使用的能力。

**分层纪律是本讲的魂**（上游辅导资料 §5 + 检查点 0002 新增教学节）：解析器只回答"六段结构是否完整"，绝不冒充业务裁判——在库 8、预占 3、公式错写成"可用量＝在库量"的合同，解析照样通过，退回它的是人。缺章、空章、重复、乱序、未知标题、未闭合代码围栏是**六种不同错误**，各有能指导修订的原因；代码围栏里的 `## 目标` 是正文不是章节，围栏未闭合必须报错而不是吞掉后续正式章节。结构通过 ≠ 业务正确，这两层各自留下证据。

与 L02 的分工对照：L02 固化的是**长期规则**（跨任务有效，"永远怎样"）；L03 交付的是**单次合同**（一次需求签字即冻结，"这次要什么"）。L04 换挡点后执行器在两者划定的边界内受控交付——调用链：业务原话 → 决定记录 → 确认版 Spec → `workbench.spec` → `workbench.cli spec` → L04 执行入口。

**本讲交付四样**：① 确认版库存导出合同（含保留歧义的 v1 前身与修订理由）；② 去业务化的六段模板 + 用它起草的采购申请草稿（证明模板可迁移、业务口径须重确认）；③ `workbench/spec.py` 最小结构解析器 + `workbench.cli` 的 `spec` 子命令（REGISTRY 注册缝第一次扩容，L02 结构约定首次实战）；④ 三份输入复验证据（完整通过 / 缺章拒绝 / 错误公式通过但人工退回）。

**本讲显式决策点（随讲义一并裁决，逐条交用户）**：

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| D1 | 绑定 Eval `spec_contract_rejects_ambiguity` 怎么落地 | 不实现 eval.harness（L05+ 待建设）；该 eval 名由合同测试承载——缺项/歧义合同被拒的用例即其落点，证据账按 eval 名登记对应 C 项，口径注明"以同名合同测试 discharge，harness 待建设" | 等 harness 建成后补跑（L03 验收挂起，不可取） |
| D2 | 采集工具参数化（L02 复查轮 S3 尾巴，到期） | `tools/capture_evidence.py` 加 `--submission-root` 参数，默认值保持 `lesson-02-submission/`（向后兼容，L02 已封存证据磁盘合同不变）；L03 调用显式传 `lesson-03-submission` | 再镜像一个工具（重复代码，不可取） |
| D3 | `claude -p` 行为实验本讲是否保留 | 降为可选观察（observing）：本讲核心证据是纯程序红绿 + 三份输入复验，不依赖跨会话行为对照；需求访谈由用户答、Claude 整理，候选先例（L02 候选表）已覆盖"模型建议不冒充人决定" | 复刻 L02 式 N0/N1 双会话对照（重设备，本讲无对应教学位） |
| D4 | 合同 scope 里的根 `FDE_SPEC.md` | 本讲**不建**。上游根 FDE_SPEC.md 是 REQ-ECOM-001（FlowERP 超卖主线）的交付主合同，有需求来源；本仓库尚无对应需求账，预建即虚构。scope 词面映射为 `lesson-03-submission/FDE_SPEC.md`（库存导出确认版），根 FDE_SPEC.md 待 L04 换挡点按真实需求建立 | 现在就建根合同（无来源，违反"未知项标待确认"） |
| D5 | vendor `spec.py` 的生成机器半边 | 只复刻解析半边（`REQUIRED_SECTIONS`/`ParsedSpec`/`_contract_headings`/`parse_spec`/`load_spec`）；`build_delivery_spec` 等需求生成机器属上游 L04 `--requirement-spec` 消费面，本讲非目标，L04 按合同再定 | 整文件照搬（带入本讲无消费方的死代码） |
| D6 | 上游 `skills/spec-acceptance-review/` 的映射 | 作为方法论参照：反例审查环节由 Claude 按其四步法（结构 → 来源与语义 → 验收可推翻性 → 边界）执行，不安装不复制；不与 mattpocock 技能混淆 | 引入为仓库内 skill（本讲无复用频次支撑） |

## 2. 本讲合同

> 🤝 **配合点 1（需用户显式调用）**：本段对应上游"澄清歧义并冻结合同"。默认路径：本表 + 冻结合同 fixture 即为已签署 Spec（既定降级路径）。**L02 的降级同意不延续，本讲需重新确认**；若要升级为正式 spec 工件请显式调用 `/mattpocock-skills:to-spec`（发布目标为 issue tracker，本仓库未配置，需先配置或同意改落 `docs/`）。

验收项从冻结合同 fixture（`workbench/course_contracts.py` L03 条目：stage=design，workbench 增量"六段式 Spec Schema 与解析器"，ERP 增量"签字确认库存导出合同"，refs `SKU:COURSE-DEMO`）引出。fixture acceptance 五条逐字：①保存 Codex 建造解析器的任务合同、范围内 Diff 和独立红绿证据；②同一六部分模板用于库存合同与采购草稿，业务口径分别确认；③Spec 的六个必要章节可被解析；④工作台 spec 入口实际调用个人解析器；完整输入保留六字段原文，缺项输入明确拒绝，输入文件不变；⑤库存导出的列、排序、空结果与错误输入均有明确预期。

| # | 验收项 | 级别 | 预期 |
|---|---|---|---|
| C1 | 起始红（commit 1） | blocking | `tests/test_l03_spec_parser.py` 真实运行、因 **workbench/spec.py 与 spec 子命令缺失等目标缺口**失败（非环境错/零用例/语法错），退出码非零；经采集工具（`--submission-root lesson-03-submission`）留原始 `meta.json` + `output.txt` |
| C2 | 决定记录先行 | blocking | `lesson-03-submission/decisions.md` 八问逐条有来源或"待确认"，区分仓库事实/模型建议/用户决定；设计判断（3.0 同形：模块职责、调用关系、数据归属、失败不变状态）只读调查、拟新增标"待建设"；规则与取舍经**候选表交用户逐条确认**后才写入 |
| C3 | 确认版可解析且业务口径完整 | blocking | `lesson-03-submission/FDE_SPEC.md` 经 `workbench.cli spec` 解析通过（acceptance ③④前半）；八列列序、`available = on_hand - reserved`、多仓明细不合并、稳定排序、空库存出表头、CSV 特殊字符保真、写出失败不毁旧文件、成败均不改库存——逐条在"约束/验收用例"有可检查预期（acceptance ⑤） |
| C4 | 歧义消除可溯源 | blocking | `FDE_SPEC-v1.md` 保留修订前原句（含歧义），确认版消除；多仓多仓位多批次反例的问答与修订理由在 `decisions.md` 可查 |
| C5 | 模板无库存残留 | blocking | `workbench/templates/SPEC_TEMPLATE.md` 保留六标题与填写提示，无 `available`/`on_hand`/仓位/批次等库存专属答案（acceptance ②前半） |
| C6 | 采购草稿复用不照抄 | blocking | `lesson-03-submission/procurement-draft.md` 六段结构被同一解析器接受，无库存公式/仓位规则照搬，未知项保持"待确认"（acceptance ②后半） |
| C7 | 解析器六类拒绝 + 围栏语义 | blocking | 缺章/空章/重复/乱序/未知标题/未闭合围栏各有明确可定位的错误词面（与 vendor `parse_spec()` 词面同形）；围栏内 `## 标题` 不被误认成章节且正文保留示例原文 |
| C8 | 只读不变 | blocking | 任何输入经 spec 入口前后 SHA-256 一致；解析失败不改写原文件、不留部分状态（acceptance ④"输入文件不变"） |
| C9 | CLI 接线真实 | blocking | `workbench.cli spec` 经 REGISTRY 注册缝挂接、实际调用 `workbench/spec.py`（调用路径可证：测试经 CLI 真实子进程 + 错误词面同源），非孤立练习模块（acceptance ④"实际调用个人解析器"） |
| C10 | 三份输入复验 | blocking | 确认版通过（退出码 0）/ 缺章版拒绝（非零）/ 错误公式版通过但**人工退回结论在场**——"解析器能证明结构完整或残缺；不能证明业务正确；L03 未证明导出已实现"三句话入证据账 |
| C11 | 绑定 Eval 承载（决策点 D1） | blocking | `spec_contract_rejects_ambiguity` 以 C7/C3 缺项拒绝用例承载，证据账按 eval 名登记；如实注明 eval.harness 待建设，不冒充已建成 |
| C12 | 写集、任务账与验收 | blocking | Diff 限于 `lesson-03-submission/`、`FDE_SPEC.md`（scope 词面，见口径注）、`workbench/templates/`、`workbench/spec.py`、`workbench/cli.py`、`tests/`、`tools/`、`docs/`；vendor 双查干净；red/diff/green 同命令、observed_at 严格递增，导入 CASE-WB-L03-001 后 status 查询 `ok:true`、`evidence_complete:true`、`acceptance:pending_human_review`；证据账逐项结论表 + 具名验收行 + `merge --no-ff` |
| C13 | 非目标 | observing | 不实现库存导出、不修改 FlowERP 业务代码、不建 eval.harness、不建需求生成机器（D5）、不建根 FDE_SPEC.md（D4）、不把"结构通过"说成"业务正确/导出已交付" |

口径注：冻结 `scope=("FDE_SPEC.md", "lesson-03-submission/", "workbench/templates/SPEC_TEMPLATE.md", "workbench/spec.py", "workbench/cli.py", "tests/")` 是上游路径语义；本复刻映射——`FDE_SPEC.md` → `lesson-03-submission/FDE_SPEC.md`（决策点 D4，根合同本讲不建）；`workbench/templates/` 为新增目录；另按 L01/L02 先例扩展证据与脚手架位（`tools/`、`docs/replication/evidence/L03.md`）。上游"个人材料放 `lesson-03-submission/` 五文件"映射为：需求工件（`decisions.md`、`FDE_SPEC-v1.md`、`FDE_SPEC.md`、`procurement-draft.md`、反例副本）+ 采集工具捕获（`03-failure|04-diff|05-green|06-observations/`，**随 Git 提交且永不移动**，L01 封条先例）。冻结数据逐字不动，映射仅记于此。

## 3. 实操流程 + Claude 简报

全程在 `lesson-03` 候选分支；Python 用仓库 `.venv`；只用标准库。上游六步路线映射：①建目录 → §3.A；②需求八问 → §3.C；③合同三小段（3.0 设计判断/3.1 V1/3.2 反例）→ §3.C；④模板+迁移 → §3.D；⑤解析器 → §3.E–F；⑥三份输入复验 → §3.G。

### A. 候选分支与采集工具参数化

```bash
git -C vendors/CodexFDE status --short && git -C vendors/flowERP status --short   # 期望均空
git checkout -b lesson-03
```

**采集工具参数化**（§1 决策点 D2，test-first）：`tools/capture_evidence.py` 增加 `--submission-root` 参数，默认 `lesson-02-submission/` 不变（L02 封存证据磁盘合同不动）；行为测试入 `tests/test_l03_spec_parser.py` 脚手架组。工具无 `--help`，用法读源码（handoff 教训）。

**合同测试** `tests/test_l03_spec_parser.py`（文件头放"验收项 → 测试名 → 实际操作 → 比较什么"映射表）：解析器行为组（六类拒绝词面、围栏、只读、字段原文）、CLI 集成组（**经真实子进程**调 `python -X utf8 -m workbench.cli spec`，断言退出码与 JSON 输出——CLAUDE.md 测试口径）、模板与采购稿组（C5/C6 词面断言）、采集工具脚手架组。起点：spec.py 不存在、spec 子命令无效choice——全部目标断言红。

### B. 起始红（commit 1）

```bash
.venv/bin/python -X utf8 tools/capture_evidence.py --submission-root lesson-03-submission red -- \
  .venv/bin/python -X utf8 -m unittest discover -s tests -v -p "test_l03_*.py"
```

（参数确切形状以源码为准；期望：测试被发现，失败集中在**解析器与 spec 入口缺失**，退出码非零。）**commit 1**（采集工具参数化 + 合同测试 + 红证据）。

### C. 需求访谈：八问 → V1 → 反例 → 确认版

**八问先答后写**（上游手册第 2 步原表）：谁使用导出结果作什么决定 / 一行代表商品、仓库、仓位还是批次 / 可用量怎样计算 / 导出哪些列什么顺序 / 多行按什么排序 / 没有库存时输出什么 / 写出失败后旧文件与半成品怎样处理 / 成功或失败后哪些业务数据不能改变。用户逐条答，Claude 整理进 `decisions.md`——**回答原文逐字入表**（L02 code-review P1 教训：改写即退回重采），模型建议单独标注不冒充决定；无法核实的标"待确认"。

**设计判断**（上游 3.0 同形）：Claude **只读**调查 `vendors/flowERP/`（库存查询/数据访问现状）与本仓库 `workbench/`（spec 入口落点），区分当前事实与拟新增；三职责（入口、查询、文件输出）拟落 `flowerp/`（L04 实现），工作台不复制库存业务逻辑。调查结论入 `decisions.md` 设计决定表，关键取舍列候选表（候选/取舍/依据/防止什么错误）**交用户逐条确认**。

**V1 → 反例 → 确认版**：Claude 按已确认决定起草 `FDE_SPEC.md`（六段：来源、目标、非目标、约束、验收用例、完成定义），保存不可覆盖的 `FDE_SPEC-v1.md` 副本；随后按 D6 四步法做反例审查——固定反例："同一 SKU 分布在两个仓库、三个仓位、两个批次时，合同究竟要求输出几行？"指出 v1 中可产生两种实现的句子，**只指认不代决**；用户作决定、理由入 `decisions.md`、修订为确认版。确认版须含上游口径：八列 `sku,name,site,location,lot_id,on_hand,reserved,available`；在库 8 预占 3 → 可用 5（西仓 4、1 → 3，两行不合并）；倒序输入与同 SKU 多仓位批次复验排序；空库存出八列表头；中文逗号引号换行 CSV 读回保真；写出失败明确报错、旧有效文件原样、无半成品；成败均不改库存与流水。仍存"待确认"则整体标 draft，不虚构确认人与日期。

### D. 模板抽取 + 采购迁移

Claude 从确认版抽 `workbench/templates/SPEC_TEMPLATE.md`（六标题 + 填写提示，删除一切库存答案），再用模板起草 `lesson-03-submission/procurement-draft.md`（采购对象/状态/字段无来源即"待确认"，不复制库存口径）。用户只查两件事：模板无 `available`/仓位/批次残留；采购稿结构同构而业务口径独立。

### E. 解析器实现（配合点 2）

> 🤝 **配合点 2（需用户显式调用）**：用户调用 `/mattpocock-skills:implement` 授权执行。Claude 在其中**自调** `codebase-design`（解析器接口与 REGISTRY 缝设计——本讲重头）、`tdd`（红→绿）；遇障自调 `diagnosing-bugs`。**未经用户显式同意不得裸做。**

最小实现 `workbench/spec.py` 解析半边（D5）：`REQUIRED_SECTIONS` 六章、`ParsedSpec` 六字段 + `as_dict()`、`_contract_headings()` 围栏屏蔽（未闭合报错、围栏内标题不误认）、`parse_spec()` 六类拒绝（错误词面与 vendor 同形："Spec 缺少必要章节：…"等）、`load_spec()` UTF-8 只读。`workbench/cli.py` 沿 REGISTRY 注册缝挂 `spec` 子命令（bootstrap 同形提供 `register_commands` 或独立模块登记）：成功输出六字段 JSON + 退出码 0；`ValueError` 走顶层 JSON 错误契约 + 非零退出码；不新建第二套解析逻辑。

### F. 后绿（commit 2 的证据侧）

红—Diff—绿同一测试命令、observed_at 严格递增：

```bash
.venv/bin/python -X utf8 tools/capture_evidence.py --submission-root lesson-03-submission diff -- \
  git diff -- lesson-03-submission workbench tests tools
.venv/bin/python -X utf8 tools/capture_evidence.py --submission-root lesson-03-submission green -- \
  .venv/bin/python -X utf8 -m unittest discover -s tests -v -p "test_l03_*.py"   # 期望 OK、退出码 0
```

### G. 三份输入复验（observation）

手工复制确认版两份并编辑：`missing-section.md` 删除"## 非目标"整节；`wrong-formula.md` 把公式改成"可用量 = 在库量"。确认版本身**不许改**。三连运行（每份记录输出、退出码、SHA-256 前后对照，经采集工具 observation 相位捕获）：

```bash
.venv/bin/python -X utf8 -m workbench.cli spec lesson-03-submission/FDE_SPEC.md          # 期望 0，六字段
.venv/bin/python -X utf8 -m workbench.cli spec lesson-03-submission/missing-section.md   # 期望非零，指认缺章
.venv/bin/python -X utf8 -m workbench.cli spec lesson-03-submission/wrong-formula.md     # 期望 0，人工退回
```

错误公式版通过解析器**不是程序故障**——人工退回结论（8−3 应为 5，错误公式得 8，违反约束/验收用例哪几条）写入证据账，并留三句话：解析器能证明结构完整或残缺；不能证明库存公式和业务决定正确；L03 仍未证明库存导出功能已实现（留 L04）。之后 **commit 2**（需求工件 + 解析器 + CLI 接线 + Diff/绿/observation 证据）。失败则保留失败，修复后重采，旧记录不删。

### H. 导入任务账

沿用活账本 `.runtime/course/L01-workbench/`（`--owner`/`--actor` 向用户索取，不代填）：

```bash
.venv/bin/python -X utf8 -m workbench.cli workbench-task-create \
  --runtime-dir .runtime/course/L01-workbench --project-id PERSONAL-WORKBENCH \
  --task-id CASE-WB-L03-001 --requirement-id CASE-WB-L03-001 \
  --request "把模糊库存导出需求变成可验收 Spec 并建设工作台结构解析器" \
  --actor "<用户具名>" --spec-file docs/lessons/L03-可验收Spec.md
```

按序导入 red、diff、green 三份 `meta.json`（vendor `import_evidence.py`，`--task-id CASE-WB-L03-001`）；链完整后**再**导入三份输入复验等 observation 记录（observation 不参与链判定，绿后导入安全）。同一 status 查询复验：`--require-task CASE-WB-L03-001 --require-red-green-evidence`，期望 `ok:true`、`evidence_complete:true`、`acceptance:pending_human_review`。

### I. 人审与合并

Claude 对 `master...lesson-03` 自调 `code-review`（双轴）后停在具名验收前——不自行 merge、不代签；发现按 test-first 修复并保留旧证据（L02 复查轮体例）。用户过 §4 清单后：验收行入 `docs/replication/evidence/L03.md` 末尾，`git checkout master && git merge --no-ff lesson-03`（合并信息含验收人），更新 roadmap 阶段 2 状态与讲义导航。

> 🤝 **配合点 3（会话中途换窗时）**：换会话前由用户调用 `/mattpocock-skills:handoff`，不以裸 `/clear` 为默认。

**给 Claude 的任务简报**（粘贴即用）：

> 执行 `docs/lessons/L03-可验收Spec.md` §3.A–H：开 `lesson-03` 候选分支，先给 `tools/capture_evidence.py` 加 `--submission-root`（默认 `lesson-02-submission/` 不变）并写 `tests/test_l03_spec_parser.py`（验收项映射表在文件头），采起始红后 commit 1。需求八问由我答、你逐字整理进 `decisions.md`，设计判断只读调查、候选表**交我逐条确认后才准写入**；V1 起草后按四步反例审查只指认歧义不代决，我决定后才出确认版并保留 v1 副本。到达 §2 配合点 1 和 §3.E 配合点 2 时停下提醒我显式调用对应技能。解析器只做结构半边（六类拒绝 + 围栏语义，错误词面与 vendor 同形），CLI 沿 REGISTRY 缝挂 `spec` 子命令、不做第二套解析；写集限 `lesson-03-submission/`、`workbench/templates/`、`workbench/spec.py`、`workbench/cli.py`、`tests/`、`tools/`、`docs/`。红绿同命令、observed_at 递增；三份输入复验留退出码与 SHA 前后对照，错误公式版通过后由我人工退回、不冒充程序结论。全部命令、输出摘要、退出码如实写入 `docs/replication/evidence/L03.md`（C1..C13 逐项结论表，按 eval 名 `spec_contract_rejects_ambiguity` 登记 C11），失败与原始输出一律保留。vendor 零写回。完成后停在具名验收前，不自行 merge、不代签。

## 4. 人审清单

具名验收前核对 `lesson-03` 分支与 `docs/replication/evidence/L03.md`：

- [ ] commit 形状：commit 1 仅采集工具参数化 + 合同测试 + 红证据；commit 2 为需求工件 + 解析器/CLI + Diff/绿/observation；red → diff → green 的 observed_at 严格递增
- [ ] 起始红有效：失败来自解析器与 spec 入口缺失（C1/C7/C9 对应断言），非环境错/零用例/语法错
- [ ] 红/绿同命令：两份 `meta.json` 的 command 字段一致；commit 2 未放宽测试断言（`git diff commit1 commit2 -- tests/` 无断言改动）
- [ ] `tools/capture_evidence.py` 改动后 L02 封存证据未动（`git status lesson-02-submission/` 干净，磁盘合同向后兼容）
- [ ] `decisions.md`：八问逐条有来源或待确认；用户回答原文逐字未被加工；模型建议未写成人员决定；候选表确认痕迹在场
- [ ] `FDE_SPEC-v1.md` 保留歧义原句；确认版消除且修订理由在 `decisions.md` 可查；待确认项未被虚构确认人或日期
- [ ] 确认版业务口径逐条可检查（C3 八条）；`SPEC_TEMPLATE.md` 无库存残留（C5）；采购稿同构不照抄（C6）
- [ ] 解析器六类拒绝各有可定位词面；围栏内同名标题不制造重复章节；输入文件 SHA-256 前后一致（C7/C8）
- [ ] CLI 真实接线：spec 子命令经 REGISTRY 缝调用 `workbench/spec.py`，错误词面同源，无第二套解析（C9）
- [ ] 三份输入复验：通过 / 拒绝 / 通过+人工退回三态齐，三句话在场；错误公式未被"修好"成解析拒绝（C10）
- [ ] 任务账：CASE-WB-L03-001 红绿链完整、observation 绿后导入、`acceptance: pending_human_review` 原样出现；C11 按 eval 名登记且注明 harness 待建设
- [ ] 写集纪律：全量 `git status` 无 vendor/flowERP 写动；C13 非目标未越界（无库存导出实现、无根 FDE_SPEC.md、无需求生成机器）
- [ ] 证据逐条有输出摘要 + 退出码；末尾具名验收行 + merge 信息含验收人

**红旗（任一出现即退回）**：先写实现后"补拍"起始红；合同测试断言改宽凑绿；把 wrong-formula 改造成解析拒绝来冒充"解析器很智能"（解析器越权当业务裁判）；把"结构通过"说成"业务正确/导出已交付"（分层混装）；加工用户八问原话或虚构确认人/日期；模板里留库存答案或采购稿照抄库存公式；CLI 绕开 REGISTRY 缝私挂命令或复制第二套解析逻辑；为造红灯故意破坏已有正确能力；手改 `meta.json`/`output.txt`/捕获目录；程序或 AI 代签验收。

---

## 具名验收

- 验收人：＿＿＿＿
- 日期：＿＿＿＿
- 结论：接受 / 退回（理由：＿＿＿＿）

---

## 附录 A：Java 重走移植注记（ADR-0006）

> 状态：**计划草案，待用户审定；审定前不动手**。决策与词汇见 ADR-0006 与 CONTEXT.md（重走 / 移植注记 / 对照基准）。§1–§4 为 Python 口径的历史对照基线，不重写；本附录只记录 Java 重走侧的翻译点与增量。重走证据落 `docs/replication/evidence/L03-java.md`（原 `evidence/L03.md` 不重开）。上游现查（2026-09-29）：`origin/main` = `7f67533`，与本讲对照基线一致（检查点 0002 后无新变更，冻结合同零变化），不触发检查点采纳。

### A1 讲解：本讲重建什么

L03 是重走线**第一个有实质实现代码增量的讲**。Python 时代交付 `workbench/spec.py` 六段式解析半边 + `workbench.cli` 的 `spec` 结构闸门；Java 侧重走 = 把冻结的解析半边按原讲合同在 `src/main/java` 重建（新包 `workbench/spec`），直接踩 L01-java 已建载体：`CommandRegistry` 注册缝（Main 静态块 +1 注册调用，镜像 cli.py 的 +2 行）、`JsonOut`/`PyJson` 输出包络、`Args` 参数解析、`Cli` 测试支撑（真实子进程）。红点 = Java `spec` 命令能力缺失（天然起始红，L01 形态）。

与 L02 的根本差异：L02 无代码增量，重建对象是规则文本对 Java 线的失真修正；L03 的代码增量真实存在，而**需求文档半边的内容已定**——八问、v1→反例→确认版、反例裁定 F2–F4 均为 Python 时代具名验收的历史事实（`lesson-03-submission/` 五工件 + `evidence/L03.md` §0）。重建口径四层：

1. **代码半边真实重建**：解析器（六类拒绝词面逐字同形 + 围栏语义）+ CLI 适配器（handler 层接住结构错误，P5 追认移植，见 A6.1）。
2. **golden l03 前置**（冻结 Python 采出，A3）：与 l01/l02 的关键差异——l01/l02 重放的是既有账本能力（commit 1 即绿）；l03 重放的**正是本讲目标能力**，`GoldenL03ReplayTest` 在 commit 1 是**红点参与者**（A2 映射表声明），commit 2 转绿即字节级对照达成，是本讲最强的验收证据。
3. **文档半边零重写 + 护栏**：五工件不动；护栏断言（在场性 / 残留零命中）就位即绿；结构半边（可解析性）经 Java CLI live 重验 + golden 字节锁。
4. **裁定移植**（A6）：D1–D6、复查轮 P3/P5 追认项、六类词面与检查顺序、C10 三句话、S1 单一来源纪律。

不重建：八问访谈与反例审查**过程**（结论承袭，不重采）；`build_delivery_spec` 需求生成机器（D5，L04 再定）；根 `FDE_SPEC.md`（D4）；Java 侧 eval.harness（双轨口径：Python 侧已建成冻结，Java 侧待建设，如实注明）；FlowERP 业务（C13）；Python 冻结实现面与 Python 时代账本。

### A2 合同翻译（C1–C13）

| # | Python 口径（§2 原文） | Java 重走口径 | 变化类型 |
|---|---|---|---|
| C1 | 起始红 = `test_l03_*.py` 非零；红点 = spec.py / spec 子命令 / 工件缺失 | 起始红 = `mvn test`（全量，与后绿同命令）非零；红点 = L03 合同测试目标组（spec 命令 invalid-choice、解析行为 gap、golden l03 重放不齐、模板 Java 拷贝缺失）；就位类（文档护栏 + fixture 机检 + golden l01/l02）即绿，映射表声明（Python L02 ReferencedCommandsAreReal 先例）。合同测试全部经真实子进程 CLI 驱动（测试口径），不直引内部类 → 起始红无编译依赖 | 红点重定位 |
| C2 | `decisions.md` 八问逐条有来源或待确认 | 原件零重写（护栏：在场 + 来源结构断言）；Java 侧增量 `lesson-03-submission/java/decisions-java.md`——记录 A6 裁定与执行期裁定（owner/actor、配合点复认），指针化不复制原件 | 增量 + 指针 |
| C3 | 确认版经 spec 入口解析通过 + 八条业务口径可检查 | 结构半边：Java CLI live 解析确认版（rc 0 六字段）+ golden t01 字节锁；业务半边：文档零重写，八条口径在场为护栏断言（不重裁定）；F2/F3/F4 反例裁定承袭 | 拆分：结构重验 / 业务护栏 |
| C4 | v1 保留歧义原句，修订理由在 decisions.md 可查 | 原件零重写；护栏断言（v1 歧义句在场、确认版已消除）；问答不重跑 | 护栏化 |
| C5 | 模板无库存残留 | JD3 双载体：`src/main/resources/templates/SPEC_TEMPLATE.md` 逐字拷贝 + 机检测试锁字符串等价；C5 断言读 Java 拷贝（六标题在场 + 库存词面零命中）→ commit 1 红点（目标工件缺失）；Python 原件零触碰（护栏：残留零命中即绿） | 双载体新增 |
| C6 | 采购草稿六段被同一解析器接受、不照抄 | 内容面护栏（"待确认"在场 + 库存词面零命中，就位绿）；结构半边重验（Java CLI live + golden t05） | 重验 |
| C7 | 六类拒绝词面与 vendor 同形 + 围栏语义 | Java 解析器逐字同形：六词面 + 检查顺序（未知→重复→缺章→乱序→空章，围栏未闭合先行）+ 围栏屏蔽 / 围栏内标题不误认且正文保留（检查点 0002 教学节）；golden t02/t06–t11 字节锁 | 无（逐字移植） |
| C8 | 输入前后 SHA-256 一致、失败不改写不留部分状态 | live 复验（三份真实输入 SHA 前后一致，observation 封条）+ 合同测试（tmp 拷贝）；已知行为边界（JD6）：非 UTF-8 解码与文件不存在 errno 词面系语言绑定，如实记录偏差——JSON 形状与 rc 1 保持，不入 golden、不伪造 Python 词面 | 重验 + 边界记录 |
| C9 | spec 经 REGISTRY 缝实际调用解析器、无第二套解析 | `SpecCommands.register(CommandRegistry)`（Main 静态块 +1 调用）；适配器转发解析器异常原词面（同源，非第二套解析）；合同测试经真实子进程 | 无（同形移植） |
| C10 | 三份输入复验三态 + 三句话 | Java CLI live 三态复验（observation 封条）+ golden t01/t02/t03 字节锁；三句话入 L03-java 证据账；错误公式**人工退回结论承袭** Python 时代具名验收（evidence/L03.md §5 验收段），具名验收时随验收行复认，Java 侧只重验"结构通过"事实 | 重验 |
| C11 | eval 名由缺项拒绝用例承载、harness 待建设注明 | 同 D1 移植：`spec_contract_rejects_ambiguity` 由 C7 缺项用例承载，证据账按 eval 名登记；双轨口径如实注明（Python 侧 harness 已建成冻结 / Java 侧待建设） | 无（口径更新） |
| C12 | 写集 = `lesson-03-submission/`、`FDE_SPEC.md`（词面）、`workbench/templates/`、`workbench/spec.py`、`workbench/cli.py`、`tests/`、`tools/`、`docs/`；任务账 + 验收 | 写集映射：`src/main/java/workbench/spec/**`（↔spec.py）、`src/main/java/workbench/cli/Main.java`（↔cli.py，+注册）、`src/main/java/workbench/bootstrap/Args.java`（位置参数扩展，JD2）、`src/main/resources/templates/**`（↔templates，JD3）、`src/test/java/**`（↔tests/）、`tools/generate_golden_l03.py`（↔tools/ 脚手架位）、`lesson-03-submission/**`（java/ 子根 + decisions-java.md）、`docs/**`；scope 词面 `FDE_SPEC.md` 与模板原件以"零重写在场"满足。pom.xml 预期零改动（无新依赖；resources 为 Maven 标准目录）。任务账 `CASE-WB-L03-JAVA-001` @ `.runtime/course/L01-workbench-java/`（沿用），链命令 `mvn test`，Java `ImportEvidence` 导入，observation 绿后导入，status 四词面；证据落 `evidence/L03-java.md`；`--no-ff` 含验收人 | 写集重定 |
| C13 | 非目标 | 原 §2 列表 + Java 增项：不触碰 Python 冻结实现面与 Python 账本、不建 Java eval.harness、不越 D5 复刻生成机器、不重跑八问/反例过程、不把结构通过说成业务正确/导出已交付 | 增量 |
| — | （Python 时代无此项） | **golden 对照门**：golden l03 由冻结 Python 前置采出（A3），Java 重放字节级一致入验收门；重放测试 commit 1 红为本讲预期红点（与 l01/l02 即绿不同，映射表声明） | 重走线增量 |

### A3 对照基准（golden）方案

生成（审定后、implement 授权前；冻结 Python 版仍可跑。工具 `tools/generate_golden_l03.py`，镜像 l02 生成器，只用标准库）：

- 工作区 `.runtime/golden-l03/`（inputs 固定件；raw 落 gitignore）；输出 `src/test/resources/golden/l03/`；规范化与掩码规则与 l01/l02 逐字相同（`<TS>`/`<WORKBENCH_ID>` 掩码、canonical JSON）；manifest 保留 normalization 块（GoldenReplay 断言其在场）
- 输入固定件三源：①冻结三工件（`lesson-03-submission/FDE_SPEC.md`、`missing-section.md`、`wrong-formula.md`，生成时读取内容冻结入 manifest——golden 直接封存本讲自己的三份输入）；②合成夹具（六类拒绝面，生成器常量，镜像讲义 §3.G"手工复制并编辑"）；③冻结采购稿（C6 结构重验）
- 幂等双跑，golden 目录 SHA-256 两次一致入证据账 §G；golden 连同生成器前置落 master（L01/L02 先例），候选分支自 master 分出即继承；生成后永不手改
- 场景面 = spec 命令结构闸门（纯解析，无账本场景；errno 类场景不入 golden，见 JD6）

场景清单（11，单命令粒度——L02 §G 教训）：

| 组 | # | 输入 | 预期 rc | 覆盖面 |
|---|---|---|---|---|
| 通过面 | t01 | 冻结确认版 FDE_SPEC.md | 0 | 六字段原文 + `ok:true`（C3 结构半边） |
| | t02 | 冻结 missing-section.md | 1 | 缺章拒绝词面（Python 时代先例："Spec 缺少必要章节：非目标"）+ C11 eval 承载 |
| | t03 | 冻结 wrong-formula.md | 0 | 结构通过 ≠ 业务正确（C10 错误公式态） |
| | t04 | 合成：约束节含围栏示例，围栏内有 `## 目标` | 0 | 围栏屏蔽：围栏内标题不误认（检查点 0002 教学节） |
| | t05 | 冻结采购稿 | 0 | C6 结构半边重验 |
| 拒绝面 | t06 | 合成：六段齐全但"目标"正文为空 | 1 | 空章词面 |
| | t07 | 合成：重复"## 目标" | 1 | 重复章节词面 |
| | t08 | 合成：六段乱序 | 1 | 顺序错误词面（应为/实际，`→` 连接） |
| | t09 | 合成：六段 + "## 附录" | 1 | 未知章节词面 |
| | t10 | 合成：``` 围栏未闭合 | 1 | 围栏未闭合词面 |
| | t11 | 合成：空文件 | 1 | 缺章全列表词面（六段 `, ` 连接格式） |

比对（进 JUnit）：新增 `GoldenL03ReplayTest` 委托 `GoldenReplay.replay`（scratch = `.runtime/golden-l03/inputs`；本套无 tamper/`assert_db_absent` 标记——spec 命令不触账本）。预期共享件零改动；若重放循环需为无账本场景微调，改动须行为零变化并留证据（l01/l02 重放回归）。golden 生成后永不手改；要变只能重生成并留证据。

### A4 实操流程（增量于 §3）

```bash
# 0. 审定后、动手前（前置落 master，L01/L02 先例）
#    - 附录 A 审定稿入讲义 → master
#    - tools/generate_golden_l03.py 生成 golden/l03（双跑指纹）→ 连同 §G 记录落 master
# 1. git checkout -b lesson-03-java
# 2. 【配合点 1 复认】to-spec 降级：每次重新确认（L02-java 先例），起始红采前问
# 3. commit 1 = L03 Java 合同测试（红点组 + 就位组，文件头映射表）+ GoldenL03ReplayTest
#    + 起始红封条（tools/capture_evidence.py --submission-root lesson-03-submission/java red -- mvn test）
# 4. 【配合点 2】implement：用户显式调用后动笔——SpecParser + SpecCommands（JD1）+
#    Args 位置参数（JD2）+ 模板双载体（JD3）；自调 codebase-design（解析器接口/缝设计）、tdd（红→绿）
# 5. diff + green：mvn test 同命令，observed_at 严格递增；新增件为主 → 先 git add -A 再
#    git diff --cached（Python 时代台账 #4 同口径，如实披露）
# 6. 三份输入复验（observation 封条：./bin/wb spec ×3，预期 rc 0/1/0）+ C8 SHA-256 前后对照
#    + 全量 mvn test 回归 observation；三句话入证据账
# 7. commit 2 = 实现侧 + Diff/绿/observation 证据
# 8. 任务账：CASE-WB-L03-JAVA-001 @ .runtime/course/L01-workbench-java/（owner/actor 执行前索取，
#    不代填）；导入前查缺链（预期 rc 1）→ 链三封条 Java ImportEvidence → obs 绿后导入
#    → status 四词面复验
# 9. code-review 双轴自调（master...lesson-03-java）→ test-first 修复、旧证据保留 → 重采追加导入
#    → 触点 3 具名验收 → git merge --no-ff lesson-03-java（合并信息含验收人）→ roadmap/讲义导航收口
```

纪律注：`.idea/` 与仓库根 `java/` 的 .gitignore 归置是 L02-java 遗留待办，非本讲写集，不顺手做。

### A5 人审清单增量（叠加 §4）

- [ ] Python 冻结实现面零触碰：`git diff master...lesson-03-java -- workbench tests pyproject.toml tools/capture_evidence.py` 为空；`.runtime/course/L01-workbench/` 无写入；pom.xml 零改动（如偏离须逐条说明）
- [ ] 需求工件零重写：五工件 + Python 模板原件 diff 为空（java/ 子根与 decisions-java.md 除外）
- [ ] golden l03 非手改：生成命令 + 双跑指纹一致在 §G；l01/l02 golden 零触碰；重放测试 commit 1 红在场（红封条含 GoldenL03ReplayTest 失败）
- [ ] 六类拒绝词面与 vendor 逐字同形：golden 字节锁 + 合同测试断言双证；检查顺序与冻结版一致
- [ ] 已知行为边界（JD6）在证据账如实记录：errno/解码词面偏差，不伪造 Python 词面
- [ ] `mvn test` 全绿（40 + 新增，实际数执行时核对）+ golden l01/l02/l03 三重放绿 + 账本链四词面（ok / evidence_complete / pending_human_review / flowerp_connected:false）
- [ ] C10 三句话 + 人工退回结论复认在场；C11 按 eval 名登记且注明 Java 侧 harness 待建设
- [ ] 红旗（新增）：把结构通过说成业务正确/导出已交付；为凑字节对照伪造 Python errno/codec 词面；越 D5 边界复刻 build_delivery_spec；重跑或改写八问/反例历史；golden 手改

### A6 裁定表（随触点 1 逐条裁定，未确认不动笔）

**A6.1 Python 时代裁定移植表**：

| 裁定 | 处置 | Java 口径 |
|---|---|---|
| D1 eval 承载方式 | 移植 | 合同测试承载 + eval 名登记；harness 双轨口径如实注明 |
| D2 采集工具参数化 | 已就位 | 工具冻结复用；`--submission-root lesson-03-submission/java`（完整相对路径——L02 错位封条教训） |
| D3 `claude -p` 行为实验降为可选 | 移植 | **L03-java 无 N0/N1 行为对照会话**（本讲核心证据 = 纯程序红绿 + 三份输入复验） |
| D4 根 FDE_SPEC.md 不建 | 移植 | scope 词面以确认版原件在场满足；根合同留 L04 按真实需求建立 |
| D5 只复刻解析半边 | 移植 | `build_delivery_spec` 等生成机器 L04 按合同再定 |
| D6 反例审查四步法参照 | 移植 | 方法论已行使完毕（Python 时代 F1–F4）；Java 时代不重跑 |
| P3 输出包络含 `flowerp_connected:false` | 移植 | Java 输出同包络（`Ledger.FLOWERP_CONNECTED` 单一来源——S1 教训移植为起始纪律） |
| P5 结构错误 handler 层接住 | 移植 | SpecCommands 自接解析/IO 异常：`{"ok":false,"flowerp_connected":…,"error":<原词面>}` + rc 1，不走顶层异常边界（避免合同拒绝被误标"内部错误"） |
| 六类拒绝词面 + 检查顺序 + 围栏语义 | 移植（逐字） | 词面常量与顺序未知→重复→缺章→乱序→空章（围栏未闭合先行）；围栏正文保留 |
| C10 三句话 + 人工退回结论 | 承袭 | 三句话入 L03-java 证据账；退回结论承袭 Python 具名验收，验收行复认 |
| F2/F3/F4 反例裁定 | 承袭 | 已固化在确认版与 decisions.md，零重写 |
| diff 相位 `--cached` 口径（台账 #4） | 移植 | 新增件为主 → 先暂存取全量 diff，如实披露 |
| 八问访谈/反例审查过程 | 不移植 | 结论承袭；过程属一次性人决，不重采 |
| Python 起始红的工件缺失类红点 | 不移植 | 五工件已存在；红点重定位为 Java 能力缺失（A2 C1） |

**A6.2 Java 时代新决策**（候选，推荐口径随触点 1 批量裁定）：

| # | 决策 | 推荐 | 备选 |
|---|---|---|---|
| JD1 | 解析器落位 | 新包 `workbench/spec`：`SpecParser`（REQUIRED_SECTIONS / ParsedSpec / contractHeadings / parse / load，解析半边）+ `SpecCommands`（CLI 适配器与注册）；Main 注册缝 +1 调用；分层 cli → spec，不触 sqlite | 塞进 bootstrap 包（与 L01 命令混杂，分层失真，不可取） |
| JD2 | Args 位置参数扩展 | Args 增加位置参数收集（spec_path），用法词面沿 argparse 同形；既有 40 用例零改动保持绿（回归保证） | SpecCommands 内手工劈参（两套解析习惯，不可取） |
| JD3 | 模板落位 | 双载体：`src/main/resources/templates/SPEC_TEMPLATE.md` 逐字拷贝 + 机检测试锁字符串等价（铁律 4 CourseContracts 先例）——Python 退役后工作台保有模板 | 仅引用 Python 原件（L06 退役时模板随 workbench/ 灭失，需退役时再迁） |
| JD4 | golden l03 方案 | A3 全案（11 场景、生成器镜像 l02、前置落 master、重放 commit 1 红为预期红点） | 缩面（六类拒绝不全锁，词面回归只剩合同测试单证） |
| JD5 | 需求工件处置 | 零重写 + 护栏 + 结构重验；Java 侧决定记录 = `lesson-03-submission/java/decisions-java.md`（A6 裁定 + 执行期裁定，指针化） | 重采八问/重跑反例（重开历史，不可取） |
| JD6 | 已知行为边界 | 非 UTF-8 解码与文件不存在 errno 词面如实记录偏差（JSON 形状 + rc 1 保持）；不入 golden、不伪造 Python 词面（L01-java parseAware 先例） | 在 Java 侧拼装 Python errno 文本（伪装同形，不可取） |
