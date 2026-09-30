# L07 讲义：本地护栏 Hook——提交前真的检查过

- 状态：**定稿 v1**（D1–D8 已裁决，见 §5 裁决记录；执行期待用户显式 `/mattpocock-skills:implement`）
- 起草：2026-09-30；上游 pin `CodexFDE@7f67533`（起草日 fetch 现查未移动，无检查点动作）
- 合同来源：`src/main/java/workbench/coursecontracts/CourseContracts.java` LESSONS[number=7]（冻结合同，逐字引用不修）
- 上游参考：`vendors/CodexFDE/docs/courses/L07/`（五件套 + L06检查接入手册 + examples 三实验 + `.codex/` 参考链路，只读对照）
- 本讲性质：**首个无 Python 先例的讲**——重走线止于 L06，golden 重放机制不适用本讲；走新讲机制（L01–L06 原创建流程同款），实现面 Java。上游 Codex Hooks 口径按 ADR-0002 映射 Claude Code hooks（映射表 §1.4，映射方案随 §5 候选表一并确认）

## 1. 讲解

### 1.1 本讲要什么

L06 交出的统一 Harness 回答的是**怎样检查**：登记 → 分级执行 → 报告 → 退出码。L07 的问题是**什么时候检查**：检查再可靠，修改后忘了运行，旧报告不能替新版本作证——上午 V1 绿，下午改了金额计算变 V2，上午那份报告对 V2 无效。Hook 是「某个事件发生时被调用的处理器」；本讲把统一 Harness 接到 Claude Code 的 Stop 事件（准备结束一轮工作时），让"结束"这个时点自动触发检查。三层分工不混：**Eval 判断对错、Harness 运行汇总、Hook 定时点调入口**——Hook 绝不另写一套金额公式，否则同一项目两套标准，一个绿一个红时说不清哪个是现行口径。

### 1.2 业务案例：草稿订单金额（以分计）

两行订单：A 商品 2 件每件 3000 分 + B 商品 3 件每件 2000 分，独立预期 **2×3000+3×2000=12000 分**，两行明细各 6000 分。教学缺陷形态（上游同款）：漏乘数量只加单价 → 5000 分。迁移输入 3×2500+2×1800=**11100 分**（明细 [7500, 3600]）——排除写死 12000 的假修复。草稿语义：创建不预占库存（创建与预占是两个业务动作，原子预占属 L08）；零库存商品也能建草稿；数量为 0/负、第二行商品不存在均拒绝且**不留残留**。客户实现已在客户仓库在场（§1.3），本讲业务面是**检查与触发**，不是重写。

### 1.3 客户真理现查（讲前 2026-09-30，`vendors/flowERP` submodule 只读）

1. **草稿订单门面在场**：`flowerp/service.py:176` `create_order`——校验客户/明细非空、数量>0、单价≥0、去重 SKU；显式 `order_id` 即稳定身份（`SO-…` 缺省生成）；`total = sum(line.line_total_cents)`（`service.py:188`）；落库 status=draft、行级商品存在性检查（NotFound）；**创建不动 stock 表**（草稿不预占 ✓）；
2. **合同绑定 eval 在场且对教学缺陷可见**：客户 `order_total_matches_lines`（`eval/cases.py:136`，blocking 登记 `eval/harness.py:32`）——单行 3×1250，断言 total == Σlines == 3750。漏乘缺陷下 total=1250 ≠ SQL 算的 line_total 3750 → **红**（非盲区）；但它**只覆盖一行金额**，多行/拒绝无残留/迁移均不覆盖——上游明示「仅默认通过不代表新增要求全部执行」，本讲补；
3. **缺陷锚点可构造**：`service.py:188` 求和行在文件内唯一命中，可植入漏乘变体（L05/L06 锚点唯一性校验同款机制）；上游参考检查 `examples/order_contract_checks.py` 三函数（draft_amount / rejected_order_has_no_residue / amount_transfer）即照此口径写，独立整数预期；
4. 客户另有 v2 `SalesService`（`flowerp/sales.py`，正式订单状态机 draft→confirmed→reserved、取消释放预占），但课程绑定 eval 走 L01 门面——照跑不换面，v2 面属 L08 绑定 eval `sales_credit_and_atomic_reservation` 的领地。

