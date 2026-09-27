# Handoff：AI-BizWorkbench — L04 已收口，下一会话进入支线（记忆系统六步最小闭环）

- 生成：2026-09-27，本会话完成 L04 全流程（起草 → 双票执行 → 复查修复轮 → A/B 双具名验收 → 合并收口）+ 教学 0004 课 + 一次代码走读
- 本文件只做**接力**；事实以仓库 canonical 文件为准，不要轻信本文件的转述

## 仓库当前状态（全部已提交，master 干净，`e0a9647`）

- **L04 已双票具名验收合并**：`826af87`（merge --no-ff，验收人 XianReallyHot-ZZH，2026-09-27）+ `b80b083`（roadmap 阶段 3 完成、讲义入导航）。master 全量 **67/67 绿**
- **本地领先 origin 48 提交，未 push**（用户未要求，不代推；新会话开局如实提示即可——这是当前最大的单点风险：L01–L04 全部工作只在这台机器上）
- **检查点状态**：pin `CodexFDE@7f67533`（检查点 0002）本会话开局现查未变。**新会话仍须 `git -C vendors/CodexFDE fetch` 现查**，若移动走 ADR-0003
- 任务账：CASE-WB-L04-001 / -002 均 `accepted`；A 票链含四轮 diff/green（全局锚定，最新绿 `142606` 后又第三轮 `135915`——以 workbench-status 实查为准）；执行记录 2 条（execution 2 eval_failed + 3 verify_completed）全保留
- 工作树干净；`.runtime/` 下有支线相关实物：`course/L04-delivery/candidate`（flowERP clone）与 `candidate-lost-run1`（首跑失败归档，不删）

## L04 交付摘要（细节一律指到 canonical 文件，此处不复述）

- 交付物：工作台 V0 受控执行（`workbench/execution.py` 三命令 + `bootstrap.py` 账本扩容）+ flowERP 首驱交付（候选内 `delivery/inventory.csv`，probe 六场景 rc 0）
- 事实源：`docs/replication/evidence/L04.md`（C1–C15 逐项、命令台账、三次真实缺陷 §3-15/16/17、失败现场 §5、A/B 双验收行 §7/§9）、`lesson-04-submission/`（A/B-evidence、handoff 五问、probe 报告 ×3、eval 脚本）
- 关键裁决（新会话引用时不要再翻案）：lot_id 列=ERP 内部批次 ID（LOT01/LOT02 是 lot_number 不导出）；CSV BOM/CRLF=客户既有行为（ADR-0005），probe 兼容；eval 期望值必须取自权威源（ERP 账），不得硬编码执行器输出的字面量

## 下一会话任务：起草支线方案（用户审完才动手）

roadmap 定位：**支线 = 记忆系统六步最小闭环，L04 后、L05 前串行插入**（`docs/replication/README.md` 阶段表）。完成标志：召回→采用→复验→失效证据链走通一次。

按 CLAUDE.md 讲前工作流同款纪律：

1. `git -C vendors/CodexFDE fetch && git rev-parse origin/main` 现查上游（≠`7f67533` 则停下报告走 ADR-0003）
2. 现查上游记忆系统相关材料（勿信快照；上游 workbench/ 下 `feedback.py`、`session_context.py`、`session_persist.py`、`memory` 相关模块与 docs 需要现场定位阅读）
3. 起草**支线方案**（形态介于讲义与 ADR 之间：使命定位、六步各自的证据形态、走不走候选分支机制、验收门形状），列候选表交用户逐条确认

### 已知设计决策点（起草时列候选表，先场内调查再推荐）

- **存储落点**：复用 workbench 账本（executions/reviews 同款表）vs 独立 `memory/` 目录 + 独立账本 vs 其他——CONTEXT.md「记忆系统」尚无词条，措辞要先立
- 六步（召回→采用→复验→失效）各自的**记录相位与链判定**是否沿用 red/diff/green/observation 四相位，还是需要新相位（新相位 = 动 bootstrap 冻结常量 PHASES，须慎重）
- 记忆条目的**失效证据**怎么留（失效不可抹与铁律 3 同构）
- 与 `learning/` 教学工作区的边界（MISSION 更新算不算"采用记忆"？）

