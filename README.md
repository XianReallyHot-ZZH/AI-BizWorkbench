# AI-BizWorkbench

以 [CodexFDE](vendors/CodexFDE)（个人 AI 研发工作台 + 课程仓库）为对照的**渐进式复刻**：
逐讲重建一套属于自己的、日常可用的个人研发工作台——Harness + 记忆系统 + 工作流蒸馏，
最终用它组织人与 AI 协同开发客户项目。课程逐讲（L00–L16）是路径，不是终点。

```
本仓库（工作台 + 复刻产物）
   │ 只读对照                    只读对照
   ├── vendors/CodexFDE ──── 课程合同 · 参考实现 · 讲义（submodule，冻结于 58f4612）
   └── vendors/flowERP ──── 客户项目（submodule，L04 起由工作台首次驱动）
```

复刻的是**方法与能力**，不是克隆上游终态。词汇表见 [CONTEXT.md](CONTEXT.md)，
关键决策见 [docs/adr/](docs/adr/)。

## 当前状态

| 阶段 | 内容 | 讲 | 状态 |
|---|---|---|---|
| 0 | 双仓库环境自检 | L00 | ✅ 已验收 2026-09-25 |
| 1 | 工作台自举：任务账、命令证据账、完整性检查 | L01 | ✅ 已验收 2026-09-26 |
| 2 | 仓库规则 + 六段式 Spec 解析器 | L02–L03 | 未开始 |
| 3 | V0 受控执行；登记 flowERP（换挡点） | L04 | 未开始 |

完整路线与阶段检查点：[docs/replication/README.md](docs/replication/README.md)。

## 快速开始

```bash
python3 -m venv .venv && .venv/bin/pip install -e .

# 全量测试（每讲合同测试 + 冻结合同 fixture）
.venv/bin/python -X utf8 -m unittest discover -s tests

# 用工作台查回它自己的建设记录（自举）
.venv/bin/python -X utf8 -m workbench.cli workbench-status \
  --runtime-dir .runtime/course/L01-workbench \
  --require-project PERSONAL-WORKBENCH --require-task CASE-WB-L01-001 \
  --require-red-green-evidence
```

运行库在 `.runtime/`（不入库）；`acceptance` 会停在 `pending_human_review`——
材料完整不等于有人接受，**待审核 ≠ 已接受**。

## 仓库导览

| 路径 | 内容 |
|---|---|
| [workbench/](workbench/) | 目标产物本体：CLI 入口、五命令账本、冻结合同 fixture |
| [docs/adr/](docs/adr/) | 复刻决策记录（全新实现 / Claude 执行器 / 检查点 / 候选分支 / 客户真理） |
| [docs/lessons/](docs/lessons/) | 每讲复刻讲义（四段结构）+ 技能编排表 |
| [docs/replication/](docs/replication/) | 路线图、状态、逐讲证据账（evidence/LNN.md） |
| [lesson-01-submission/](lesson-01-submission/) | 证据捕获原始记录（meta.json + output.txt，勿移动） |
| [learning/](learning/) | 教学工作区：使命、课程、学习记录 |

## 工作方式（一分钟版）

每讲一个 `lesson-NN` 候选分支：先落**起始红**（失败合同测试），实现后用**同一命令**
红转绿，证据落 `docs/replication/evidence/`；全部验收门通过也只是**待审核**，
由仓库所有者**具名验收**后合并回 master，合并信息即验收记录。
AI 自述与截图不算证据；vendors/ 目录零写回。

详见 [CLAUDE.md](CLAUDE.md)（面向 AI 协作者的完整约定）。
