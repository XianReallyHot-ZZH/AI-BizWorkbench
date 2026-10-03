# Handoff：AI-BizWorkbench — L15 完成收口（operate 第三讲 + S01 记忆系统 Java 对应物兑现），下一会话 L16 开局

- 生成：2026-10-03（同会话完成：开局 fetch 现查无新上游 → 沿先例自建讲义（第三例）→ D1–D10 签收 → to-spec（用户显式；接缝两既有缝零新缝 + D7 具名选定 R1）→ implement（用户显式）→ 起始红 18 三面 → 实现（2a/2b 两段）→ 五拍实验 + R1 前后原件 → 链证据 + verify + 密封 → code-review 双轴（修六/入账五）→ 具名验收 → merge --no-ff → 收口 → push + CI 绿 → L15 教训入记忆 → 本 handoff）。上一会话起点 = [handoff-L14-to-L15.md](handoff-L14-to-L15.md)
- 本文件**接替**上一份 handoff；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `61db446`，已 push，领先 0）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：收口前实跑 **Tests run: 267, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（= 249 既有零回归 + L15 新 18；复查轮修复后复跑复核）
- vendors 双 submodule status 恒空（铁律 1——L15Checks 已含双树恒空断言）；pin `CodexFDE@406f7aa`（检查点 0003；本会话开局 fetch 现查 pin 后零新提交、课程讲义目录止于 L12）。新会话**仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代只读封存）；`L01-workbench-java/`（Java 线账本——L15 链 128–131 + execution 16 终态）；`L07…L14-panel/`（勿动）；**`L15-evolution/`（L15 五拍实验库，数据在，可读勿改写）**；`.runtime/hooks/` 持续增长；无后台服务与未收任务
- CI：收口 push Run `37091845743` **success**（50s，checkout `61db446`）；无新 push 则无新 Run
- **大件警示（持续）**：`lesson-15-submission/06-observations/…/output.txt` = **275MB status 密封件，超 GitHub 100MB 硬限**——meta.json + SHA-256 已入库、output 本地封条完整（`.git/info/exclude` 排除，磁盘原件未动未删）。根因仍是账本历史单调增长（db 218MB），L16 密封件必更大，瘦身面留检查点裁量。教训已在记忆

## 新会话必读：两道活护栏（都在生效）

1. **Stop hook**（L07）：每次回合结束过闸（净树 15–25s）；被 block = gate 如实报告，修真实原因勿绕闸；详见 [hook_staging/README.md](../../hook_staging/README.md)
2. **CI 工作流**（L08）：`.github/workflows/l08-eval.yml`——L09–L15 七讲均裁定不动（**改工作流前先读 CI_GATE_SPEC**；远程接入新检查面留检查点统一裁量）

## L15 终态与遗留（详见 [docs/replication/evidence/L15.md](../replication/evidence/L15.md)）