**结论**：业务实现与绑定 eval 均在客户在场，本讲增量如实转为——①**订单面检查**（上游三检查函数的 Java 对应物：探针驱动客户实现 + 预期与断言留 Java）挂进统一入口；②**Hook 处理器与待审配置投影**（`hook_staging/`）；③**真实 Stop 事件的红绿证据链**（安装后宿主真实触发，手工喂输入不算）。客户 eval 照跑不删弱不改写（ADR-0005）。

### 1.4 宿主映射（ADR-0002）：Codex Hooks → Claude Code hooks

上游参考链路（`vendors/CodexFDE/.codex/`，只读对照）：`.codex/hooks.json` 注册 Stop → `.codex/hooks/quality_gate.py` 读事件（校验事件名与 cwd）→ 重入分支（`stop_hook_active` 跳过）→ 子进程 `python -m eval.harness --suite blocking`（内部 100s < 外层 120s 双层超时）→ 翻译为协议响应（失败 → `{"decision":"block","reason":…}` 含输出尾十行；通过 → continue 类响应；异常 → block「未完成验证」，处理器自身恒退出 0）。生成与启用分开：产物先落 `hook_staging/` 待审，人工审查后安装，再验真实事件。

本仓库映射（Claude Code 侧协议 = 官方文档现查 + 实证，2026-09-30，claude 2.1.283 / macOS，三组 `claude -p` 临时目录实测；执行期如有版本漂移按 `/hooks` 只读视图与官方说明复核）：

| 上游（Codex） | 本仓库（Claude Code） |
|---|---|
| Stop 生命周期事件 | Claude Code `Stop` 事件（每次答完话准备结束本轮时触发；matcher 不适用，写了被静默忽略；`if` 字段在 Stop 上写了 hook 永不运行——两坑都不踩） |
| `.codex/hooks.json`（项目 Hook 配置） | `.claude/settings.json` 的 `hooks.Stop` 段（项目共享、可提交；各层 settings 的 hooks 合并追加不覆盖；本仓库现无任何 hooks 配置，安装目标位干净） |
| `hook_staging/hooks.json` + `hook_staging/quality_gate.py` 待审投影 | `hook_staging/`：`settings.hooks.json`（待审安装片段）+ `target.json`（pin 的检查目标树与指纹）+ `README.md`（安装/回滚/重指流程与指纹清单）；处理器本体 = Java 源件（随候选分支入库受审） |
| 处理器 `quality_gate.py`（stdin 事件 → 校验 → 调统一入口 → 协议 JSON） | REGISTRY 新命令 `quality-gate`（settings 命令 `"${CLAUDE_PROJECT_DIR}/bin/wb" quality-gate`——`sh -c` 执行、路径占位符实测可用；读 stdin 事件、cwd 校验、重入分支、子进程调统一入口、双层超时、协议翻译、事件与响应落盘 `.runtime/hooks/`） |
| stdin 事件五字段（事件名/cwd/stop_hook_active…） | 同名五字段全部在场（`hook_event_name`/`session_id`/`transcript_path`/`cwd`/`stop_hook_active`，实测确认）+ 额外字段（`last_assistant_message`、`background_tasks`、`session_crons`、`permission_mode` 等）如实入事件存档 |
| 协议响应 `{"decision":"block","reason":…}` / `{"continue":true,…}` | block 双路等价实测：exit 0 + stdout `{"decision":"block","reason":…}`（reason 必填）或 exit 2 + stderr；gate 统一走 exit 0 + JSON（stdout 混日志即破坏协议——诊断走 stderr）；通过 = exit 0 + `systemMessage`（用户面提示，10k 字符限） |
| 内部 100s < 外层 120s 双层超时 | 同数字：gate 子进程调统一入口 timeout 100s，settings `timeout: 120`（秒制，command 型默认 600 显式收紧；超时即取消 hook 放行停止——留痕入边界） |
| `stop_hook_active` 防死循环 | 同字段短路（实测 false→true 序列确认）+ 宿主硬保险：连续 block 8 次后强制结束回合（`CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` 可调） |
| 统一入口 `python -m eval.harness --suite blocking` | `workbench.evals.l07.OrderChecks`（登记项收口 main，挂 `EvalHarness`；见 §2 登记项构成） |
| 真实宿主事件（Codex 会话结束） | 真实 Claude Code 会话结束：`claude -p` 无头会话 Stop hook 照触发（实测 ×3；注意 `--bare` 会整体跳过 hooks——真实事件链禁用该旗子）；交互会话首次需 workspace trust 确认，`-p` 视目录已信任 |
| `/hooks` 审查信任状态 | `/hooks` 只读视图核对注册与来源；`--debug-file` 落调试日志；gate 把 stdin 事件 JSON 转写落盘 = 官方输入契约的正当证据用法（比解析 transcript 可靠——transcript 异步滞后） |

