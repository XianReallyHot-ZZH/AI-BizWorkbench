# Handoff：AI-BizWorkbench — L03 已收口，下一会话进入 L04（换挡点）

- 生成：2026-09-27，本会话完成检查点 0002 → L03 全流程（起草 → 执行 → 双轴复查修复轮 → 具名验收 → 合并）+ 教学 0002/0003 两课
- 本文件只做**接力**；事实以仓库 canonical 文件为准，不要轻信本文件的转述

## 仓库当前状态（均已提交，master 干净，`6d4e25a`）

- **L03 已具名验收合并**：`922f486`（merge --no-ff，验收人 XianReallyHot-ZZH，2026-09-27 接受）+ `135f72f`（roadmap 阶段 2 标记已完成、L03 讲义入导航）。master 全量 50/50 绿
- **本地领先 origin 34 提交，未 push**（用户未要求；新会话开局如用户未提 push，如实提示即可，不代推）
- **检查点 0002 已完成**：pin `a74445a → 7f67533`，记录在 `docs/research/codexfde-orientation.md` §10；roadmap 基线行已真指针化（`ade8fb8`，本次修正了上一会话遗留的假指针化）。**新会话仍须现查上游**，pin 若又动走 ADR-0003 检查点
- L03 交付物：`workbench/spec.py`（解析半边 + REGISTRY 缝适配器）、`workbench/cli.py` +4 行、`workbench/templates/SPEC_TEMPLATE.md`、`tests/test_l03_spec_parser.py`（16 用例）、`tools/capture_evidence.py` `--submission-root` 参数化（默认 `lesson-02-submission/` 不变）、`lesson-03-submission/`（FDE_SPEC 确认版 + v1 + decisions + 反例副本 + 12 条捕获，勿移动）、`docs/replication/evidence/L03.md`
- 任务账：`CASE-WB-L03-001` 12 条记录链完整（#19–#30，含 129 失败现场与被失权旧绿——**都保留是刻意的**），`acceptance: pending_human_review` 已被验收行覆盖

## L03 遗留欠账（L04 起草时显式清算）

- **D5**：vendor `spec.py` 的生成机器（`build_delivery_spec`/`normalize_requirement_id`/`normalize_business_refs` 等）本讲未复刻，L04 按合同需要再定——上游 `--requirement-spec` 消费面
- **D4**：仓库根 `FDE_SPEC.md` 本讲不建（上游根文件是 REQ-ECOM-001 交付主合同）；L04 换挡点若需根合同，按真实需求建立，不预造
- **绑定 eval `inventory_export_is_stable`**：L03 先例是绑定 eval 以同名合同测试承载 + 证据账登记 + 注明 harness 待建设（讲义 D1 裁决）；L04 是否沿用由用户裁决
- flowERP 事实（本会话只读调查，写入 `lesson-03-submission/decisions.md` SJ 表）：`flowerp/inventory_export.py:15,46` `export_inventory_file` **已存在**（临时文件+fsync+os.replace，finally 清理），`inventory.py:56-59` `available = on_hand - reserved` 同口径——L04 是"按 Spec 驱动交付并独立复验"，**不是从零实现**（上游口径：已有能力如实记录）

## 下一会话任务：起草 L04 讲义（用户审完才动手）

按 CLAUDE.md「每讲工作流」：

1. `git -C vendors/CodexFDE fetch && git rev-parse origin/main` 现查上游——若 ≠ `7f67533`，停下向用户报告，走 ADR-0003 检查点裁决（更新 §10），不要静默跟进
2. 读上游 L04 材料：`vendors/CodexFDE/docs/courses/L04/`（本会话**未读过** L04 材料，全部现场读；labs 模板 `docs/courses/labs/L04/`）
3. 起草 `docs/lessons/L04-*.md`（四段结构见 `docs/lessons/README.md`，参照 L03 讲义成稿）

### L04 已知合同事实（`workbench/course_contracts.py` `_lesson(4,...)`，逐字以它为准）

