# CI_GATE_SPEC：L08 远程复验门规格

> 本仓库 CI 门（`.github/workflows/l08-eval.yml`）的规格文件——L08 合同写集映射件
> （上游课程路径 `CI_GATE_SPEC.md` 直接对应）。讲义 [docs/lessons/L08-远程复验与证据信封.md](docs/lessons/L08-远程复验与证据信封.md)（定稿 v1，D1–D9 已裁决）与合同
> fixture `CourseContracts.java` LESSONS[8] 是本规格的上游；冲突以讲义与冻结合同为准。

## 门定义（什么必须跑、退出码语义）

- **统一入口**：`workbench.evals.l08.SalesChecks`（独立 main，八登记项全 blocking）
  ——本地、CI 与显式复验调用**同一入口**（合同 acceptance[2]「本地与 CI 使用同一
  Eval 身份」）：同一命令、同一登记项、同一报告 schema 1.0（`EvalHarness`，L06 已
  对齐客户）。环境准备可以不同，业务标准不能偷偷变成两套。
- **退出码**：blocking 失败 → 统一入口 rc 1 → 工作流 Job 红（**非零退出码阻断候选
  变更**，合同通过标准）；信封步骤 `if: !cancelled()`——失败运行也绑信封归档，但
  上传成功不覆盖失败结论（两层退出码语义分开）。
- **构建面**：`mvn -q process-test-classes`（编译 + classpath 落盘）。**不跑 mvn
  全量测试**：终态合同测试断言本工作流无 `continue-on-error`，与 A/B 教学态自指
  冲突——全量 `mvn test`（含 golden 六重放 + 各讲合同）是本地门与验收门。
- **归档**：报告与信封落 `_ci_reports/<run_id>-<attempt>/`（非隐藏目录，规避
  upload-artifact v4.4 隐藏文件排除），artifact `if: always()` +
  `if-no-files-found: error`——**缺文件即报错，旧输出永不冒充本次证据**。
- **通过标准四条**（合同，逐字）：本地与 CI 使用同一入口；非零退出码阻断候选变更；
  自动修复不直接合并主分支；CI 通过不等于具名验收（合并仍走候选分支机制 +
  人工 `merge --no-ff`）。

## A/B/C 受控对照规则（lesson-08 分支历史专用）

同一工作流文件三个状态，每态只改一个层（Run 与 diff 可回查）：

| 态 | 注入步骤 | 统一入口步骤 | 预期 |
|---|---|---|---|
| A 假绿 | 在场（`InjectDefect` 拷贝树注入教学缺陷） | `continue-on-error: true`（字面吞失败，教学注入）+ `--target` 缺陷树 | Harness 报告 block（rc 1）、Job 绿——矛盾并存，证明「绿色」可能来自错误流程 |
| B 可信红 | 不变 | 只删 `continue-on-error` | Job 红——检查流程诚实，业务仍未修 |
| C 可信绿 | 移除（`--target` 回干净 `vendors/flowERP`） | 不变 | Job 绿——业务在同一标准下恢复正确 |

- 业务缺陷 = 上游 `injected-atomic-defect.diff` 逐字一行（`conn.commit()` 提前提
  交）；「缺陷在场/不在场」由注入装置表达——客户实现本就正确（讲义 §1.3），教学
  注入不伪称客户事故。
- **A 态（吞失败）与注入步骤永不进 master**：合并面 = C 态终态；A/B 态在 lesson-08
  分支历史可回查。终态合同测试（`ciWorkflowIsPresentAndFinalState`）锁终态无
  `continue-on-error`。
- 隔离运行：另做一次「报告未生成」Run（统一入口被跳过）→ 上传步骤
  `if-no-files-found: error` 报红、平台日志留证——证明无旧 artifact 冒充。

## 信封核对清单（复验者下钻顺序，不可倒置）

1. 从平台入口定位真实 Run（Run ID / attempt / 事件 / 分支头）；平台测合并结果时，
   实际 checkout SHA 与分支头不同可以合理，但必须能追溯。
2. 核对运行了什么：同一 `SalesChecks` 入口、八登记项、报告 schema 1.0。
3. 读分项结果与 summary（`decision`）、两层退出码（统一入口 rc × Job 结论）。
4. 下载 artifact，**解压后**对报告文件重算 SHA-256，与信封 `report_sha256` 对账；
   信封身份（`commit_sha`/`run_id`）与平台 Run 一致。
5. 信封是**关联证据的一层**，不是业务裁判，也不是合并授权——来源、内容完整、
  业务正确、人工接受是四种判断，后面的判断需要前面的依据，却不能互相替代。

## 边界（如实）

- SIMULATED 身份（`GITHUB_SHA=SIMULATED-*`）只用于本地机制实验，永不冒充远程 Run。
- 上传成功 ≠ 业务通过；报告存在 ≠ 本次生成（stale-output 边界：命令失败不写新
  输出，旧文件原样保留——`ci-evidence` 合同测试锁定）。
- `--python` 必须传绝对路径（`resolveInterpreter` 按 cwd 绝对化，不查 PATH）。
- 本仓库未配置 branch protection（合并必需检查）——单有红灯不能证明平台已启用
  合并限制，属平台治理动作，留用户另行决策（上游口径）。