映射的**不变量**（上游教学点逐条承接）：生成/审查/安装/真实验证四步分离；`hook_staging/` 不被宿主自动加载；安装是人工授权动作（逐项合并不覆盖、前后指纹留证）；手工喂事件 JSON 只算协议测试，**不算真实事件**；处理器退出码成功 ≠ 业务通过（两层退出码语义分开）；重入跳过没有产生新绿报告，最终显式复验不可省。

### 1.5 因果交接（本讲为下一讲准备什么）

L08 把同一套 Eval 接入 CI（远程复验与证据信封；本地与 CI 同一 Eval 身份）。本讲交出的订单面登记项与报告 schema 1.0（L06 已对齐客户）就是 CI 侧复用的同一身份；Hook 解决的"本地时点"与 CI 解决的"远端时点"互补——本地 Hook 通过不能代替真实 CI 运行（上游口径）。L08 业务面（订单原子预占）另起，不提前。

## 2. 本讲合同（C 编号清单）

blocking / observing 标注。write_scope 映射（合同四路径 → 本仓库）：`flowerp/` → `.runtime/course/L07-*/` clone 内客户实现（业务缺陷注入/修复只发生在 clone，永不写回 vendors，铁律 1）；`eval/`、`tests/` → `src/main/java/workbench/evals/l07/` + `src/test/java/`；`hook_staging/` → 仓库根 `hook_staging/`（入库）。安装产物 `.claude/settings.json` 不在合同写集内——安装是验收前的人工授权动作，留证随收口提交（§5 D6）。