## 本会话新沉淀的经验教训（仓库文档未覆盖部分）

- **zsh 不做变量分词**：`I="cmd --args"; $I ...` 会把整串当一个词——多命令复用写显式 `for M in ...; do .venv/bin/python ... "$M/meta.json"; done`
- **unittest 单用例**只能 `discover -s tests -k <name>`，不能 `unittest test_l04_...`（tests 经 -s 发现，直接模块名找不到）
- **claude -p 无头会话三条硬教训**（都已修进代码/文档）：①argparse `nargs='+'` 收 argv 会被 `-X`/`-p` 选项形 token 终止——命令旗标一律单字符串 + shlex；②跨 cwd 的 eval/执行器命令一律**绝对路径**（相对解释器路径在候选 cwd 炸，曾致执行记录尽失）；③spawn 的 `OSError` 必须捕获落账——裸崩=失败被抹，铁律 3 红线
- **Edit 工具大段替换会吞章节标题**（本会话两次：§6/§7 标题被吞）——多章节重排用 python 脚本按标题正则切分重组，不用 Edit 硬碰
- 章节顺序错乱时：`re.split(r"(?=^## \d+\. )", text, flags=re.M)` 按编号 sort 重写，可靠
- 模型选择流程：真实执行器启动前用 AskUserQuestion 问用户（L04 答复"账号默认"=不传 `--model`）；子进程会带出会话环境模型名警告 `[claude-code:unrecognized_model]`，非致命，如实记证据即可
- 全局锚定的操作化（L03 先例，本讲四轮实践）：任何修复落账晚于上轮绿 → 同命令重采 diff+green，旧记录全保留，"全局最新须为成功绿"自动恢复

## 教学线状态（learning/，用户自定节奏，不催）

- **0004 课已发布**（`e0a9647`，受控执行：笼子/状态机/三缺陷 + 一败一成复验 + 4 题判分）——用户尚未做复验与测验
- 0001（链判定）/0002（六段闸门）测验仍未作答——按间隔重测原则不催
- 待办（NOTES.md 已记）：`reference/0001-workbench-cheatsheet.html` 补 v2（L04 三命令 + 状态机）——**等用户做完 0004 复验再补**
- 本会话已做一次 executor.py 代码走读（按执行生命线 + 行号锚点），用户在逐步建立"门/防不住/账"三连问的判断力

## 配合点（下一会话相关，届时停下提醒用户）

- 支线方案若涉及第 2 段合同形态：`/mattpocock-skills:to-spec`（用户显式；降级需本支线明确同意——新工作流，无既定降级可沿用）
- 支线执行期：`/mattpocock-skills:implement`（用户显式）
- 模型自调：`tdd`、`codebase-design`（记忆条目 schema/召回接口设计——支线重头）、`code-review`、`diagnosing-bugs`、`research`（上游记忆系统材料调查）
- `/mattpocock-skills:teach`（用户显式；0005 选题候选：支线机制或 L05 失败优先 Eval）
- `/mattpocock-skills:handoff`（换会话时，用户显式）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局现查上游 + 检查点裁决 | —（fetch 后报告，ADR-0003） | 模型自查+用户裁决 |
| 支线：上游记忆系统材料调查 | `research`（可后台） | 模型自调 |
| 支线方案起草 + 候选表 | —（直接写 docs/ 交审） | 模型自查 |
| 记忆条目 schema / 召回接口设计 | `codebase-design` | 模型自调 |
| 支线执行期 | `/mattpocock-skills:implement` | 用户显式 |
| 红绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 支线收口前 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
- 措辞继承根目录 CONTEXT.md 词汇表；「记忆系统」词条尚缺，支线起草时先立措辞（经用户确认）
- 用户确认风格见记忆 `user-confirmation-style`：候选表一次列全，等一句确认，原文逐字入账；显式请求验收后的「没问题，继续」=签收（L04 双门均如此），未显式请求时不得当签收
- 支线虽非编号讲次，铁律与证据纪律**全部适用**（vendors 只读、失败不可抹、证据=命令+退出码+失败后权威状态）
