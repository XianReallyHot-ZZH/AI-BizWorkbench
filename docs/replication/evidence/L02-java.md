# L02-java 重走证据账（lesson-02-java 前置与候选）

> ADR-0006 重走线第二讲证据。本账只追加；Python 时代证据见 [L02.md](L02.md)，不重开。移植注记与 golden 方案见 [L02-仓库规则.md 附录 A](../../lessons/L02-仓库规则.md)（2026-09-29 用户审定，"没问题，继续"批量确认：重建口径 + R1–R5 候选写入/R6 不写入 + golden 方案 + java/ 子根 + 任务号 + mvn test 链命令）。

## G. 对照基准（golden）生成 —— 2026-09-29（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 工作台（master，Python 实现、测试、pyproject 零改动）
- 生成工具：`tools/generate_golden_l02.py`（只用标准库；15 场景单会话按序驱动真实 CLI 子进程，与合同测试同形，不绕入口直调）
- 上游现查（起草时，2026-09-29）：`origin/main` = `7f67533`（纯课程文档加深，`course_mainline.py` 冻结合同零变化）→ 本讲基线保持 `a74445a`，不触发检查点采纳

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l02.py`（首跑） | 0 | 15/15 场景按预期退出码通过（t04/t15 预期 rc 1，其余 rc 0），EXIT=0 |
| G2 | 同命令（第 2 跑，确定性比对） | 0 | 15/15 ok，EXIT=0 |
| G3 | `find src/test/resources/golden/l02 -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`（两跑各一次） | 0 | 两跑目录指纹同为 `ed8aad6ca469e6a3b4d1ff6674d0f34ce917a81269953fbce738b1a362645768` → **golden 字节级确定** |

### 场景清单（15，逐条见 `manifest.json` 的 scenarios 数组）

审定方案表 t13 一行按单命令粒度展开为 t13–t15 三场景，语义不变（任务创建与追加本身无法并入查询场景）。

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 身份/项目/任务 | t01–t03 | init 成功 / 项目登记 / 带 spec 快照创建（T-GOLDEN-L02） |
| 链基线 | t04 | 缺链拒绝（零记录，`same_command_red_diff_green_missing`） |
| 链三记录 | t05–t07 | red/diff/green 追加（command_text = `mvn test`，fixture 常量） |
| 链完整 | t08 | `ok:true` + `evidence_complete:true` + `acceptance:pending_human_review` |
| **obs 不破坏链** | t09–t10 | 绿后 observation 追加成功（账本只追加）→ 链仍完整（**L02 流核心语义，l01 golden 未覆盖**） |
| 多 obs + 全量回显 | t11–t12 | 第二条 observation；无 require 标志全量回显（1 任务 5 记录形状） |
| **obs 不满足链** | t13–t15 | 第二任务 T-OBS-ONLY 建 1 条 observation → require-red-green 查询仍报缺链（t15 `errors` 逐字核过：`same_command_red_diff_green_missing: 缺少同命令的红—Diff—绿链`） |

### 规范化与掩码规则（与 golden/l01 逐字相同，Java 测试侧按 `manifest.json` normalization 块复现）

- 掩码字段（不可复现）：`created_at` / `recorded_at` → `<TS>`（墙钟）；`workbench_id` → `<WORKBENCH_ID>`（uuid4）
- `observed_at` **逐字保留**——合同字段（链时序语义），生成时固定字面量（09:00:01–09:00:05 +08:00 严格递增）
- canonical JSON：`sort_keys=True, ensure_ascii=False, indent=2` + 末尾换行；非 JSON 逐字保留（防御性）
- golden 共 16 个文件（15 份 stdout + manifest.json）；**生成后永不手改**，要变只能重生成并留证据

### 落位与继承

golden 前置生成，连同生成器与本 §G 随前置提交落 master（L01 先例：`evidence/L01-java.md` §G 落位说明）；`lesson-02-java` 自 master 分出即继承。

### raw 现场

`.runtime/golden-l02/raw/`（gitignore，不入库）：15 组 stdout/stderr + inputs 固定件。重跑生成器即可复现，不依赖本机留存。

---

## R. 候选 lesson-02-java 证据（2026-09-29，implement 授权前阶段）

### R0 环境与分支形状

- 环境：JBR Java 21.0.10（javac 21.0.10）、Maven 3.9.6、macOS（Darwin 25.6.0）；Python 侧 .venv（3.13，anaconda 托管——L00 既记偏离）
- 分支 `lesson-02-java` 自 master@`f445da0` 分出（继承前置 golden）
- 决策记录：**配合点 1 复认（2026-09-29）**——用户显式选择降级路径：讲义 §2 合同表 + 冻结合同 fixture 即 Spec，不调用 `/mattpocock-skills:to-spec`（Python 时代同意不延续，本次重新确认）；A6 候选表 R1–R5 写入、R6 不写入随同批量确认

### R1 起始红（C1）

- **红封条**：`lesson-02-submission/java/03-failure/20260929T051448833581Z-5b184e93/`（`mvn test`，rc=1）
- 红形态：40 跑 **3 红**（`JavaLineRuleFactsTest` 全部三条——CLAUDE.md 缺 Java 重走线规则要素：`mvn test` 词面缺席 / `src/main` 结构组缺席 / 冻结面组缺席，全部目标能力缺失类）+ **37 绿**（就位类：L02 翻译件 5 绿【Python L02 ReferencedCommandsAreReal 先例，映射表声明】、fixture 5 绿、既有合同 25 绿）
- **golden 双重放绿于红中**：GoldenL02ReplayTest 15 场景首次 Java 重放即字节级一致（就位类语义面锁定，移植注记 A3 预期）；GoldenL01ReplayTest 委托重构后行为不变
- 采红前失败两次（按"先修再采"处理，现场如实保留）：
  1. **golden 重放支撑重构滑手**：`GoldenReplay.goldenResource` 对已含前导 `/` 的资源名再补 `/`，双斜杠 classpath 查不到 → GoldenL01/L02ReplayTest 双双"必须在测试 classpath"红（预检跑，`target/test-classes` 中资源实存、manifest 可读，定位到拼接 bug）→ 修复后两重放绿
  2. **采集参数误读**：`--submission-root` 是完整相对路径而非父根下子名，首采落仓库根 `java/03-failure/20260929T051109701433Z-accdd5fa/`（rc=1，封条本身有效但位置错）——按写集纪律不入库、原样保留于工作树不删，处置待人审裁定；重采以正确子根落位

### R2 N0（先于规则修改，C7）——2026-09-29

- **会话配置**：`claude -p --permission-mode plan --model "glm-5.3-flash[1M]"`（用户确认，N1 将逐字一致），cwd 仓库根；`claude --version` 封存 = `06-observations/20260929T060121649835Z-1b7a7701/`（rc 0）
- **N0 封条**：`06-observations/20260929T060126846312Z-9b7d6952/`（rc 0）；副本 `java/N0.md`；修改前规则副本 `java/agents-before.md`（= 当前 CLAUDE.md 逐字拷贝，此刻尚未修改）
- **结论：五条全部判拒，N0 正确**（讲义 §1 预授权情形，如实保留未制造失败）
- **与 Python 时代 N0 的结构差异（诚实记录）**：Python 时代 N0 自述"依据**不是**根 CLAUDE.md（尚未写入边界），而是 vendors 合同+讲义"；本次 N0 直接指认"CLAUDE.md「FlowERP 业务边界（L02 固化）」"逐条引用边界 1–4 + 铁律 2/3 + 边界 5 判拒——因边界已在 Python 时代验收固化。**依据来源维度在 N0 时点即已锚定根 CLAUDE.md**，N0→N1 不预期出现"依据来源变化"型差异；本讲改善维度落在文本层（Java 线要素从缺到在，红点组转绿），行为层为回归性验证
- N0 引用仓库事实核过（`workbench/bootstrap.py` acceptance 硬编码、`WorkbenchBootstrapContractTest` 断言、golden 覆盖）——干净进程自行读取，无诱导
- CLI 回显 `unrecognized_model`（词面小写化）与 open.bigmodel.cn 网关提示、stdin 3 秒告警，均留原始捕获（Python 时代同形词面）
- 自称"评审结论已备案至计划文件"实际无落盘（`-p` 计划模式未持久化，Python 时代同形观察）；`git status` 核查：仅本讲预期产物，写集纪律未破
- **时间序（C7）**：N0 observed_at `2026-09-29T06:01:26Z` < CLAUDE.md 修改时刻（尚未发生，commit 2 前核双证）

### R3 写规则（implement 授权后）+ 后绿 + N1 行为对照 —— 2026-09-29

**配合点 2**：用户显式调用 `/mattpocock-skills:implement`（触点 2）后动笔。

**CLAUDE.md 增量（commit 2 写入侧）**——只加已确认规则（A6 R1–R5，候选外零写入），每段可指认：

| 候选项 | 落点 | 内容 |
|---|---|---|
| R1 | 常用命令补 Java 块 | `mvn test`（全量门）/ `mvn test -Dtest=…`（单类）/ `./bin/wb workbench-status`（L01-java 账本示例） |
| R2 | 架构大图补 Java 双轨块 | src/main 四包分层 / CourseContracts 双载体 / golden l01+l02 资源位 / 投影件 / pom 白名单 / bin/wb 包装；另两处事实性补记：adr 列表补 0006、submission 树补 lesson-02-submission 行 |
| R3 | 结构约定·双轨纪律 | Python 实现面冻结不触碰 + tools 复用不重写 + Python 账本零写入；**仍然允许面**（冻结外照常演进、误触即回退）与**失败后状态**（冻结面字节不变）同段写明 |
| R4 | 两处过期陈述修正（唯一改写项） | ① 常用命令"eval harness 属 L05+，尚不存在"→"已随 L05/L06 建成并冻结"；② 结构约定待建设清单 → 双轨口径（Python 已建成冻结 / Java 待建设） |
| R5 | 重走线指针 | Java 块头行：状态唯一事实源 = roadmap 重走线行，CLAUDE.md 不缓存进度 |

**红转绿**：`mvn test -Dtest='L02WorkbenchRulesTest,JavaLineRuleFactsTest'` 8/8 绿（预检）→ 全量封条见台账 #6/#7。

**N1 / 边界对照 / 迁移练习**（配置与 N0 逐字一致；N1、边界、迁移为三个独立会话——P1 教训 v2 式）：

- **N1**（`06-observations/20260929T061559378756Z-aa696e32/`，rc 0）：**读取层**——指认根 CLAUDE.md 并带行号引用（铁律 :11-18 / FlowERP 边界 :20-30 / 信用内核 :122）+ CONTEXT.md 措辞；**行为层**——五项逐条引用边界条款判拒（边界 1→:24、边界 2→:25…），判拒后"原数据不变"随引。C8 双半齐。
- **边界对照**（`06-observations/20260929T061846801034Z-6773b692/`，rc 0，独立会话原文逐字）："**可以继续设计**"+ 方案四句逐条正向映射（铁律 3/信用内核、铁律 2/边界 5、展示层不写库）——C9 放行。
- **迁移练习**（`06-observations/20260929T062810996330Z-b5b91238/`，rc 0，新会话）：引用边界 4 判拒"先加库存明天补审批"；申请=推进"待批准"的意向记录 vs 批准=具名人工决定；库存核查方法在答。附加观察（讲义 §3.E 口径）。
- N0→N1 差异如实记录：判拒结论两者一致（N0 已正确），N1 增量为**条款行号级引用**（N0 为段落级指认）——与移植注记 A1 预期一致，不声称行为翻转。
- 人类可读副本：`java/N1-and-boundary.md`（三段全录）；`java/agents-after.md`（修改后规则副本）；副效应核查同 N0（计划文件自称落盘实际无、`git status` 仅本讲产物）。

**回归（C10）**：`mvn test` 全量 observation `06-observations/20260929T064001784121Z-2db66206/`（rc 0，40/40）；程序检查不替代 N0/N1 行为证据（三层分检声明保持）。

**链态（commit 2 采集后）**：red `…5b184e93`（05:14:48，rc 1）→ diff `…120054ad`（06:12:41）→ green `…c8c2ae2f`（06:12:42，rc 0）——同命令 `mvn test`、observed_at 严格递增、Diff 严格居间。

### R4 任务账与终核（C11/C12）—— 2026-09-29

- **具名**：`--owner`/`--actor` = `XianReallyHot-ZZH`（用户 AskUserQuestion 确认，不代填）；账本 `.runtime/course/L01-workbench-java/`（L01-java 活账本沿用，Python 时代账本零触碰）
- **任务创建**：`./bin/wb workbench-task-create … CASE-WB-L02-JAVA-001 --spec-file docs/lessons/L02-仓库规则.md` → `ok:true`，requirement_summary `25dc5d1bb701…`（spec 快照）
- **导入前缺链查询**（预期失败也是证据）：observation `06-observations/20260929T064745623806Z-8031aa30/`（rc **1**，缺链词面）✓
- **链导入**：Java `workbench.tools.ImportEvidence` ×9 全 rc 0（重算 SHA-256 验封）——顺序 red→diff→green（链）→ observation×6（version→N0→N1→边界→迁移→回归，绿后导入不参与链判定）
- **终态 status 密封采集**：`06-observations/20260929T064829178371Z-6ae4bdb4/`（rc 0）——`ok:true` / `evidence_complete:true` / `acceptance:pending_human_review` / `flowerp_connected:false`；本任务链 red(rc1)→diff→green 同命令，observation×6 在账不破坏链（golden t09/t10 语义的活账复现）
- **C12 非目标核查**：`git diff master...lesson-02-java --name-only` 仅 CLAUDE.md / src/test / lesson-02-submission/java / evidence——无 FlowERP 业务、无 eval.harness、无 Hook/MCP 路径命中；**Python 冻结面零触碰**（`git diff master...lesson-02-java -- workbench tests pyproject.toml tools/capture_evidence.py` 为空）；vendor 双查恒空
- **待验收裁定项**：① 采红参数误读的错位封条（仓库根 `java/03-failure/…accdd5fa`，未入库原样保留于工作树）处置；② `.idea/` 未跟踪目录系 IDE 产物，非本讲写集，不入库

---