| C | 验收项（来源） | 类型 | 怎么验 |
|---|---|---|---|
| C1 | 合法明细生成草稿订单和稳定身份（合同 acceptance[0]） | blocking | 订单探针驱动客户实现：两行建单 status=draft、显式订单号原样保留、独立预期 12000 与明细 [6000,6000] 由 Java 算出（不抄返回值）、零库存商品可建草稿且 stock 行前后不变 |
| C2 | 非法数量被拒绝且不留下订单（acceptance[1]） | blocking | 三态拒绝：数量 0 / 数量负 → ValueError，第二行商品不存在 → NotFound；`sales_orders`/`sales_order_lines`/`stock` 三表前后快照逐表相等（上游 rejected_order_has_no_residue 同形） |
| C3 | 订单总额等于明细合计 + 绑定 eval `order_total_matches_lines`（acceptance[2] + evalCases） | blocking | 客户 blocking 用例在目标 clone 子进程照跑绿（绑定 eval，业务权威）；迁移 11100/[7500,3600] 排除写死 12000 |
| C4 | 待审 Hook 配置和处理器存 `hook_staging/`，调用统一 Harness（acceptance[3]） | blocking | `hook_staging/` 三件入库（配置片段/target pin/README 指纹）；处理器六类协议合同测试全绿（pass/block/reentry/malformed/timeout/command-error——替身 harness 命令，上游 hook_protocol_lab 同形映射）；处理器不复制业务判断（只组装登记项调统一入口，无第二套金额公式） |
| C5 | 人工审查后安装到实际候选，保存真实事件的违规反馈与恢复复验；仅业务 Eval 通过不代表 Hook 验收完成（acceptance[4]） | blocking | 安装：用户审查 `hook_staging/` 指纹后具名授权，逐项合并进 `.claude/settings.json`，前后指纹留证；真实 Stop 事件红（缺陷在场：block 响应 + 会话续跑记录 + 重入跳过如实入账）与绿（修复后 pass）各一份，事件 JSON/处理器响应/Harness 报告三件落盘；修复后**显式**同入口复验绿（重入跳过 ≠ 通过）；证据账分列业务证据与触发证据，不以其一替另一 |
| C6 | 同命令红绿链入账（本仓库信用内核口径） | blocking | 业务链：`OrderChecks --target` 同参数红（缺陷树）→ diff（一行恢复）→ 绿（同命令）；真实事件链：settings 内静态 hook 命令红绿（树内缺陷在/不在）；`capture_evidence.py --submission-root lesson-07-submission` 采集 + `ImportEvidence` 导入，observed_at 严格递增；`workbench-status --require-red-green-evidence` |
| C7 | 检查冻结不被偷偷改动 + L05/L06 能力不回归 | blocking | 客户 `eval/harness.py`+`eval/cases.py` 双树同指纹（L05/L06 机制承袭）+ 客户 `flowerp/service.py` 缺陷树/修复树指纹对账（缺陷补丁即 diff 证据）；本讲 Java 源件（OrderChecks/OrderProbe/QualityGate 及 cli 注册缝）指纹初冻 + 收口复核；`mvn test` 全量绿（含 golden l01–l06 六重放） |
| C8 | 边界与证据分类留痕 | observing | 手工协议实录单独归类（协议证据 ≠ 真实事件）；真实超时的进程终止行为未实测（替身只测 TimeoutExpired 翻译，上游同款边界；外层超时即取消 hook 放行停止——语义如实入 README）；`--bare` 跳过 hooks 与 `if` 字段陷阱（Stop 上写了永不运行）入 README 边界注记；宿主信任面（交互首用 workspace trust、`-p` 视为已信任）与 8 次强制续跑上限按实测记录；安装后交互会话每次停止过闸属预期行为，如实入账 |

登记项构成（`OrderChecks` 收口，gate 的 blocking 面；对上游接入后七项指定检查，差异如实记录——本仓库 l05/l06 件本就同仓在位，无需接入脚本）：`l07_draft_amount`（blocking，C1）、`l07_rejected_order`（blocking，C2）、`l07_amount_transfer`（blocking，C3 迁移）、`l07_order_total_regression`（blocking，客户绑定 eval 子进程照跑）、`l06_stock_regression`（blocking，L06 口径检查进程内复用——旧能力在 gate 面在场）、`l07_frozen_checks`（blocking，指纹复核）。observing 教学项不入 gate 面（上游 hook 只跑 blocking；observing 语义 L06 已演示，不重复）。

## 3. 实操流程 + Claude 简报

