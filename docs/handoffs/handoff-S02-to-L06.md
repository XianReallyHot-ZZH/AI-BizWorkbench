# Handoff：AI-BizWorkbench — S02 已验收合并并推送，下一会话进入主线 L06

- 生成：2026-09-28。本会话完成：S02 全程（`/mattpocock-skills:implement` → 起始红×3 → 实现 → 双轴复查 → 验收期发现修复 → 具名验收 → `merge --no-ff` → roadmap → **push**）+ 验收人亲验（起真服务看主账四屏）
- 本文件只做**接力**；事实以仓库 canonical 文件为准，不要轻信本文件的转述
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master 干净，`5693886`，**已与 origin/master 同步**）

- **push 已完成**：`bc2d624..5693886`，20 提交上 GitHub——L01–S02 全部工作（含 lesson-s02-submission/ 证据封存）不再单机。7 个已合并分支（lesson-01..05、side-memory、side-dashboard）仅本地保留，内容全部可达自 master 历史，不丢
- **S02 已具名验收合并**：`2c2171e`（merge --no-ff，验收人 XianReallyHot-ZZH，验收语「没问题，继续」逐字入合并信息）+ `5693886`（roadmap 支线二行标记完成）。master 全量 **94/94 绿**（合并后复跑）
- S02 交付：`workbench/dashboard.py`（四屏只读观察窗）+ `tests/test_side_dashboard.py`（C1–C6 十二用例）+ 证据账 `docs/replication/evidence/S02.md`（三轮红绿链 11 记录 + 失败现场 + 双轴裁决）
- 检查点状态：本会话开局 fetch 现查，pin `CodexFDE@7f67533` 未动。**新会话仍须 `git -C vendors/CodexFDE fetch` 现查，勿信本快照**
- vendors 两 submodule status 恒空（铁律 1 全程保持）
- 运行库（.runtime，gitignored）：新增 `.runtime/course/S02-dashboard/`（工作账 CASE-WB-S02-001，11 记录，evidence_complete: true）；主账 `.runtime/course/L01-workbench/` 全程 sha256 未动（`20b3ad61…447e`，看板只读三层实测）
- 后台看板服务已停；重启命令见 `docs/lessons/支线-工作台看板.md`

## S02 关键裁决（新会话引用时不要再翻案）

1. **观察窗定位**：零 POST、零验收按钮、`acceptance: pending_human_review` 恒待人签——看板是验收人的眼，不是操作台；验收动作永远只在 CLI 具名
2. **链语义三态**（验收期发现，test-first 修复，S02.md §4-6）：`chain.applicable` 字段——evidence 0 相位的任务（L04+ 执行/复核模型、纯载体）标「不走证据链」，**不报失锚、不拉低全账判定**；`evidence_complete` 只统计走链任务。教训已入骨：合同种子全是"有证据相位"形状，混合记账模型只有真账才暴露——**观察窗必须用真账验收**
3. 工作账 CASE-WB 任务不进账本状态机（S01 同款：0 执行 0 复核，state 停 no_execution）；验收效力在证据账具名行 + 合并提交，验收后无需补"accepted"
4. 误标红捕获目录 `lesson-s02-submission/03-failure/20260928T122423678793Z-`（相位 red 实为绿）**保留不删、未导入账本**；教训：采红前先 `git status` 核对实现不在工作树

## 下一会话任务：主线 L06（阶段 4 质量链第二讲）

开局顺序：

1. `git -C vendors/CodexFDE fetch` 现查（报告结果，有新上游材料则走检查点裁决 ADR-0003）
2. L06 合同现查：`workbench/course_contracts.py` 冻结 fixture 的 L06 条目 + 上游 L06 讲义（阶段 4 = 失败优先 Eval → **Harness 分级** → Hook → CI，L05 已完成；L06 具体合同以 fixture 与上游现查为准，勿凭本文件推测）
3. 起草 `docs/lessons/L06-*.md`（四段结构见 docs/lessons/README.md；客户真理现查 + 候选表一次列全等具名确认），**用户审完讲义才动手**
4. 动工后：`lesson-06` 候选分支（ADR-0004），commit 1 = 合同测试起始红 + 红证据（capture + vendor import_evidence 落账，workflow 同 S02：`.runtime/s02-manifest.txt` 先例）

## 本会话新沉淀（仓库文档未覆盖部分）

- **实现暂存重采真红**：最终版测试定稿后实现已落盘时，`git stash push -u -- <实现文件>` → 采红 → `stash pop`（S01 R2 先例的实操形）；误标捕获保留留痕即可，不必删
- **lsof 运行时绑定断言**（C6 加固形）：`lsof -nP -iTCP:<port> -sTCP:LISTEN` 断言 `127.0.0.1:<port>` 在场且 `*:<port>` 不在场——"绑回环"的源码串断言不可信（绑空串不含 0.0.0.0 字样）
- **status 巨 JSON 入账口径**（沿 L05 教训实操化）：capture 的 sh -c 管道内联 python 裁剪 `{ok, evidence_complete, acceptance, errors}` 四字段，整包勿入账
- 复查轮"绿到即达"的合同补全（覆盖缺口非行为缺口）**无红腿**是正当形态，如实记录即可；canary（测试自身失败路径自证）是让加固测试诚实的低成本手段

## 教学线状态（learning/，用户自定节奏，不催）

- 0006 课已发布未复验未测验；0001/0002/0004/0005 复验仍未作答——按间隔重测原则不催
- 待办（NOTES.md 已记）：cheatsheet v2 补 L04 三命令+状态机；0005 复验后加记忆七命令；0006 复验后加「辨别力三问」验收清单段
- 下一课候选：**0007「四屏背后的账本结构」**（素材已齐：三态链修复 + 证据链 11 记录 + 四屏数据流）——S02 讲义 §1.4 预告过；或用户点名

## 配合点（下一会话相关，届时停下提醒用户）

- L06 讲义候选表裁决：用户具名确认（一次列全等一句）
- L06 执行期：`/mattpocock-skills:implement`（用户显式，到点停下提醒）；若需合同形态第 2 段：`/mattpocock-skills:to-spec`（用户显式）
- 收口前：`code-review` 双轴（模型自调，L04/L05/S02 三度先例）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即本会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局现查上游 + 检查点裁决 | —（fetch 后报告，ADR-0003） | 模型自查+用户裁决 |
| L06 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| L06 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
- 措辞继承根目录 CONTEXT.md 词汇表（避免各词条 _Avoid_ 替代词）
- 用户确认风格见记忆 `user-confirmation-style`：候选表一次列全等一句确认；显式请求验收后的「没问题，继续」=签收（L04/L05/S02 讲义/S02 验收四度如此）
- L06 纪律与既往讲同款：候选分支 ADR-0004、证据账、失败不抹、vendors 只读；push 已解除单点风险，但新提交仍会再累积——收口时照例提请用户 push