- 任务 `CASE-WB-L15-001`：verify `verify_completed`（execution 16，actor Claude），task_state review；链闭合 red(128) < diff(129) < green(130) + 密封(131，`evidence_complete:true`）；具名验收行 §7（验收人 XianReallyHot-ZZH，接受 2026-10-03）
- 交付件：`workbench/evolution/EvolutionStore` + REGISTRY `evolution`（六状态机 + verify 重门含 governed 分支前置化）+ `workbench/learning/` 两件（LearningStore+LearningCli——**S01 冻结面 770 行 Java 兑现**，七命令五表词面直承 + evolution 来源级联）+ `workbench/delivery/FeedbackCli`（spec 偏差 +1，上游 main 同形）+ DeliveryView evolution 块与第九计数（L14 D4 预埋兑现）+ DynamicSpecFreeze 纯核（**dynamic_eval_required 首见承载**——起始红形式化）+ `workbench/evals/l15/L15Checks` 十件默认门（指纹九件含 S01 tag git show 现算）；REGISTRY 合计 +9
- 五拍实验真链 + R1 拷贝树受控改进（前红 `invariant_ok:false` → 锚点单事务化 → 后绿 + 客户 eval 双照跑双绿 + 双 submodule 恒空）原件在 `lesson-15-submission/15-evolution/`（永不移动）；**09a = AI 名义 bind 被 human() 门拒现场**（S01 claude 增补真实链路生效实证——本讲最有价值的失败）
- 复查轮双轴 13 项处置表 §3.6（修六：S-硬1 govern 静默兜底 test-first 修 / T-1 指纹九件 / T-6 双树恒空 / S-5 注释词面 / S-4 choices / spec 勘误三；不修入账五各带理由）；红基线修正三处披露 §3.5
- **无未解决悬案**；候选 `lesson-15` 六 commit + 验收行 `e557e6c` + merge --no-ff + 收口 `61db446`
- 自建讲义先例已三例（L13/L14/L15）——D1 范围口径（绑定 eval 断言面为界 + 不采纳清单落账）+ spec 阶段补充裁决（接缝确认 + D 表增量具名）+ 起始红三面 + 复查轮处置表，第四例（L16）可直接承

## L15 执行教训（已入记忆 feedback-l15-execution-discipline，此处仅指针）

SQLite **同进程双连接互锁**（事务内勿构造会建表的 Store——只读检查移 BEGIN 前 + 同连接直查）；Checks 夹具 ID 主体恰 10 位过 `TASK-[A-Z0-9]{10}` 校验；**真链实验是最后一道门**（AI 名义被拒比机检更有说服力——失败件保留为现场）；指纹冻结按**文件序**采集（数组序错位实测抓获）；capture 子命令无 --help（用法从 meta.json 反推，误触发垃圾删除+披露）。

## 下一会话任务：主线 L16（transfer 终讲——课程收官）

**开局第一件事 = fetch 现查**（pin 后有无新提交、L16 讲义是否上架）；有新上游走检查点裁决（ADR-0003），无则沿 L13–L15 先例走**检查点自建讲义（第四例）**。

L16 合同现查口径（`CourseContracts.java` LESSONS[16]，以新会话现查为准）：主题「在新环境接手，并完成现场新需求」；阶段 **transfer**；workbench_increment「冷启动、发布证据索引与迁移答辩」/ erp_increment「现场交付此前未实现的小需求」；描述「现场抽取一个此前未实现的 FlowERP 小需求，使用工作台完成 Spec、受控执行、Eval、人审和发布证据」；demo `REQUIREMENT:LIVE-DRAW`（**现场抽取**）；写集 `flowerp/ workbench/ eval/ web/ tests/`；验收三条「需求在答辩现场抽取且仓库基线中尚未实现。」「正常、失败和失败后不变状态均有新证据。」「发布索引能追溯需求、Diff、Eval、人审与剩余风险。」；绑定 eval 一件 `delivery_evidence_and_review_controls`（L13 已建复验面）；lesson(16, …, **true**, true)——首参数 true 疑为 live 抽取标志（现查 lesson() 签名确认）；story 16。

**L15 → L16 因果交接**：L16 消费本讲**记忆链冷启动**（现场新需求用 learn-recall 带引用上下文——课程蓝图 L13-L16 段「反馈与经验复用」收口）+ **动态 Eval 机制**（L16 现场抽取的新需求同样要求新增用例先真实失败，DynamicSpecFreeze 已建）；上游 `workbench/release_index.py`（发布证据索引，102 行）与 `course_release.py`（CourseBaselinePublisher/CourseCandidateArtifacts——audit/publish/export/promote/manifest）是 **L16 面对照候选**（L15 D1 不采纳清单明示预留）；「冷启动」面（干净环境启动系统）上游有 environment_check/bootstrap_handoff 等件——讲前 fetch 后再裁。

## 配合点（届时停下提醒用户）

- 开局 fetch 现查 → 有新上游走检查点裁决（ADR-0003），用户裁决
- L16 讲义候选表：用户具名确认（一次列全等一句）
- `/mattpocock-skills:to-spec`（用户显式；L08–L15 先例已立：接缝确认 → spec 落 `docs/specs/`）
- `/mattpocock-skills:implement`（用户显式，动笔前）
- 收口前：`code-review` 双轴（模型自调，并行 sub-agent + S/T 编号处置表——L15 先例：修六入账五）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L16 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| spec 合成 | `/mattpocock-skills:to-spec` | 用户显式 |
| L16 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制（冷启动/发布索引大概率） | `/mattpocock-skills:teach` | 用户显式 |
| 机制图（按需） | `archify` | 模型自调 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求后的「没问题，继续」= 签收/授权（L15 验收与收口 push 授权均此口径）
- 收口时照例提请用户 push（L15 push 一次成功；密封件超限新常态与 HTTP 400 分段预案记忆均在案：核真实领先数用 `git rev-list --count origin/master..master`）
- 执行纪律见记忆 feedback-l07–l15-execution-discipline（L15 新增五条：同进程互锁 / 夹具 ID 十位 / 真链最后一道门 / 指纹文件序 / capture 无 --help）

## 教学线（learning/，用户自定节奏，不催）

- L07–L11 复盘课未做、L12/L13/L14/L15 复盘课新候选、0001/0002 测验仍未作答、cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-10-03）

- `mvn test`（复查轮修复后复跑，后台 600s 超时自动续跑经通知确认）→ **Tests run: 267, Failures: 0, Errors: 0, rc 0 + BUILD SUCCESS**
- push 一次成功：`94bf457..61db446`（11 commits，无大件 warning）
- CI Run `37091845743` **completed success**（checkout `61db446`，50s）
- master 头 = `61db446`（L15 收口）；本 handoff commit 为新头，push 后再触发一条 master Run（预期绿）