1. **前置（审定后、动手前）**：讲义定稿入 master。本讲无 golden（无 Python 先例），无共享件扩展；回归门 = 既有全量 `mvn test`（含六重放）。
2. **候选分支 `lesson-07`**：【配合点·to-spec 复认】`/mattpocock-skills:to-spec` 用户显式，未调用则降级口径每次重新确认（L02–L06 先例，起始红采前问一次）。commit 1 = 合同测试（子进程缝：`bin/wb quality-gate` 未注册、`workbench.evals.l07.OrderChecks` main 缺席、`hook_staging/` 投影缺失——三面起始红，Java 编译不绑实现类，L06-java 编译绑定教训承袭）+ 红证据（capture + `ImportEvidence` 落账）。
3. **实现转绿**（用户显式 `/mattpocock-skills:implement` 后动笔）：`workbench/evals/l07/` 两件——`OrderProbe`（内联 python 探针片段，`StockProbe` 同款缝：子进程一次跑完订单场景、回报原始观察 JSON，**不判对错**）+ `OrderChecks`（登记项收口 main，预期与断言留 Java，`EvalHarness` 报告/退出码）；REGISTRY 注册 `quality-gate`（stdin 事件校验 → cwd 校验 → 重入分支 → 子进程调 `OrderChecks`（timeout 100s）→ 协议翻译 → 事件/响应/报告落盘 `.runtime/hooks/`）；`hook_staging/` 三件。
4. **缺陷注入与业务链**：`.runtime/course/L07-candidate/`（干净 clone，建树指纹入账）→ 锚点唯一性校验植入漏乘变体（`service.py:188` 求和行 → 只加单价；教学缺陷，授权与 Diff 记录在案）→ `OrderChecks --target` 红（客户绑定 eval 红 + 多行红，decision block、rc 1）→ capture 红。
5. **协议实录（手工，分类为协议证据）**：真实 stdin Stop 事件 JSON 手工喂 `bin/wb quality-gate`（缺陷树）→ block 响应实录落盘；重入输入（`stop_hook_active:true`）→ 跳过响应实录。上游 5.1 同款：能证明适配链路，不能证明宿主触发。
6. **安装（人审后）**：用户核对 `hook_staging/` 指纹 → 具名授权 → 合并 `.claude/settings.json`（Stop → `./bin/wb quality-gate`，timeout 120）→ 安装前后指纹与 diff 留证。安装后本会话停止即过闸（预期，如实入账）。
7. **真实事件链**：`claude -p "<中性提示>" --output-format stream-json --debug-file <路径>`（本仓库根，事件流与 hook 调试日志落盘；**勿用 `--bare`**——会整体跳过 hooks，实测确认）→ 真实 Stop 触发：**红**（缺陷在场：gate block + reason，会话续跑可见，再停 `stop_hook_active:true` 重入跳过，会话结束；三件证据落 `.runtime/hooks/` 并 capture）→ **修**（clone 内一行恢复，diff 落账）→ **显式绿**（同命令 `OrderChecks --target` 绿，capture）→ **真实事件绿**（再次 `claude -p`：gate pass，干净结束）。
8. **迁移与边界 observation**：迁移 11100 已在登记项（C3）；手工协议/真实事件分类对账（C8）；未实测边界如实标注。
9. **verify 集成**：`./bin/wb workbench-task-run CASE-WB-L07-001 --workspace .runtime/course/L07-candidate --mode verify --eval-command "<OrderChecks 命令>" --execution-timeout 900 --actor <向用户索取>`（L06-java 先例同形）→ `ImportEvidence` 导入观察类 → `workbench-status` 密封。
10. **收口**：`code-review` 双轴（master...lesson-07）→ 修复轮（若有）→ 用户按 §4 具名验收 → `git merge --no-ff lesson-07`（合并信息含验收人与结论）→ roadmap 阶段 4 行 + 讲义导航 + CLAUDE.md 待建设清单（本地护栏建成；`course-status` 等上游命令仍待建）。

### Claude 简报（第 3 段粘贴用）

> 在 lesson-07 候选分支上：①以合同测试（`bin/wb quality-gate` 未注册 / `OrderChecks` main 缺席 / `hook_staging/` 投影缺失三面）采起始红，capture + ImportEvidence 落账；②实现 `workbench/evals/l07/` 两件（OrderProbe 探针——子进程 python 驱动 clone 内客户实现回报原始观察，断言留 Java；OrderChecks 六登记项收口——l07 三新 + 客户 order_total_matches_lines 子进程 + l06 口径回归 + 指纹复核）+ REGISTRY 注册 `quality-gate`（stdin 事件校验/cwd 校验/重入跳过/子进程调 OrderChecks timeout 100s/协议翻译/事件落盘 `.runtime/hooks/`）+ `hook_staging/` 三件，六类协议合同测试转绿；③`.runtime/course/L07-candidate/` 建树（指纹入账）→ `service.py:188` 锚点唯一性校验植入漏乘变体（教学缺陷授权记录）→ `OrderChecks --target` 红（block/rc 1）capture；④手工 Stop 事件 JSON 喂 gate：block 与重入跳过实录（协议证据，单独归类）；⑤用户审 `hook_staging/` 指纹后具名授权安装 `.claude/settings.json`（timeout 120，前后指纹留证）；⑥`claude -p` 真实事件链：红（block+续跑+重入跳过三件证据）→ clone 内一行恢复（diff 落账）→ 同命令显式绿 → 真实事件绿；⑦verify 集成 + ImportEvidence + status 密封（CASE-WB-L07-001）。vendors/ 只读；失败记录一律保留；真实事件与手工协议证据分类不混。

