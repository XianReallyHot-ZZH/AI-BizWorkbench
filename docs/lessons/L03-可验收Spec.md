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
