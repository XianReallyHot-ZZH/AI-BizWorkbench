# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 本仓库是什么

CodexFDE（个人 AI 研发工作台 + 课程仓库）的**渐进式复刻**：以 `vendors/` 下两个 submodule 为只读对照，逐讲（L00–L16）重建属于自己的、日常可用的工作台。课程逐讲是路径，不是终点。对照基线冻结于 `CodexFDE@58f4612`，上游变更只在检查点显式采纳（ADR-0003），不自动同步。

词汇表在根目录 [CONTEXT.md](CONTEXT.md)——**措辞必须继承它**（复刻/上游/候选分支/起始红/具名验收/证据/合同 fixture），避免各词条的 _Avoid_ 替代词。

## 铁律（先读这里）

1. **`vendors/` 只读**。CodexFDE 与 flowERP 是 submodule，任何复刻产物不得写回。`git -C vendors/CodexFDE status --short` 与 flowERP 同查必须恒空。
2. **待审核 ≠ 已接受**（逐字使用）。候选通过全部门槛也只是待审核，直到用户具名验收。Claude 不代签、不自行 merge。
3. **证据 = 命令 + 退出码 + 失败后权威状态**。AI 自述、绿色截图不算证据；失败记录一律保留，不删不改凑通过。
4. **冻结合同 fixture**（`workbench/course_contracts.py`）是上游 LESSONS 数据的逐字拷贝，不许"修好"它——发现与上游分歧走检查点显式采纳。
5. **用户显式技能**（`/mattpocock-skills:to-spec`、`:implement`、`:handoff`、`:teach`）到点必须停下提醒用户调用；降级路径仅在用户明确同意后走（见 docs/lessons/README.md 技能编排表）。
6. 换会话用 `/mattpocock-skills:handoff` 生成交接文档，不以裸 `/clear` 为默认。

## 常用命令

```bash
# 环境（Python ≥3.10，只用标准库；本机 3.13 与课堂 3.11 的偏离记录在证据即可）
python3 -m venv .venv && .venv/bin/pip install -e .

# 全量测试（当前门 = 合同测试；eval harness 属 L05+，尚不存在）
.venv/bin/python -X utf8 -m unittest discover -s tests

# 单文件 / 单用例
.venv/bin/python -X utf8 -m unittest discover -s tests -v -p "test_l01_*.py"
.venv/bin/python -X utf8 -m unittest discover -s tests -k wb07 -v

# 工作台 CLI（运行库 .runtime/，已 gitignore）
.venv/bin/python -X utf8 -m workbench.cli workbench-status \
  --runtime-dir .runtime/course/L01-workbench \
  --require-project PERSONAL-WORKBENCH --require-task CASE-WB-L01-001 \
  --require-red-green-evidence

# 看账本（只读；status 会重算 SHA-256 复核，勿手改）
sqlite3 "file:.runtime/course/L01-workbench/workbench.db?mode=ro"
```

证据采集/导入用 vendor 工具（只执行不修改）：`vendors/CodexFDE/docs/courses/L01/tools/evidence.py` 与 `import_evidence.py`（后者用 `sys.executable`，必须以本仓库 `.venv` 的 python 运行）。捕获目录 `lesson-01-submission/` **随 Git 提交且永不移动**——meta.json 里的 `output_file` 是绝对路径，移动即断封条。

## 每讲工作流（候选分支机制，ADR-0004）

1. **讲前**：`git -C vendors/CodexFDE fetch` 现查上游（勿信快照），起草 `docs/lessons/LNN-标题.md`（四段结构见 docs/lessons/README.md），**用户审完讲义才动手**。
2. **候选分支 `lesson-NN`**：commit 1 = 合同测试（起始红）+ 红证据；实现后采 Diff + 后绿（与红**同一测试命令**、observed_at 严格递增）再 commit 2。起始红必须先于实现真实存在。
3. **证据**：`docs/replication/evidence/LNN.md`——C 编号逐项结论表 + 命令台账（输出摘要 + 退出码）+ 失败现场 + 末尾具名验收行。
4. **验收与合并**：用户按讲义 §4 清单核对后，验收行入证据账，`git merge --no-ff lesson-NN`（合并信息含验收人与结论），更新 roadmap 状态。
5. 验收前 Claude 自调 `code-review` 复查整条候选分支；发现按 test-first 修复并保留旧证据。

## 架构大图

```
本仓库（工作台 + 复刻产物）
├── workbench/            目标产物本体
│   ├── cli.py            入口：REGISTRY 注册表缝 + 顶层异常边界（JSON 错误契约）
│   ├── bootstrap.py      L01 五命令：任务账 + 命令证据账 + 完整性检查（四职责见模块 docstring）
│   └── course_contracts.py  冻结合同 fixture（16 讲 LESSONS 逐字数据）
├── tests/                每讲合同测试（文件头有 验收项→测试 映射表）+ fixture 完整性测试
├── docs/
│   ├── adr/              复刻决策（0001 全新实现 / 0002 Claude 执行器 / 0003 检查点 / 0004 候选分支 / 0005 客户真理）
│   ├── lessons/          每讲复刻讲义 + 技能编排表（讲前 just-in-time 产出）
│   └── replication/      路线图与状态 + 每讲证据账（evidence/LNN.md）
├── lesson-01-submission/ evidence.py 原始捕获（meta.json + output.txt，入库不移动）
└── learning/             教学工作区（使命/课程/学习记录，服务"验收人读懂代码"）

vendors/CodexFDE          只读：课程合同、参考实现、讲义、L01 证据工具
vendors/flowERP           只读：客户项目，L04 才由工作台首次驱动（客户真理：其自身 eval 才是业务权威）
```

**信用内核**（L01 钉死，后续讲次全部踩在上面）：账本只追加、失败不可抹、快照不随原文件变、`status` 每次重算 SHA-256 复核、链判定三规则（同命令红绿 / Diff 严格居间 / 全局最新须为成功绿）、`acceptance: pending_human_review` 恒待人签。改动任何一处都要意识到全链信用随之变动。

**换挡点**：L01–L03 由人直接监督 AI 建工作台；L04 起改为"通过工作台组织协同"——届时执行器合同（写集、预算、JSON 事件流、沙箱、退出码）按 ADR-0002 映射到 Claude Code 权限机制。