## 4. 人审清单

- **看哪个 diff**：commit 1 = 合同测试三面起始红（**无**实现）；commit 2 = `workbench/evals/l07/` 两件 + cli 注册缝 + `hook_staging/` 三件 + 绿证据；安装 diff = `.claude/settings.json` hooks 段（人工授权动作，随收口提交，可 revert 回滚）；业务"修复"只是 `.runtime` clone 内 `service.py` 求和行一行（对本仓库出现大业务 diff 即红旗——业务实现在客户仓库，本讲只做检查与触发）。
- **跑哪些门**：亲手 `mvn test`（全量含六重放）；亲手对缺陷 clone 跑 `OrderChecks`（应 block/退出 1/定位 5000≠12000）；手工喂 gate 一份 Stop 事件（应 block 翻译，退出 0）；`workbench-status --require-red-green-evidence`；核对客户 eval 双树 sha256 与冻结值一致；查 `.runtime/hooks/` 真实事件三件证据（事件 JSON/响应/报告）与 `claude -p` 事件流。
- **什么算作弊**：手工喂 JSON 冒充真实事件（证据分类混装）；跳过安装人审直接写 `.claude/settings.json`（或安装 diff 无指纹留证）；把重入跳过记成通过；修复后不显式复验只拿真实事件绿凑数（或反之）；gate 内出现第二套金额公式；删弱客户 eval 或改指纹；红绿不同命令/不同目标树；`hook_staging/` 与安装后配置指纹不一致仍声称已审。

