# Handoff：AI-BizWorkbench — L14 完成收口（operate 第二讲 + 自建讲义第二例），下一会话 L15 开局

- 生成：2026-10-02（同会话完成：开局 fetch 现查无新上游 → 沿 L13 先例自建讲义 → 定稿 D1–D10 签收 → to-spec（用户显式）→ implement（用户显式）→ 起始红 6 → 实现 → 两幕实验 → verify + 密封 → 复查轮双轴 → 具名验收 → 合并收口 → push 一次成功 + CI 绿 → L14 教训入记忆 → 本 handoff）。上一会话起点 = [handoff-L13-to-L14.md](handoff-L13-to-L14.md)
- 本文件**接替**上一份 handoff；事实以仓库 canonical 文件为准（状态唯一事实源 = [docs/replication/README.md](../replication/README.md)，勿轻信本文件转述）
- 存放：入库 `docs/handoffs/`（先例惯例）；同文副本放 OS 临时目录

## 仓库当前状态（master `faf9f58`，已 push，领先 0）

- 工作树 `?? java/`：**非悬案**——L02-java §6 具名验收裁定「保留不删不移」，历次保持不入库，勿提交勿清理
- 全量门 `mvn test`：收口前实跑 **Tests run: 249, Failures: 0, Errors: 0, BUILD SUCCESS，rc 0**（= 243 既有 + L14 新 6：合同测试六用例三面——与 commit 1 红基线逐字节一致，零改动纪律维持）
- vendors 双 submodule status 恒空（铁律 1）；pin `CodexFDE@406f7aa`（检查点 0003；本会话开局 fetch 现查 pin 后零新提交、课程讲义目录止于 L12）。新会话**仍须再 fetch，勿信本快照**
- 运行库（gitignored）：`.runtime/course/L01-workbench/`（Python 时代只读封存）；`L01-workbench-java/`（Java 线账本——L14 链 122–127 + execution 15 终态）；`L07…L13-delivery/`（勿动）；**`L14-panel/`（L14 幕一实验库，数据在，可读勿改写）**；`.runtime/hooks/` 持续增长；无后台服务与未收任务
- CI：收口 push Run `37015124749` **success**（42s，checkout `faf9f58`，旧门照跑即绿）；无新 push 则无新 Run
- **大件警示（新形态）**：`lesson-14-submission/06-observations/20261002T130706570031Z-1bb2d326/output.txt` = **118MB status 密封件，超 GitHub 100MB 硬限**——meta.json + SHA-256 已入库、output 本地封条完整（`.git/info/exclude` 排除，磁盘原件未动未删）。**根因是账本历史单调增长（L13 任务对象 59MB + L12 25MB + L06 15MB），非单讲增量**——后续每讲密封件会更大，瘦身面留检查点裁量（L15 讲前可议）。教训④已入记忆

## 新会话必读：两道活护栏（都在生效）

1. **Stop hook**（L07）：每次回合结束过闸（净树 15–25s）；被 block = gate 如实报告，修真实原因勿绕闸；详见 [hook_staging/README.md](../../hook_staging/README.md)
2. **CI 工作流**（L08）：`.github/workflows/l08-eval.yml`——L09–L14 六讲均裁定不动（**改工作流前先读 CI_GATE_SPEC**；远程接入新检查面留检查点统一裁量）

## L14 终态与遗留（详见 [docs/replication/evidence/L14.md](../replication/evidence/L14.md)）

