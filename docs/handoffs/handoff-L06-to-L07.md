# Handoff：AI-BizWorkbench — L06 已验收合并，下一会话进入主线 L07

- 生成：2026-09-29。本会话完成：L06 全程（开局现查 → 讲义定稿 D1–D6 → `lesson-06` 三 commit → 双轴复查 → 修复轮 commit 3 → 具名验收 → `merge --no-ff` → roadmap → 0007 课发布）
- 本文件只做**接力**；事实以仓库 canonical 文件为准，不要轻信本文件的转述
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master 干净，`63e9622`，**未 push——领先 origin 10 提交**）

- **push 未做**（会话末用户未及答复）：L06 讲义定稿 2 + lesson-06 四 commit + 验收合并 `b329ac4` + roadmap `1af7b19` + 0007 课 `63e9622`。新会话开局先提醒 push
- **L06 已具名验收合并**：`b329ac4`（merge --no-ff，验收人 XianReallyHot-ZZH，验收语「没问题，继续」逐字入 evidence/L06.md §5；首版合并信息 S06 笔误已 amend 勘误留痕）。master 全量 **103/103 绿**（合并后复跑）
- L06 交付：`evals/harness.py`（统一运行器）+ `evals/report_contract.py`（报告一致性独立复核）+ `evals/l06/`（checks 五登记项收口 / stock_consistency_check 口径驱动 / build_defect_baseline 缺陷构造）+ `tests/test_l06_eval_harness.py`（九项合同）+ 证据账 `docs/replication/evidence/L06.md`（双链红绿 22 记录 + 复查轮）
- 检查点状态：本会话开局 fetch 现查，pin `CodexFDE@7f67533` 未动（新 tag `course-package-20260928` 即指向本 pin）。**新会话仍须 `git -C vendors/CodexFDE fetch` 现查，勿信本快照**
- vendors 双 submodule status 恒空（铁律 1 全程保持）
- 主账 `.runtime/course/L01-workbench/`：任务 CASE-WB-L06-001 **accepted**（review_id 5 approve，execution 6 verify_completed），记录 57–78
- 运行库（gitignored）：`.runtime/course/L06-defect-baseline/`（CSV 缺陷 clone，仍可用）、`L06-candidate/`（干净 clone）、`L06-probe/false-green/`（假绿变体）；无后台服务
- 教学线：`learning/lessons/0007-unified-harness-verdict.html` 已发布（L06 复盘：传导链可信度），未复验未测验

## L06 关键裁决（新会话引用时不要再翻案）

1. **增量重定位**（讲义 §1.3 客户真理现查）：客户 flowERP **自带统一 harness**（`eval/harness.py` `run_suite`，blocking 分级、schema 1.0、退出码一致）——本讲不重写客户检查，增量 = 本仓库 `evals/` 侧统一裁判；客户 eval 经子进程**收口**（登记项内照跑），不改写不删弱（ADR-0005）
2. **传导链设计**：harness 的 summary/decision/退出码全部从 results 单点推导（那行 `sum(...)` 是唯一真相源）；report_contract 把 summary 当被告不当证人、自己重推导对质——harness 与复核器的计数是**两份独立实现**（故意的，复查轮 S1 不采纳合并即是此理）；等级写在登记处 = 事先确认的规则，不得运行时调级
3. **R1（执行期翻案）**：口径驱动首版把 AC-UNCHANGED 快照拍在 `create_order` 之前——建草稿单是合法写入，被拒的是预占；干净候选绿腿首采当场翻红（`93b27034` 封存未导入）。教训：检查语义锚点对准**被拒的那个动作**；链 B 红绿用修后驱动按序重采（66→65→64），v1 历史 62/63 追加不删
4. **客户 eval 盲区实证**（C7 留痕）：客户 `inventory_export_is_stable` 数据 reserved=0，对「CSV available 误写 on_hand 且 reserved>0」**绿×2**（record 72/73，同树本仓库口径检查红）。是否补 reserved>0 客户用例留检查点显式采纳，本讲不改客户文件
5. **L07 接口已就位**：`evals/harness` 是 L07 Hook 复用的同一入口（「统一入口解决怎样检查，L07 解决什么时候实际检查」）；报告 schema 1.0 与客户对齐 = L08「本地/远端同一 Eval 身份」铺垫（record 69 实证客户报告过本仓库复核器）
6. 复查轮双轴：无硬违规；采纳 S3（expected/wanted 改名，JSON 键不动）+ S4（entries 必传参）+ §2 勘误；S1/S2/S5/S6 不采纳（理由在 L06.md §4——S2 是 l05 冻结产物不动）

