# Handoff：AI-BizWorkbench — L05 已收口，下一会话执行支线 S02（工作台看板）

- 生成：2026-09-28。本会话完成：L05 全程（动工→执行期翻案→采证→复查轮→具名验收→合并→roadmap）+ 教学 0006 课发布 + 支线 S02 讲义起草与定稿
- 本文件只做**接力**；事实以仓库 canonical 文件为准，不要轻信本文件的转述
- 存放说明：入库 `docs/handoffs/`（durability 优先，先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（全部已提交，master 干净，`9fb0e01`）

- **L05 已具名验收合并**：`6ff6202`（merge --no-ff，验收人 XianReallyHot-ZZH）+ `0589172`（roadmap 阶段 4 标记 L05 完成）+ `555bacc`（验收行入证据账）。master 全量 **82/82 绿**（合并后复跑）。`lesson-05` 分支照惯例保留
- **任务账**：CASE-WB-L05-001 `accepted`（13+2 行 evidence，`evidence_complete: true`）；approve 记录在场（reviewer XianReallyHot-ZZH）
- **运行库**（.runtime，gitignored）：`.runtime/course/L05-defect-baseline/`（缺陷基线，永不合入）、`L05-candidate/`（干净候选）、`L05-probe/`（四探针含无效 narrow，保留不删）
- **本地领先 origin 约 20 提交，未 push**（用户未要求，不代推；**单点风险持续累积**——L01–S02 草案全部工作只在这台机器，交接时向用户提请注意）
- **检查点状态**：pin `CodexFDE@7f67533`（检查点 0002）本会话三次 fetch 未动。**新会话仍须 `git -C vendors/CodexFDE fetch` 现查**
- 工作树干净；本地分支：`lesson-01..05`、`side-memory` 均合并保留

## L05 交付摘要（细节一律指 canonical 文件）

- 交付物：`evals/l05/build_defect_baseline.py`（锚点守卫+缺陷自检）+ `evals/l05/receiving_scenario_check.py`（独立复算预期，读库存与流水）+ `lesson-05-submission/`（11 份封存）
- 事实源：`docs/replication/evidence/L05.md`（C1–C7、复查轮双轴、盲区留痕）、讲义定稿 v2 `docs/lessons/L05-失败优先Eval.md`（§5 两级裁决记录）
- **关键裁决（新会话引用时不要再翻案）**：①客户 eval 实际被测路径是门面 `service.py:150`（不是 `inventory.py:102`——事实真但层错曾触发执行期翻案）；②缺陷基线形状 = 门面重放分支「重试再次入账」（窄缺陷因 `inventory_events.event_key` 主键不可构造）；③账面重写类盲区（客户 eval 不读流水）留检查点显式采纳，本讲只留痕；④同命令异树口径（`$L05_EVAL_TARGET` 切换 + pwd 行三重披露）；⑤裸 assert 定位 = 用例名 + BLOCK 行 + 驱动数值级补充（harness 任何输出不含断言表达式）

## 下一会话任务：执行支线 S02（讲义已定稿，候选表已裁决——**不用再审**）

讲义定稿：**`docs/lessons/支线-工作台看板.md`（`9fb0e01`，D1–D6 全部接受，裁决记录 §5）**。开局顺序：

1. `git -C vendors/CodexFDE fetch` 现查（报告结果，无新检查点动作即继续）
2. **提醒用户显式调用 `/mattpocock-skills:implement`**（铁律 5；降级路径仅用户明确同意后走）
3. `side-dashboard` commit 1 = 合同测试 C1–C6（起步红：`workbench-dashboard` 不存在 → `invalid choice` 形状，同 L04）+ 红证据（capture + vendor import_evidence 落 CASE-WB-S02-001）
4. 实现 `workbench/dashboard.py`（D1–D6 裁决：REGISTRY 注册缝、sqlite mode=ro + 仅 GET + 账本 sha 自检三层只读、直读内部表不建 API、四屏缺表降级、服务端渲染单页 HTML 零依赖、默认 127.0.0.1）
5. 绿 + 全量（82+N）→ `code-review` 双轴复查 → 修复 → 证据账 `docs/replication/evidence/S02.md` → 具名验收 → `git merge --no-ff side-dashboard` → roadmap 支线行追加

## 本会话新沉淀（仓库文档未覆盖部分）

- **跨目录命令的采证模式**：capture 工具以调用 cwd 运行命令且 meta 记 argv 列表——跨目录命令用 `sh -c 'cd "$VAR" && pwd && exec …'` + 环境变量切目标 + 输出 pwd 行自证（L05 红绿链靠这个做到 command_text 逐字节同串）。复用场景：任何「同命令异目标」采证
- **capture→import 用 manifest**：每采一条就把最新 meta.json 路径追加 `.runtime/<讲>-manifest.txt`，事后按序导入（observed_at 天然递增）；observation 相位在绿后追加安全（链锚定只看 red/diff/green）
- **workbench-status 全量 JSON 巨大**（含 spec 快照全文）：终端查看用管道裁剪字段，勿整包 capture 入账

## 教学线状态（learning/，用户自定节奏，不催）

- **0006 课已发布**（`bef4064`，缺陷基线/辨别力三问：无效探针+两连红+盲区实验+裸断言口径，素材全取自 L05 当天真实产物）——用户尚未做复验与测验
- 0001/0002/0004/0005 复验与测验仍未作答——按间隔重测原则不催
- 待办（NOTES.md 已记）：cheatsheet v2 补 L04 三命令+状态机；0005 复验后加记忆七命令；0006 复验后可加「辨别力三问」验收清单段
- 下一课候选：S02 看板落地后可讲「四屏背后的账本结构」（或用户点名）

## 配合点（下一会话相关，届时停下提醒用户）

- S02 执行期：`/mattpocock-skills:implement`（用户显式，到点停下提醒）
- S02 收口前：`code-review` 双轴（模型自调，L04/L05 先例）
- S02 若需合同形态第 2 段：`to-spec`（用户显式；本支线由自拟合同段承担，同 L04/L05）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即本会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局现查上游 + 检查点裁决 | —（fetch 后报告，ADR-0003） | 模型自查+用户裁决 |
| S02 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
- 措辞继承根目录 CONTEXT.md 词汇表（避免各词条 _Avoid_ 替代词）
- 用户确认风格见记忆 `user-confirmation-style`：候选表一次列全，等一句确认，原文逐字入账；显式请求验收后的「没问题，继续」=签收（L04/L05/S02 讲义三度如此），未显式请求时不得当签收
- S02 纪律与 L05 同款：候选分支 ADR-0004、证据账、失败不抹、vendors 只读；看板**没有验收按钮**——签收永远在 CLI 具名
