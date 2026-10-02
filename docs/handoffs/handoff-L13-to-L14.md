# Handoff：AI-BizWorkbench — L13 完成收口（operate 首讲 + 检查点自建讲义首例），下一会话 L14 开局

- 生成：2026-10-02（同会话完成：fetch 现查无新上游 → 用户裁决**检查点自建讲义** → 讲义起草 → 「没问题，继续」D1–D9 批量签收 → to-spec（用户显式）→ implement（用户显式）→ 起始红 6 → 复查轮双轴 → 具名验收 → 合并收口 → push 一次成功 + CI 绿 → 教训入记忆 → 本 handoff）。上一会话起点 = [handoff-L12-to-L13.md](handoff-L12-to-L13.md)
- 本文件**接替**上一份 handoff；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `6948cb4`，已 push，领先 0）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：收口后实跑 **Tests run: 243, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（= 237 既有 + L13 新 6：合同测试三面六用例）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@406f7aa`（检查点 0003；本会话两次 fetch 现查 pin 后无新提交）。新会话**仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代，只读封存）；`L01-workbench-java/`（Java 线账本，L13 终态见下）；`L07/L08/L09-candidate/L10-candidate/L10-runs/L11-candidate/L11-integration/L11-integration-replay/`（勿动）；**`L13-delivery/`（L13 任务 API 运行库——受控实验数据在，含 TASK-ACF2CB5493 完整链；实验复演可读勿改写）**；`.runtime/hooks/` 持续增长；无后台服务与未收任务
- CI：收口 push Run `37002353308` **success**（46s，checkout `6948cb4`，旧门照跑即绿）；本 handoff commit 推送后再触发一条（预期绿）
- **大件警示**：`lesson-13-submission/06-observations/20261002T110514137329Z-5f86e87e/output.txt` = **52.76MB**（status 密封件——GitHub >50MB warning，未超 100MB 硬限；**原件不可改不可移动**——meta.json 绝对路径封条）。后续采 status 前先探输出体量（教训⑤已入记忆）

## 新会话必读：两道活护栏（都在生效）

1. **Stop hook**（L07）：每次回合结束过闸（净树 15–25s）；被 block = gate 如实报告，修真实原因勿绕闸；详见 [hook_staging/README.md](../../hook_staging/README.md)
2. **CI 工作流**（L08）：`.github/workflows/l08-eval.yml`——L09–L13 五讲均裁定不动（**改工作流前先读 CI_GATE_SPEC**，门规格是合同写集映射件；远程接入新检查面留检查点统一裁量）

## L13 终态与遗留（详见 [docs/replication/evidence/L13.md](../replication/evidence/L13.md)）

- 任务 `CASE-WB-L13-001`：verify `verify_completed`（execution 14，actor Claude），task_state review；链闭合 red(116) < diff(117) < green(118) + obs 119–121；具名验收行已入账 §7（验收人 XianReallyHot-ZZH，接受）
- 交付件：`workbench/delivery/` 七件（TaskStore 九态机/幂等/并发守卫/recover + SpecFactory 六段模板/信号词路由 + Feedback 观察自动接受具名 + Workflow 四段链**绿报告只进 review** + DeliveryAutomation 有界重试/失败沉淀 + HttpApi 四端点/JDK 内置零新依赖 + DeliveryServe）+ **REGISTRY `delivery-serve`**（第五工作台命令，常驻 HTTP；缺省 suite = 客户 purchase_requires_approval 原名照跑 + normalizeReport 平铺→summary 包装）+ `workbench/evals/l13/` DeliveryChecks 十件默认门
- **受控 API 实验**（lesson-13-submission/13-api/）：真实四拍 202 → review 停住（真实客户 eval 绿）→ 具名批准 completed → 重放同 id/异需求 409；**一起真实缺陷被实验抓获**（缺省 suite 平铺报告缺 summary 包装——attempt1 失败原件保留）；信用内核护栏实录（record 120 `task_in_review`——「停在人工审核」工作台面实证）
- 复查轮双轴：硬违规零/越界零；真缺陷 S-3b test-first 修（失败名去重失效先红后绿）；补拍 T-2 未绿批准拒绝 + T-6 recover 中断重放；spec/讲义勘误六处；不修入账四件（均上游同形）；处置表 §3.6
- D1 范围裁决先例（**后续自建讲次可承**）：范围 = 绑定 eval 断言面（cases.py + task_api_contract + HTTP 测试）+ 合同验收；基线 tag 无代码区分度（进度标记）；上游 main 后期增量不采纳清单落账
- **无未解决悬案**；候选 `lesson-13` 六 commit + 验收合并 `7a08dd3` + 收口 `6948cb4`

## L13 执行教训（已入记忆 feedback-l13-execution-discipline，此处仅指针）

sqlite **读写连接分面**（轮询读不能抢写锁——IMMEDIATE 事务设计里读路径用独立 autocommit 连接）；**合成缝机检测不出真实面报告形态**（Checks 合成 suite 永绿，缺省 suite 平铺报告分歧只有真实链路实验能抓）；capture 命令须与实跑**逐字一致**；verify 成功后不能补采（task_in_review 护栏——capture 与正式跑同步）；status 密封件可能是巨输出（52.76MB 教训）。

## 下一会话任务：主线 L14（operate 第二讲）

**L14 上游讲义 pin 内未上架**（本会话 fetch 现查目录止于 L12）——开局第一件事即 fetch 现查；有新上游走检查点裁决（ADR-0003），无则沿用 L13 先例走**检查点自建讲义**（D1 范围口径可直接承袭：绑定 eval 断言面为界 + 基线 tag 无代码区分度 + 后期增量不采纳清单）。

L14 合同现查口径（`CourseContracts.java` LESSONS[14]，以新会话现查为准）：主题「让交付状态在 Web 面板可见」；workbench_increment「可查询任务与工作台面板」/ erp_increment「交付 ERP 操作页」；描述「在无密钥 Web 面板展示 ERP 权威状态和交付证据，并能下钻到任务事件」；demo `REQUIREMENT:COURSE-L14`；写集 **`web/ workbench/ workbench_web/ tests/`（web 首次进写集；上游 workbench_web/ 前端件在 pin 内——ls 可见 app.js/daily.html/index.html 等）**；验收三条「页面数据来自 API 而非静态假数据」「DOM、API、SQLite 与 ERP 状态可对账」「页面不包含凭据」；绑定 eval 三件 `delivery_evidence_and_review_controls` + `web_api_and_persistence_projection_agree` + `no_committed_secrets`（后两件均客户/上游 eval——`eval/cases.py:479` web_api_and_persistence_projection_agree 在场）。**L13 的 capabilities/status_url/view_url 三查询面就是 L14 的数据源**（讲义 §1.5 因果交接）；上游 `delivery_view.py`（完整面板投影）与 `workbench_web/` 是对照面候选（L13 时按 D1 裁掉的部分——views 简化投影、code_execution 簇字段——L14 按 L14 断言面重裁）。

## 配合点（届时停下提醒用户）

- 开局 fetch 现查 → 有新上游走检查点裁决（ADR-0003），用户裁决
- L14 讲义候选表：用户具名确认（一次列全等一句）
- `/mattpocock-skills:to-spec`（用户显式；L08–L13 先例已立：接缝确认 → spec 落 `docs/specs/`）
- `/mattpocock-skills:implement`（用户显式，动笔前）
- 收口前：`code-review` 双轴（模型自调，并行 sub-agent + S/T 编号处置表——L13 先例：真缺陷 test-first 修 + spec 勘误六处入账）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L14 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| spec 合成 | `/mattpocock-skills:to-spec` | 用户显式 |
| L14 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 机制图（L14 Web 面板大概率需要） | `archify` | 模型自调 |
| 用户要深学机制 | `/mattpocock-skills:teach` | 用户显式 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求后的「没问题，继续」= 签收/授权（L13 验收与 push 授权均此口径）
- 收口时照例提请用户 push（L13 push 一次成功；**52.76MB 大件 warning 出现**——若再遇超大输出先探体量再采；HTTP 400 分段预案记忆仍在：核真实领先数用 `git rev-list --count origin/master..master`，勿口头估数）
- 执行纪律见记忆 feedback-l07/l08/l09/l10/l11/l12-execution-discipline + **L13 新增五条（feedback-l13-execution-discipline）**

## 教学线（learning/，用户自定节奏，不催）

- L07–L11 复盘课未做、L12/L13 复盘课新候选、0001/0002 测验仍未作答、cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-10-02）

- `mvn test` → **Tests run: 243, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**，rc 0（收口 commit 时点实跑）
- push 一次成功：`138f7ed..6948cb4`（10 commits）；GitHub 大件 warning 一条（52.76MB，见上）
- CI Run `37002353308` **completed success**（checkout `6948cb4`，46s）
- master 头 = `6948cb4`（L13 收口）；本 handoff commit 为新头，push 后再触发一条 master Run（预期绿）