- **标题**"委托 Codex 执行一次最小变更"，stage=**build**，workbench 增量"受控执行、Diff 摘要与最小 Eval"，ERP 增量"交付库存导出"
- request：先直接监督 Codex 补齐并独立验收 Workbench V0，再让 V0 在限定写集内调用 Codex 实现 SKU 库存导出，并保存两张 Ticket 的 Diff、命令和正反路径证据
- scope=`("flowerp/", "workbench/", "tests/")`——**首次写 flowERP**（vendor 只读铁律的例外路径 = 候选分支内的 flowERP 交付，怎么与"vendors/ 只读"铁律共存是讲义必须显式裁决的决策点）
- acceptance 四条：①V0 的执行模式、写集检查、最小 Eval 和摘要**由非执行者独立验收**；②库存导出结果字段与排序**满足 L03 合同**（A1–A8 就是验收依据）；③导出值与 ERP 权威库存一致；④执行结果列出实际写集与复验命令
- 绑定 eval：`inventory_export_is_stable`（处置见上欠账）
- **换挡点**（CLAUDE.md 已固化）：L04 起由"人直接监督 AI 建工作台"改为"通过工作台组织协同"——执行器合同（写集、预算、JSON 事件流、沙箱、退出码）按 ADR-0002 映射到 Claude Code 权限机制；flowERP 首驱，客户真理 ADR-0005（其自身 eval 才是业务权威，`vendors/flowERP/eval/`）

### L04 起草已知设计决策点（现场读材料后补全，先列候选表交用户）

- 执行器合同 → Claude Code 权限机制的映射形态（ADR-0002；本会话经验：`claude -p` 无头会话 5 分钟+ 转后台任务等通知别轮询）
- "两张 Ticket"（先验收 V0，再 V0 驱动导出）在候选分支机制下的任务账/分支形状
- 写集检查器、Diff 摘要、最小 Eval 的模块落点（REGISTRY 缝扩容第二批；L03 结构约定第一次实战已过，L04 是规模化）
- 铁律 1 与 scope=`flowerp/` 的共存机制（flowERP submodule 在候选分支内的写路径与合并口径）

## 本会话新沉淀的经验教训（细节见证据账与课文，此处只点名字）

- **全局锚定实操**：复查修复轮重采时，修正 diff 落账晚于新绿会把绿失权（WB-09/F2 语义）——处置 = 同命令再采一条新绿，全部旧记录保留（L03 证据账 §5/台账 #16–#20）
- 采集工具已参数化：`--submission-root`，L04 用 `lesson-04-submission`；工具仍无 `--help`，探测即真实执行
- `git diff --cached <三点区间>` 是用法错误（rc 129）；提交后的全量分支 diff 用 `git diff master...lesson-NN`（证据账要 Diff 正文，不只 `--stat`）
- 任务账 status 输出结构：`projects[].tasks[]`，证据在 `task['evidence']`，`evidence_complete`/`acceptance` 在顶层——别按 `records` 找
- wrong-formula 放行是设计（解析器不冒充业务裁判）；`ValueError` 在 spec handler 层接住不走顶层边界（L03 验收追认两条，见证据账 §5）
- 请求原文逐字、候选表交用户逐条确认后原文入账——已连续两讲是硬要求（用户以"没问题，继续"批量确认，确认后逐字落账；见记忆 user-confirmation-style）
- code-review 双轴（`mattpocock-skills:code-review`，参数 `master...lesson-NN`）+ 发现按 test-first 修复 + 证据账 §N 补记，已成固定体例

## 配合点（L04 相关，届时停下提醒用户）

- `/mattpocock-skills:to-spec`（第 2 段合同；降级需用户**本讲**明确同意——每讲重新确认，L03 的同意不延续）
- `/mattpocock-skills:implement`（§3 执行时，用户显式；L03 确认过的入口行为：`claude -p` 双会话配置逐字一致、模型 ID 先问用户）
- 模型自调：`tdd`（红绿循环）、`codebase-design`（执行器/写集/Diff 摘要接口设计——L04 重头）、`code-review`（双轴）、`diagnosing-bugs`（遇障）
- `/mattpocock-skills:teach`（用户显式；工作区 `learning/`，**0002/0003 两课新发布用户未完成，0001 测验仍未作答——按间隔重测原则不催，等用户回来说结果**）
- `/mattpocock-skills:handoff`（换会话时，用户显式）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 现查上游 + 检查点裁决 | —（fetch 后报告，ADR-0003） | 模型自查+用户裁决 |
| 读上游 L04 材料、判断口径 | —（直接读） | 模型自查 |
| 执行器/写集/Diff 摘要接口设计 | `codebase-design` | 模型自调 |
| 第 2 段合同 | `/mattpocock-skills:to-spec` | 用户显式；降级需本讲同意 |
| 执行期 | `/mattpocock-skills:implement` | 用户显式 |
| 红绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 具名验收前 | `code-review`（`master...lesson-04`） | 模型自调 |
| 用户要深学某机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
- 措辞继承根目录 CONTEXT.md 词汇表（复刻/上游/候选分支/起始红/具名验收/证据/合同 fixture），避开各词条 _Avoid_ 替代词（近例：检查点用「显式采纳」不用「升级」）
- 用户确认风格见记忆 `user-confirmation-style`：候选表一次列全，等一句确认，原文逐字入账；未显式请求验收时不得把"继续"当签收