## 下一会话任务：主线 L07（阶段 4 质量链第三→四讲：Hook 本地护栏）

开局顺序：

1. **提醒 push**（10 提交待推）→ `git -C vendors/CodexFDE fetch` 现查（报告结果，有新上游走检查点裁决 ADR-0003）
2. L07 合同现查：fixture `workbench/course_contracts.py` LESSONS[7]「用 Codex Hooks 建立本地护栏」（本地护栏 + 销售订单创建 + `hook_staging/` 待审查配置 + 人工审查后安装 + 仅业务 Eval 通过≠Hook 验收完成）+ 上游 `vendors/CodexFDE/docs/courses/L07/`（含 `L06检查接入手册.md`——L06 检查接入 L07 候选的七项阻断检查注册）——**勿凭本文件推测，以现查为准**
3. 起草 `docs/lessons/L07-*.md`（四段结构 + §5 候选表一次列全等具名确认），**用户审完讲义才动手**；客户真理现查必做（flowERP 销售订单面 + 客户 `order_total_matches_lines` 用例已在 EVALS）
4. 动工后：`lesson-07` 候选分支（ADR-0004），commit 1 = 合同测试起始红 + 红证据（capture + vendor import_evidence 落账，workflow 同 L06）

## 本会话新沉淀（仓库文档未覆盖部分）

- **`exec` 吞命令**：`sh -c` 内 `exec` 会替换 shell，同一命令串后续比对/回显永不执行（`30aeee3b` 残缺输出）——多步采证逻辑走临时脚本文件，不走内联嵌套引号
- **时变字段归一**：比对两次 harness 输出结构前先归一 `generated_at` 与 `duration_ms`（否则误判不一致，`7624b8a8` rc 1 先例）
- **capture 相对路径陷阱**：`sh -c 'cd <clone> && …'` 后 `.venv/bin/python` 相对路径失效（rc 126）——clone 内采证用绝对 venv 路径
- **intent-to-add 进 diff**：新文件未跟踪时 `git add -N <files>` 让 `git diff` 能捕获全文进 diff 相位记录
- 误标/残缺捕获处置惯例：封存留痕不删、不导入账本、证据账 §失败现场记一句（L06.md §3.2 现有四例）

## 教学线状态（learning/，用户自定节奏，不催）

- 0007 课已发布（L06 复盘：传导链可信度；四题测验未作答）；0001/0002 测验仍未作答；0006 未复验——按间隔重测原则不催
- 待办（NOTES.md 已记）：cheatsheet v2 补 L04 三命令+状态机+记忆七命令；0006 复验后加「辨别力三问」清单段；0007 复验后加「传导链四环」清单段
- 下一课候选：L07 验收后用户点名，或 0008「Hook 与生命周期」；handoff 文档本身不预设

## 配合点（下一会话相关，届时停下提醒用户）

- push 提醒（开局）+ L07 讲义候选表裁决：用户具名确认（一次列全等一句）
- L07 执行期：`/mattpocock-skills:implement`（用户显式，到点停下提醒）；若需合同形态第 2 段：`/mattpocock-skills:to-spec`（用户显式；L04–L06 三度未用先例）
- 收口前：`code-review` 双轴（模型自调，L04/L05/S02/L06 四度先例）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即本会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 push 提醒 + 现查上游 + 检查点裁决 | —（fetch 后报告，ADR-0003） | 模型自查+用户裁决 |
| L07 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| L07 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
- 措辞继承根目录 CONTEXT.md 词汇表（避免各词条 _Avoid_ 替代词）
- 用户确认风格见记忆 `user-confirmation-style`：候选表一次列全等一句确认；显式请求验收后的「没问题，继续」=签收（L04/L05/S02 讲义/L06 验收/0007 课五度如此）
- L07 纪律与既往讲同款：候选分支 ADR-0004、证据账、失败不抹、vendors 只读；收口时照例提请用户 push
