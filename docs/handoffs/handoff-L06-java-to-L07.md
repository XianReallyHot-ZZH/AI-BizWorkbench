# Handoff：AI-BizWorkbench — 重走线收官 + Python 退役完成，下一会话启动主线 L07

- 生成：2026-09-30。上一会话完成：L06-java 验收合并（`4f6c0d6`）与收口（`d4b77cb`）+ Python 载体退役（`d6409f6`，用户确认「退役，继续」）；本会话仅产出本交接文档
- 本文件**接替** [handoff-L06-to-L07.md](handoff-L06-to-L07.md)（2026-09-29，Python 时代口径——其仓库状态段与「下一会话任务」段已被重走线与退役跨过；§L06 关键裁决与 §本会话新沉淀两段仍有效）
- 本文件只做**接力**；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)），不要轻信本文件的转述
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `d6409f6`，已 push，工作树唯一未跟踪项为已裁定保留物）

- **push 已完成**：`origin/master..master` = 0（旧 handoff 的「10 提交待推」事项已解决）
- 工作树 `?? java/`（仓库根 `java/03-failure/20260929T…-accdd5fa/`）：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」（首采 `--submission-root` 误读落错位，封条本身有效但位置错；L04-java 复核闭合不重开）。新会话勿清理、勿重开
- 全量门 `mvn test`：本交接时点实跑结果见文末附记（实跑命令 + 退出码，非转述）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@7f67533`。本交接 fetch 现查：最新远端 ref 仍为 2026-09-27（tag `course-package-20260928`、`origin/main`），无新提交——**新会话仍须再 fetch，勿信本快照**（上游日更）
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代账本，**只读封存**）；`.runtime/course/L01-workbench-java/`（Java 线账本，L06-java 终态：任务账 16 条证据 `evidence_complete:true`）；`L06-defect-baseline/`、`L06-candidate/` 等 L06 运行树仍可用；无后台服务

## 退役后新约束（ADR-0006 尾款已执行，2026-09-30）

- `workbench/`、`tests/`、`evals/`、`pyproject.toml` 四件**不得在工作树复活**；对照源 = tag `python-carrier-final`（@ `d4b77cb`）；机检护栏在 `L02WorkbenchRulesTest` / `SpecTemplateDualCarrierTest`
- 冻结合同 fixture 载体 = `src/main/java/workbench/coursecontracts/CourseContracts.java`（对照 = tag + `src/test/resources/coursecontracts/frozen-python-projection.json` 全字段投影机检）
- `tools/`（capture_evidence / golden 生成器 / 合同投影导出）保留，但**执行需 tag checkout**（golden 再生成、投影导出都如此）；`.venv` 与 python 本体保留（capture 采集与 evals 子进程 python 探针在用——客户实现是 Python）
- golden 对照基准 l01–l06 六套（`src/test/resources/golden/`）不变、永不手改

## 下一会话任务：主线 L07（阶段 4 质量链第三讲：Hook 本地护栏）

**关键定位——首个无 Python 先例的讲**：重走线止于 L06，L07 从未在 Python 建成，golden 重放机制**不适用**本讲；走新讲机制（同 L01–L06 原创建流程，实现面为 Java）。L06-java 的 `workbench/evals/` EvalHarness 已带「L07 Hook 入口」——统一入口解决**怎样检查**，L07 解决**什么时候实际检查**。

合同现查（`CourseContracts.java:147`，LESSONS[7]「用 Codex Hooks 建立本地护栏」，以现查为准勿凭本转述）：

- 交付目标：通过工作台交付销售订单创建，本地护栏复验订单金额和明细一致；写集含 `flowerp/`、`eval/`、`tests/`、`hook_staging/`（课程路径口径，映射在讲义定）
- 五条要求：①合法明细生成草稿订单和稳定身份 ②非法数量被拒绝且不留下订单 ③订单总额等于明细合计 ④待审查的 Hook 配置和处理器存 `hook_staging/`，调用统一 Harness ⑤**人工审查后安装到实际候选，保存真实事件的违规反馈与恢复复验；仅业务 Eval 通过不代表 Hook 验收完成**
- 绑定 eval：`order_total_matches_lines`（客户 EVALS 已有，L06 现查先例）

开局顺序：

1. `git -C vendors/CodexFDE fetch` 现查（报告结果；有新上游走检查点裁决 ADR-0003）
2. 通读上游 `vendors/CodexFDE/docs/courses/L07/` 五件套——注意 `L06检查接入手册.md` 是 L07 前置（七项检查注册到统一入口后再装 Hook）；上游 Codex Hooks 口径 → 本仓库按 ADR-0002 映射 Claude Code 权限/hook 机制，**映射方案在讲义定并经用户确认**
3. 客户真理现查：flowERP 销售订单面（ORDER:COURSE-DEMO 身份、草稿订单状态机）+ `order_total_matches_lines` 用例现状（铁律 3：取消释放预占等业务边界同步适用）
4. 起草 `docs/lessons/L07-*.md`（四段结构见 [docs/lessons/README.md](../lessons/README.md) + §5 候选表**一次列全等具名确认**——含证据账与分支命名口径：主线先例 `lesson-06` / `evidence/LNN.md`，本讲拟 `lesson-07` / `evidence/L07.md`），**用户审完讲义才动手**
5. 动工：`lesson-07` 候选分支（ADR-0004），commit 1 = 合同测试起始红 + 红证据（`tools/capture_evidence.py` 采集 + 账本导入，workflow 同 L06-java；采集教训见下节）

## 上一份 handoff 仍有效的段落（指针，勿重复翻找）

- [handoff-L06-to-L07.md](handoff-L06-to-L07.md) §L06 关键裁决（6 条）：传导链单点推导、harness 与复核器**两份独立实现是故意的**、登记制等级不得运行时调级、R1 快照锚点教训、客户 eval 盲区实证（是否补 reserved>0 用例留检查点显式采纳）、报告 schema 1.0 对齐客户 = L08 铺垫——L07 Hook 调用统一 Harness 直接踩在这些设计上，引用勿翻案
- 同文件 §本会话新沉淀（采集教训，capture_evidence 仍是 Python 工具，直接适用）：`exec` 吞命令走临时脚本、时变字段归一（`generated_at`/`duration_ms`）、clone 内采证用绝对 venv 路径、`git add -N` 让新文件进 diff、误采封存惯例（封存留痕不删不导入账本、证据账 §失败现场记一句）

## 教学线（learning/，用户自定节奏，不催）

- 0007 课已发布（L06 复盘：传导链可信度），结尾已埋 L07 引子「同一份检查，怎么保证提交前一定被跑过」
- 0001/0002 测验仍未作答；0006/0007 未复验；cheatsheet v2 待办（L04 三命令 + 辨别力三问 + 传导链四环）——详见 [learning/NOTES.md](../../learning/NOTES.md)
- 下一课候选：L07 验收后用户点名，handoff 不预设

## 配合点（届时停下提醒用户）

- L07 讲义候选表：用户具名确认（一次列全等一句）
- L07 执行期：`/mattpocock-skills:implement`（用户显式，到点停下提醒）；`/mattpocock-skills:to-spec` 同理（L04–L06 三度未用先例，降级路径须用户同意）
- 收口前：`code-review` 双轴复查候选分支（模型自调，L04–L06 四度先例）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即本会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点裁决 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L07 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| L07 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| Hook 机制图（如讲义需要） | `archify`（产 SVG 入讲义，先例口径） | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用
- 措辞继承根目录 CONTEXT.md 词汇表（避免各词条 _Avoid_ 替代词）
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求验收后的「没问题，继续」=签收
- L07 纪律同既往：候选分支 ADR-0004、证据账、失败不抹、vendors 只读；收口时照例提请用户 push（遇 HTTP 400 大载荷分段推送，见记忆 push-http400-staged-workaround）

## 附记：交接时点实跑记录（2026-09-30）

- `mvn test` → **Tests run: 109, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**，exit 0（历时 8:55，2026-09-30T17:09:43+08:00）
- `./bin/wb workbench-status --runtime-dir .runtime/course/L01-workbench-java --require-project PERSONAL-WORKBENCH --require-task CASE-WB-L01-JAVA-001 --require-red-green-evidence` → exit 0，`ok:true`，`errors:[]`，`evidence_complete:true`
- `git -C vendors/CodexFDE fetch` 后现查：无新提交（最新远端 ref 仍 2026-09-27），双 submodule status 恒空，pin `7f67533` 不动