## 5. 候选表（决策点，一次列全，等具名确认）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| D1 | 宿主映射方案（ADR-0002） | **Claude Code Stop hook**：`hook_staging/` 待审三件（settings.hooks.json 片段 + target.json pin + README 流程指纹）→ 人审授权后合并安装 `.claude/settings.json`（`timeout: 120` 秒制显式收紧，默认 600 过长）→ `claude -p` 无头会话取真实事件（L04 先例；hook 触发已实测 ×3）。协议已实证：block = exit 0 + stdout `{"decision":"block","reason":…}`；`stop_hook_active` 短路防死循环 + 宿主 8 次强制上限双保险；命令 `"${CLAUDE_PROJECT_DIR}/bin/wb" quality-gate` | 照抄上游 `.codex/` 目录形态在本仓库造同名结构（Claude Code 不加载，形同摆设）；Git pre-commit hook 承载（上游明示：触发时机不同，会话结束可能未提交、提交前可能又改——不选）；PreToolUse 拦提交动作（那是拦"提交"不是拦"收工"，与合同"提交前本地护栏=准备结束工作时的质量门"语义不合） |
| D2 | 处理器与统一入口落点 | `quality-gate` 进 REGISTRY（`./bin/wb quality-gate`——合同 workbenchIncrement「提交前本地护栏」即工作台能力，命令面稳定可审，合同测试经 Cli 子进程缝）；统一入口 = `workbench.evals.l07.OrderChecks`（独立 main 不进 REGISTRY，l05/l06 先例——hook 命令引用它经 gate 子进程，argv 面不入 settings） | 处理器作 evals 独立 main（settings 命令串暴露 classpath 细节，难审难读，不取）；OrderChecks 也进 REGISTRY（两入口语义混置） |
| D3 | 缺陷注入与红绿链形态 | `.runtime/course/L07-candidate/` 单树承载全链：建树指纹 → 注入漏乘（锚点唯一性校验）→ 显式红 → 手工协议实录 → 安装 → 真实事件红 → **同一棵树**一行恢复（diff 落账）→ 显式绿 → 真实事件绿（上游"同一候选 V2→V3"同形；红绿同命令 = `--target` 同参 / settings 静态命令） | 缺陷树/修复树两棵分开（丢失"同一候选修复"教学点，且 diff 不再是实际发生过的一行恢复）；缺陷注入 vendors（铁律 1 禁止） |
| D4 | 真实事件触发与证据口径 | `claude -p "<中性提示>" --output-format stream-json`（中性提示不诱导改码，只要停止时点）；证据三件 = gate 落盘的事件 JSON + 处理器响应 + Harness 报告（`.runtime/hooks/` → capture 入 lesson-07-submission/），另存 stream-json 事件流佐证续跑；交互会话自身的过闸记录如实入账（活的护栏证明） | 交互会话手工停等触发（时点不可控、证据面弱）；伪造事件 JSON（作弊红线） |
| D5 | gate 调统一入口的进程形态与超时 | gate **子进程**跑 `java -cp … workbench.evals.l07.OrderChecks --no-report`（timeout 100s，stdout 尾十行入 reason——上游 gate→harness 子进程隔离同形，超时可真实终止；与 l06 JD2「Checks 子进程再起 java」不同语境：那是进程内复用驱动逻辑，这是处理器边界隔离，如实记录差异）；外层 settings `timeout: 120`（秒）；两层余量 20s。协议出口统一 exit 0 + stdout 纯 JSON（诊断走 stderr——stdout 混日志即破坏协议解析，实测确认按 `{` 开头才读 JSON） | gate 进程内直调 `EvalHarness.run`（无整体可终止边界，超时语义只剩子进程面，弱于上游——不取） |
| D6 | 安装与留痕口径 | 安装 = 用户审 `hook_staging/` 三件指纹 → 具名授权（一句确认）→ Claude 执行合并（逐项合并不覆盖已有键；`.claude/settings.json` 此前不存在，首个文件即安装产物）→ 前后指纹 + diff 随**收口提交**入库（回滚 = revert 该文件）；`target.json` 长期守卫目标默认持续指向 `.runtime/course/L07-candidate/`，验收后如需重指（如 vendors 客户真理面）另行小步配置变更并留指纹 | 安装产物只落 settings.local.json 不入库（安装无留痕、护栏非仓库资产，不取）；target 重指混入本讲候选分支（写集外动作，验收粒度失控） |
| D7 | 分支/证据/任务/账本命名 | 分支 `lesson-07`（主线先例，无 -java 后缀——重走线已完结，Java 是唯一活动实现线）；证据账 `docs/replication/evidence/L07.md`；任务 `CASE-WB-L07-001` @ `.runtime/course/L01-workbench-java/`（唯一活动账本；-JAVA 后缀是重走线消歧物，主线不再需要）；采集根 `lesson-07-submission/`（永不移动） | 全套 -java 后缀（与"重走线已完结"的事实不合）；复用 lesson-06-submission（跨讲混链） |
| D8 | 无 golden 的验收门替代 | 验收门 = ①合同测试（六类协议 + 三面工具）②真实事件证据链（红绿各一份三件证据 + stream-json）③指纹冻结对账（客户双树 + 本讲 Java 源件初冻/收口复核）④全量 `mvn test`（含 golden l01–l06 六重放——既有能力回归门）。如实声明：本讲无字节级对照基准，防护面 = 合同测试 + 指纹 + 真实事件 | 现在为 L07 补建 golden 基准（无 Python 先例可采，先例缺位硬造即自造对照，违背 golden「冻结 Python 采出」语义——不取） |

> **裁决记录（2026-09-30）**：D1–D8 推荐方案**全部接受**。用户原话：「没问题，继续」（批量确认，逐字入账）。起草日现查：CodexFDE pin `7f67533` 未移动（无检查点动作）；flowERP 客户真理四条事实成立（`service.py:176` 草稿门面与 `:188` 唯一锚点、`order_total_matches_lines` 在场且 blocking 且对漏乘缺陷可见非盲区、只覆盖一行金额）；Claude Code hooks 协议 = 官方文档 + claude 2.1.283 实测（block 双路、`-p` 触发 ×3、`stop_hook_active` false→true、`--bare` 跳过 hooks、timeout 秒制默认 600）。
