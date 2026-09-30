# Handoff：AI-BizWorkbench — L07 完成收口 + 本地护栏生效，下一会话启动 L08

- 生成：2026-09-30（同日三段：L07 讲义起草裁决 → 执行验收合并 → 本 handoff）。上一会话完成：L07 全流程（讲义 `75b3874` → 候选 `lesson-07` 五 commit → 验收合并 `42a2b01` → 收口 `efe9dfd`，全部已 push）+ L07 执行三教训入记忆
- 本文件**接替** [handoff-L06-java-to-L07.md](handoff-L06-java-to-L07.md)（其仓库状态段与「下一会话任务」段已被 L07 跨过；指针段仍有效）
- 本文件只做**接力**；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `efe9dfd`，已 push，`origin/master` = master，领先数 0）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：交接时点实跑 **Tests run: 122, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（2026-09-30 本 handoff 前，含 golden l01–l06 六重放 + L07 合同 13）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@7f67533`。本 handoff fetch 现查：无新上游提交（最新远端 ref 仍 2026-09-27）——**新会话仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代账本，只读封存）；`.runtime/course/L01-workbench-java/`（Java 线账本，L07 终态见下）；`L07-candidate/` 净树在位（`6c372dcd…`）；`.runtime/hooks/` = gate 事件归档持续增长；无后台服务

## ⚠️ 新会话必读：Stop 护栏已安装且生效中（L07 交付物）

- `.claude/settings.json`（入库 `a73224fb…`）：Stop → `./bin/wb quality-gate`，timeout 120s——**本仓库每个 Claude Code 会话（含你）每次回合结束都会过闸**，净树静默放行约 15–25 秒；`target/child-classpath.txt` 缺失时 bin/wb rc 2 不阻断（先 `mvn test`/`compile` 重建）
- 若被 block：那是 gate 如实报告检查失败（reason 点名失败登记项）——**修真实原因后显式复验**，不要绕闸（`--bare`/删 settings 是逃生口不是日常）；连续 block 8 次宿主强制结束（上限机制）
- gate 事件留痕 `.runtime/hooks/<ts>-<session>/`；待审投影与回滚/重指流程见 [hook_staging/README.md](../../hook_staging/README.md)
- `target.json` 目前 pin `.runtime/course/L07-candidate`（净树）——长期守卫目标重指（如 vendors 客户真理面）留用户另行小步决策（README 已写流程）

## L07 终态与遗留（详见 [docs/replication/evidence/L07.md](../replication/evidence/L07.md)）

- 任务 `CASE-WB-L07-001`：链 + 观察在账、verify `verify_completed`、`workbench-status` 终态 rc 0（`evidence_complete`）；**账面 approve 记录缺席**——verify actor 误填用户名致 `self_review_rejected` 死结（§8.3，无解缠，如实披露；验收事实源 = 证据账具名行）。**教训（已入记忆 feedback-l07-execution-discipline）：`--actor` 永远填实际执行者，Claude 执行填 `Claude`**
- 真实超时的进程终止行为未实测（README 边界注记标未验证）；账面 review 态永不改（账本只追加）
- 过程失误三起 + 前两起全在证据账 §8（README 回滚误伤 / 指纹尾段拼凑 / actor 误填）——都是教训不是悬案

## 下一会话任务：主线 L08（阶段 4 质量链收官：把同一套 Eval 接入 CI）

合同现查（`CourseContracts.java` LESSONS[8]「把同一套 Eval 接入 CI」，以现查为准勿凭本转述）：

- workbench_increment「远程复验与证据信封」/ erp_increment「交付原子预占」；绑定 evals：`stock_never_negative`、`sales_credit_and_atomic_reservation`（客户 blocking 在场）、`ci_evidence_envelope_is_honest`（新）；写集含 `.github/workflows/`、`workbench/ci_evidence.py`、`CI_GATE_SPEC.md`（课程路径口径，映射在讲义定）
- **L08 收口设上游检查点**（roadmap 阶段 4 行 + L08 检查点额外裁定：上游 L09–L15 讲义是否上架 → 决定后半程逐讲 or 按阶段）——fetch 现查后一并裁决
- L07 铺垫可直接踩：报告 schema 1.0 已对齐客户 harness（L08「本地/远端同一 Eval 身份」）；gate 的统一入口与事件留痕机制；`claude -p --bare` 跳过 hooks 的口径（CI 里想要确定性时反着用）

开局顺序（L07 同款）：

1. `git -C vendors/CodexFDE fetch` 现查（报告结果；有新上游走检查点裁决 ADR-0003）
2. 通读上游 `vendors/CodexFDE/docs/courses/L08/` 五件套 + 客户真理现查（`sales_credit_and_atomic_reservation` 用例现状、v2 SalesService 原子预占面——L07 讲前现查已见其在场；CI 侧 GitHub Actions 机制按 ADR-0002 映射定讲义方案）
3. 起草 `docs/lessons/L08-*.md`（四段结构 + §5 候选表一次列全等具名确认；命名拟 `lesson-08` / `evidence/L08.md` / `CASE-WB-L08-001`，actor 教训勿忘），**用户审完讲义才动手**
4. 动工：候选分支（ADR-0004），commit 1 = 合同测试起始红 + 红证据（采集根 `lesson-08-submission/` 新根，永不移动）

## 配合点（届时停下提醒用户）

- L08 讲义候选表：用户具名确认（一次列全等一句）
- L08 执行期：`/mattpocock-skills:implement`（用户显式，到点停下提醒）；`/mattpocock-skills:to-spec` 降级复认同理（起始红采前问一次）
- 收口前：`code-review` 双轴复查候选分支（模型自调，L07 刚走完先例）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点裁决 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L08 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| L08 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 机制图（如讲义需要） | `archify`（产 SVG 入讲义，先例口径） | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求验收后的「没问题，继续」= 签收
- 收口时照例提请用户 push（遇 HTTP 400 大载荷分段推送，见记忆 push-http400-staged-workaround——本讲一次推成未触发）
- 执行纪律见记忆 feedback-l07-execution-discipline（actor 填实际执行者 / 指纹只贴实测 / rc 不经管道尾）

## 教学线（learning/，用户自定节奏，不催）

- L07 复盘课未做（下一课候选由用户点名，handoff 不预设）；0001/0002 测验仍未作答；cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-09-30）

- `mvn test` → **Tests run: 122, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**，rc 0
- `git -C vendors/CodexFDE fetch` 后现查：无新提交（最新远端 ref 仍 2026-09-27），双 submodule status 恒空，pin `7f67533` 不动
- `git rev-list origin/master..master --count` = 0（push 已核实，efe9dfd）