- 任务 `CASE-WB-L14-001`：verify `verify_completed`（execution 15，actor Claude），task_state review；链闭合 red(122) < diff(123) < green(124) + 密封(125) + verify 观察(126/127——首跑缺 --execution-timeout rc 1 留盘）；具名验收行 §7（验收人 XianReallyHot-ZZH，接受 2026-10-02）
- 交付件：`workbench/delivery/` **DeliveryView**（完整投影纯函数——九态归属表逐字 + freshness review 不 stale + unknown 内联硬词面 + integrity 五类 + 列表八计数压缩汇总；实时构建不缓存）+ **HttpApi 增量**（views 列表默认 20 + view() 升级 + **verify 仅复验路由** + WEB_ROOT 静态 serve 穿越守卫/no-cache——无新命令）+ 本仓顶层 **`workbench_web/` 三件**（六面取数闭集、零依赖零 CDN）+ `workbench/evals/l14/` **WebPanelChecks 八件默认门**（投影三面 + HTTP/静态 + 面板静态断言含闭集负面断言 + no_committed_secrets 仓面扫描 + 受控 ERP 四向对账 + 冻结指纹七件）
- **受控两幕实验**（lesson-14-submission/14-web/）：幕一真实 delivery-serve 四拍（202 TASK-6D525D6F31 → review 停住 → 下钻九事件 → 教学替身具名批准 completed——export_candidate 因 REQ-COURSE-L 前缀出现）；幕二受控客户实例四向对账 api_skus == db_skus == [SKU-L14-PANEL-01] + 客户自测 11 例照跑——**客户源码零改动**
- 复查轮双轴：真缺陷 T-1（unknown 硬词面）test-first 修 + 补拍 T-3（投影与库同源）/T-6（取数面闭集）+ 小修五处 + spec/讲义勘误八处；不修入账 S-1/S-2；处置表 §3.6
- **扫描器自毒实录**（§3.1，本讲最有价值的失败）：首版密钥扫描被自己的 diff 采集件毒化（marker 字面回流）——markers 改运行时拼接构造 + diff-phase 采集件忽略政策；两个被毒化 v1 件留盘
- **无未解决悬案**；候选 `lesson-14` 五 commit + 验收合并 `0fe595d` + 收口 `faf9f58`
- 自建讲义先例已两例（L13/L14）——D1 范围口径（绑定 eval 断言面为界 + 后期增量不采纳清单落账）+ 起始红三面 + 复查轮处置表，第三例（L15+）可直接承

## L14 执行教训（已入记忆 feedback-l14-execution-discipline，此处仅指针）

扫描器**自毒**（检查器 marker 必运行时拼接——证据链会 diff 检查器自身）；status 密封件随账本历史单调增长、**超百 MB 即物理不可提交**（meta+SHA 入库 + 本地封条 + 缺口声明）；客户树遗留表陷阱（`products` 空表 vs 主数据 `product_master`——快照前先核真实表名）；测试断言面错位时**词面由实现侧承载**保合同测试红基线零改动。

## 下一会话任务：主线 L15（operate 第三讲）

**开局第一件事 = fetch 现查**（pin 后有无新提交、L15 讲义是否上架）；有新上游走检查点裁决（ADR-0003），无则沿 L13/L14 先例走**检查点自建讲义（第三例）**。

L15 合同现查口径（`CourseContracts.java` LESSONS[15]，以新会话现查为准）：主题「生成交付摘要并采集真实反馈」；workbench_increment「交付摘要、反馈审核、Memory/RAG 与演进记录」/ erp_increment「交付反馈驱动的 ERP 小改进」；描述「将一条真实采用反馈审核为改进任务，把已审核经验登记为可治理记忆，在另一事项中检索为带引用上下文，再通过工作台交付并保留升级前后证据」；demo `REQUIREMENT:COURSE-L15`；写集 `workbench/ flowerp/ eval/ tests/`；验收三条「原始反馈先审核再进入记忆候选与执行合同」「记忆来源、版本、状态、检索结果、采用快照和后续验证可追溯」「撤回或跨项目记忆不进入默认上下文，反馈与检索结果均不能直接改写阻断裁判」；绑定 eval 两件 `delivery_evidence_and_review_controls` + `raw_feedback_cannot_become_blocking`（**dynamic_eval_required=true——首见动态 eval 要求**）；story 15「Feedback/Evolution 共同建造者与受控改进执行者」。

**L14 → L15 因果交接**（讲义 §1.5 已埋）：L14 裁掉的投影 **evolution 块**与列表 `pending_feedback` 计数正是 L15 演进记录/反馈治理的增量补块面（spec D4 勘误已声明「L15 按断言面增量补块」）；L13 的 feedback 沉淀面 + L14 的 view 投影 feedback 块是 L15 的数据入口。上游 `workbench/evolution.py`、记忆系统（Python 冻结面时代建成、Java 对应物待建）与 RAG 检索面是对照候选——讲前 fetch 后再裁。

## 配合点（届时停下提醒用户）

- 开局 fetch 现查 → 有新上游走检查点裁决（ADR-0003），用户裁决
- L15 讲义候选表：用户具名确认（一次列全等一句）
- `/mattpocock-skills:to-spec`（用户显式；L08–L14 先例已立：接缝确认 → spec 落 `docs/specs/`）
- `/mattpocock-skills:implement`（用户显式，动笔前）
- 收口前：`code-review` 双轴（模型自调，并行 sub-agent + S/T 编号处置表——L14 先例：真缺陷 test-first 修 + 补拍 + 勘误八处入账）
- 换会话：`/mattpocock-skills:handoff`（用户显式；本文件即上一会话产物）

## Suggested skills（新会话按阶段调用）

| 时机 | 技能 | 方式 |
|---|---|---|
| 开局 fetch 现查 + 检查点 | —（fetch 后报告，ADR-0003） | 模型自查 + 用户裁决 |
| L15 讲义起草 | —（四段结构，写完等审） | 模型自拟 |
| spec 合成 | `/mattpocock-skills:to-spec` | 用户显式 |
| L15 执行 | `/mattpocock-skills:implement` | 用户显式 |
| 起始红→绿循环 / 遇障 | `tdd` / `diagnosing-bugs` | 模型自调 |
| 收口前复查 | `code-review`（候选 diff 双轴） | 模型自调 |
| 用户要深学机制（Memory/RAG 大概率） | `/mattpocock-skills:teach` | 用户显式 |
| 机制图（按需） | `archify` | 模型自调 |
| 换会话 | `/mattpocock-skills:handoff` | 用户显式 |

## 沟通

- 全中文；用户 = 唯一具名验收人；「待审核 ≠ 已接受」逐字使用；措辞继承根目录 CONTEXT.md 词汇表
- 用户确认风格见记忆 user-confirmation-style：候选表一次列全等一句确认；显式请求后的「没问题，继续」= 签收/授权（L14 验收与收口 push 授权均此口径）
- 收口时照例提请用户 push（L14 push 一次成功无大件 warning；密封件超限新形态与 HTTP 400 分段预案记忆均在案：核真实领先数用 `git rev-list --count origin/master..master`）
- 执行纪律见记忆 feedback-l07–l14-execution-discipline（L14 新增四条：扫描器自毒 / 密封件超百 MB / 客户遗留表 / 测试红基线零改动）

## 教学线（learning/，用户自定节奏，不催）

- L07–L11 复盘课未做、L12/L13/L14 复盘课新候选、0001/0002 测验仍未作答、cheatsheet v2 待办——详见 [learning/NOTES.md](../../learning/NOTES.md)

## 附记：交接时点实跑记录（2026-10-02）

- `mvn test`（复查轮修复后复跑，经 surefire 汇总核实）→ **Tests run: 249, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**
- push 一次成功：`a23cce6..faf9f58`（9 commits，无大件 warning）
- CI Run `37015124749` **completed success**（checkout `faf9f58`，42s）
- master 头 = `faf9f58`（L14 收口）；本 handoff commit 为新头，push 后再触发一条 master Run（预期绿）
