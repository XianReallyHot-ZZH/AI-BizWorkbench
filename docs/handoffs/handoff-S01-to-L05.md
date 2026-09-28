# Handoff：AI-BizWorkbench — 支线 S01 已收口，下一会话审 L05 讲义并动工

- 生成：2026-09-28。本会话完成：支线全流程（方案起草 → 双轴复查 → 四链真实走通 → 具名验收合并收口）+ 教学 0005 课 + L05 讲前现查与讲义草案
- 本文件只做**接力**；事实以仓库 canonical 文件为准，不要轻信本文件的转述
- 存放说明：本仓库惯例是交接文档入库 `docs/handoffs/`（durability 优先，延续 L01–L04 先例）；同文副本已放 OS 临时目录

## 仓库当前状态（全部已提交，master 干净，`600b0c0`）

- **支线 S01 已具名验收合并**：`6499024`（merge --no-ff，验收人 XianReallyHot-ZZH，2026-09-28）+ `e55ee1d`（roadmap 支线行完成、讲义入导航）。master 全量 **82/82 绿**
- **本地领先 origin 10 提交，未 push**（用户未要求，不代推；单点风险同上次交接——L01–S01 全部工作只在这台机器上）
- **检查点状态**：pin `CodexFDE@7f67533`（检查点 0002）本会话两次现查未变。**新会话仍须 `git -C vendors/CodexFDE fetch` 现查**
- 任务账：主账 `.runtime/course/L01-workbench` 新增走通任务 CASE-WB-S01-WALK（accepted）+ 记忆资产 v1 `ASSET-becd3ae6`（superseded）/ v2 `ASSET-b8f18a6c`（active）；支线账 `.runtime/course/S01-memory` 链完整（红×2 → diff×4 → 绿×2，`evidence_complete: true`）
- 本地分支：`lesson-01..04`、`side-memory` 均已合并保留（未删）；工作树干净

## S01 交付摘要（细节一律指 canonical 文件）

- 交付物：`workbench/learning.py`（七命令记忆系统）+ `bootstrap.py` 五表 DDL + REGISTRY 第三批 + 15 条合同测试；主账经 `_ensure_v0_schema` 完成五表幂等迁移
- 事实源：`docs/replication/evidence/S01.md`（C1–C13、复查轮 ×3 缺陷、时间戳事故 §4-6、四链走通 §6、验收行 §7）、`docs/lessons/支线-记忆系统最小闭环.md`（定稿方案 D1–D9）
- 关键裁决（新会话引用时不要再翻案）：记忆链自有五相位 precheck/implement/eval/review/outcome（bootstrap.PHASES 不动）；失效只状态迁移不删除；memory 类失败只记 outcome 不自动撤回；govern 须 `--project-id` 且跨项目显式拒绝；记忆账链 = sha256 快照链（判内容不变），与任务账红绿链（判时序）分工
- 客户真理无涉（S01 纯工作台侧）；教学 0005 课（`4ea4f63`）已发布，复验块用主账真实资产

## 下一会话任务：审 L05 讲义候选表 D1–D5 → 动工 lesson-05（用户审完才动手）

讲义草案已入库：**`docs/lessons/L05-失败优先Eval.md`（`600b0c0`，状态：草案待审）**。第一件事 = 用户逐条裁决候选表，核心是 D1 的客户真理现查裁决：

- **现查结论（讲义 §1.3，勿再翻案除非用户裁决）**：flowERP `receive` 已幂等（`vendors/flowERP/flowerp/inventory.py:102`），客户 blocking eval 已有 `receiving_is_idempotent`（`vendors/flowERP/eval/harness.py:27`）→ 本讲增量重定位为"缺陷基线两连红证明检查有辨别力 + 同命令真实候选绿 + 冻结 eval 指纹"，**不重写客户已有的实现与 eval**
- 其余候选点：D2 缺陷基线 = 独立 clone + 构造坏 receive（`.runtime/course/L05-defect-baseline/`，永不合入）；D3 workbench 零新命令（eval.harness 是 L06 正题不抢跑）；D4 沿用客户用例身份（L08"同一 Eval 身份"起点）；D5 照旧 `lesson-05` 候选分支
- 合同：C1–C7（讲义 §2）；冻结合同 LESSONS[number=5]（`workbench/course_contracts.py`）逐字引用不修
- 审完 → 按裁决修订讲义 → `lesson-05` commit 1 = 缺陷基线 + 红证据（C3 两连红）

## 本会话新沉淀的经验教训（仓库文档未覆盖部分）

- S01 的纪律事故与修复已全部入 `S01.md` §4（时间戳手估、zsh 分词 ×2、_check_source 层级空转），新会话引用教训时**先读该节**，不在本文件重复
- code-review 技能双轴并行子代理（Standards + Spec 分开跑再汇总）效果好：Spec 轴抓到 2 个测试没覆盖的真缺陷（跨项目治理缺失、防漂移半做），复查轮按 test-first 两条新合同先红后绿——L05 收口复查轮沿用此法
- 具名动作批量执行的实操：6+1 个用户具名动作一次列全等一句确认（L04/S01 两次均如此），执行时失败（zsh/命令名写错）**不落账面**、修正后重跑——账本里只留成功的具名记录

## 教学线状态（learning/，用户自定节奏，不催）

- **0005 课已发布**（`4ea4f63`，记忆系统：两套链分工 + 四段真实缺陷故事 + 4 题判分 + 只读复验块）——用户尚未做复验与测验
- 0001/0002/0004 复验与测验仍未作答——按间隔重测原则不催
- 待办（NOTES.md 已记）：cheatsheet v2 补 L04 三命令 + 状态机（0004 复验后），0005 复验后可一并加记忆七命令
- 0006 选题候选：L05 的"缺陷基线/检查器辨别力"（S01 复查轮空转故事 + L05 两连红是现成教材）

## 配合点（下一会话相关，届时停下提醒用户）

- L05 讲义若要第 2 段合同形态：`/mattpocock-skills:to-spec`（用户显式；本讲由冻结合同 fixture + 讲义合同段承担，同 L04）
- L05 执行期：`/mattpocock-skills:implement`（用户显式，到点停下提醒）
- 模型自调：`tdd`（起始红→绿）、`code-review`（收口复查，双轴）、`diagnosing-bugs`（遇障）；`research`（若需补查 flowERP receive 细节）
- `/mattpocock-skills:teach`（用户显式；0006 选题见上）
- `/mattpocock-skills:handoff`（换会话时，用户显式）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局现查上游 + 检查点裁决 | —（fetch 后报告，ADR-0003） | 模型自查+用户裁决 |
| 审 L05 候选表 → 修订讲义 | —（直接改 docs/ 交审） | 模型自查 |
| L05 执行期 | `/mattpocock-skills:implement` | 用户显式 |
| 红绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
- 措辞继承根目录 CONTEXT.md 词汇表（「记忆系统/记忆条目/采用快照」三词条已立）
- 用户确认风格见记忆 `user-confirmation-style`：候选表一次列全，等一句确认，原文逐字入账；显式请求验收后的「没问题，继续」=签收（S01 方案与验收两门均如此），未显式请求时不得当签收
- L05 虽是编号讲次，纪律与 L04 同款：候选分支 ADR-0004、证据账 `evidence/L05.md`、失败不抹、 vendors 只读、客户真理 ADR-0005（flowERP 自身 eval 才是业务权威）
