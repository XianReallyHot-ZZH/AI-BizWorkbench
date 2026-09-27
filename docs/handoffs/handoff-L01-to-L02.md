# Handoff：AI-BizWorkbench — L01 已收口，下一会话进入 L02

- 生成：2026-09-26，上一会话完成 L01 全流程（起草→执行→验收→合并）+ 教学区搭建 + CLAUDE.md/README.md
- 本文件只做**接力**；事实以仓库 canonical 文件为准，不要轻信本文件的转述

## 仓库当前状态（均已提交，master 干净）

- L00、L01 均已具名验收合并；最新 `f697a24`（roadmap 标记）。合并链见 `git log --oneline -8`
- `CLAUDE.md` + `README.md` 已建（6b38970）——新会话的 standing conventions，先读它
- 教学区 `learning/` 已入库；**第一课（lessons/0001 证据链判定测验）用户尚未作答**——按间隔重测原则，不催，等用户回来说结果；结果记入 learning-records（已有 0001 内核心智模型记录）
- 测试基线：全量 29/29 OK；`.venv` 就绪；活账本在 `.runtime/course/L01-workbench/`（gitignored）

## 下一会话任务：起草 L02 讲义（用户审完才动手）

按 CLAUDE.md「每讲工作流」执行：

1. `git -C vendors/CodexFDE fetch && git rev-parse origin/main` 现查上游——若 ≠ `58f4612`，停下向用户报告，走 ADR-0003 检查点裁决，不要静默跟进
2. 读上游 L02 材料：`vendors/CodexFDE/docs/courses/L02/`（README / 辅导资料 / 手册 / 行动卡 / prompts）
3. 起草 `docs/lessons/L02-*.md`（四段结构 + 配合点标注，参照 L01 讲义与 docs/lessons/README.md 结构定义）

### L02 已知裁决点（起草时显式交用户）

- **规则文件口径**：上游写 `AGENTS.md`（Codex 生态惯例）；本仓库执行器是 Claude Code，已有 `CLAUDE.md`。规则文件叫什么、ADR-0002 的同形映射怎么落——这是 L02 第一个显式决策
- 冻结合同：`write_scope=("AGENTS.md", "tests/")`；acceptance 两条（越界请求被规则明确判拒 / 规则同时给出正常路径和失败后不变状态）；本讲无 eval 名

## 配合点（L02 相关，届时停下提醒用户）

- `/mattpocock-skills:implement`（§3 执行时，用户显式；降级需同意）
- `/mattpocock-skills:to-spec`（第 2 段合同；可降级为讲义合同表即 Spec，需用户同意——L01 已有过一次降级同意，L02 需重新确认）
- 模型自调：`writing-for-agents`（L02 核心——给工作台写规则文件）、`tdd`、`diagnosing-bugs`、验收前 `code-review`
- 用户教学线活跃：`/mattpocock-skills:teach` 工作区在 `learning/`

## 上一会话的经验教训（细节见证据账，此处只点名字）

- zsh 多词变量不拆分：命令一律写全字面量（L01 证据账台账 #23 的 rc=127 现场就是这么来的）
- 链判定语义以 vendors `bootstrap.py` 的 `_chain` 为准（存在性红 + 全局失权）；F8/F10/F15 三项裁决已接受，详见 `docs/replication/evidence/L01.md` 复查修复轮
- `lesson-01-submission/` 捕获目录勿移动（meta.json 绝对路径封条）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 起草讲义前读上游材料、判断口径 | —（直接读） | 模型自查 |
| 规则文件成文 | `mattpocock-skills:writing-for-agents` | 模型自调 |
| 执行期 | `/mattpocock-skills:implement` | 用户显式 |
| 红绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 具名验收前 | `code-review` | 模型自调 |
| 用户要深学某机制 | `/mattpocock-skills:teach` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
