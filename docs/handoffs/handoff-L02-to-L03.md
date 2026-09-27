# Handoff：AI-BizWorkbench — L02 已收口，下一会话进入 L03

- 生成：2026-09-27，上一会话完成 L02 全流程（检查点 0001 → 起草 → 执行 → code-review 修复轮 → 具名验收 → 合并）
- 本文件只做**接力**；事实以仓库 canonical 文件为准，不要轻信本文件的转述

## 仓库当前状态（均已提交，master 干净）

- L02 已具名验收合并：`97fc4d1`（merge --no-ff，验收人 XianReallyHot-ZZH）+ `b0d8286`（roadmap/导航标记）。master 34/34 绿
- 本地领先 origin 约 17 提交，**未 push**（用户未要求）
- 检查点 0001 已完成：pin `58f4612 → a74445a`，changelog 在 `docs/research/codexfde-orientation.md` §10。**CLAUDE.md 与 roadmap 的基线行已指针化**（指向 §10），不再存 SHA——下次检查点只改 §10
- L02 交付物：CLAUDE.md 三段规则（五条 FlowERP 边界+适用条件 / DoD / 结构约定）、`tools/capture_evidence.py`（证据封存工具，磁盘合同与 vendor evidence.py 同形，硬编码捕获根 `lesson-02-submission/`）、`tests/test_l02_workbench_rules.py`（5 项）、`lesson-02-submission/`（封条捕获，勿移动）
- 任务账：`.runtime/course/L01-workbench/` 现有 `CASE-WB-L01-001` + `CASE-WB-L02-001`（13 条记录，链完整，验收已签）
- L01 捕获目录里有一条 2026-09-27 披露的杂项记录（rc=127，工具误用），已录入 `docs/replication/evidence/L01.md` 附记——审计时勿当异常

## 下一会话任务：起草 L03 讲义（用户审完才动手）

按 CLAUDE.md「每讲工作流」：

1. `git -C vendors/CodexFDE fetch && git rev-parse origin/main` 现查上游——若 ≠ `a74445a`，停下向用户报告，走 ADR-0003 检查点裁决（更新 §10 changelog），不要静默跟进
2. 读上游 L03 材料：`vendors/CodexFDE/docs/courses/L03/`（上一会话**未读过** L03 材料，全部现场读；labs 模板在 `docs/courses/labs/L03/`）
3. 起草 `docs/lessons/L03-*.md`（四段结构见 `docs/lessons/README.md`，参照 L02 讲义成稿）

### L03 已知合同事实（详见 `workbench/course_contracts.py` L03 条目，逐字以它为准）

- **第一个带绑定 eval 名的讲**：`spec_contract_rejects_ambiguity`——复刻全程要开始回答"绑定 Eval 怎么落地"（此前 C10 惯例是"不实现 eval.harness"，L03 这个 eval 名怎么处置是**显式决策点**，L02 先例是记为非目标，L03 是否沿用需与用户确认）
- write_scope 含 `workbench/spec.py` + `workbench/cli.py`——**工作台本体第一个命令/模块增量**（REGISTRY 缝首次扩新命令，正是 L02 刚写进 CLAUDE.md 的结构约定第一次实战）
- scope 另含 `FDE_SPEC.md`、`lesson-03-submission/`、`workbench/templates/SPEC_TEMPLATE.md`、`tests/`；refs `SKU:COURSE-DEMO`
- 同形映射点：上游 `lesson-03-submission/` 有签署样例与 `missing-section.md` 缺项反例（`vendors/CodexFDE/lesson-03-submission/`，仓库根），可作对照

### L03 已知设计决策点（起草时显式交用户）

- **采集工具去重**：`tools/capture_evidence.py` 硬编码 `lesson-02-submission/`，L03 需要 `lesson-03-submission/`——L02 复查修复轮 S3 留的尾巴，现在到期。选项：(a) 加 `--submission-root` 参数（推荐，向后兼容 L02 记录）；(b) 再镜像一个工具。改动按 test-first，旧证据不动
- `claude -p` 行为实验在 L03 是否还需要（上游 L03 有同伴歧义标注环节，映射待设计）
- N0/N1 式逐字请求教训直接适用：**请求原文逐字是硬要求**（L02 code-review P1：v1 改写了请求首行被退回重采）

## 上一会话的经验教训（细节见证据账，此处只点名字）

- `claude -p`：单轮会话；跑 5 分钟+ 会转后台任务（等通知，别轮询）；模型词面 `unrecognized_model` 警告属网关常态，argv 留证为准；模型会自称"交互式会话"与实际不符——采集记录权威；两次对照会话配置必须逐字一致（同 `--model`/plan mode/工作目录），模型 ID 先问用户
- observation 相位不参与链判定（`workbench/bootstrap.py:146`）——绿后导 observation 安全，但新的 red/diff/green 会使旧绿失权（L02 修复轮就是这么重采的：同命令新 Diff/绿，旧记录全保留）
- 规则/取舍类内容**先列候选表交用户逐条确认后才写入**（上游"学生亲自决定"的同形映射，L02 已成惯例）
- `evidence.py`/`capture_evidence.py` 都无 `--help`，探测即真实执行——用法读源码
- 请求里的多词变量会踩 zsh 不拆分坑：命令一律写全字面量；heredoc 传多行请求可靠

## 配合点（L03 相关，届时停下提醒用户）

- `/mattpocock-skills:to-spec`（第 2 段合同；降级需用户**本讲**明确同意——每讲重新确认，L02 的同意不延续）
- `/mattpocock-skills:implement`（§3 执行时，用户显式）
- 模型自调：`tdd`（红绿循环）、`codebase-design`（spec.py 解析器接口设计——本讲重头）、`writing-for-agents`（若再动 CLAUDE.md）、验收前 `code-review`（双轴，发现按 test-first 修复并保留旧证据，参照 L02 证据账 §7 体例）
- 用户教学线活跃：`/mattpocock-skills:teach` 工作区在 `learning/`；证据链判定测验（lessons/0001）用户**尚未作答**——按间隔重测原则不催，等用户回来说结果

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 讲前读上游 L03 材料、判断口径 | —（直接读） | 模型自查 |
| 现查上游发现 pin 已动 | —（ADR-0003 检查点，停下报告） | 模型自查+用户裁决 |
| 采集工具参数化设计 | `codebase-design` | 模型自调 |
| 第 2 段合同 | `/mattpocock-skills:to-spec` | 用户显式；降级需本讲同意 |
| 执行期 | `/mattpocock-skills:implement` | 用户显式 |
| 红绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 具名验收前 | `code-review` | 模型自调 |
| 用户要深学某机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
- 措辞继承根目录 CONTEXT.md 词汇表（复刻/上游/候选分支/起始红/具名验收/证据/合同 fixture），避开各词条的 _Avoid_ 替代词
