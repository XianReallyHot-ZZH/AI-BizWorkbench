# WB-L04-BOOTSTRAP

## 来源

L04：委托执行器完成一次最小变更（docs/lessons/L04-受控执行.md，用户确认 2026-09-27）。本合同是 Ticket A 的建设合同：先补齐并复验工作台 V0，A 未具名验收不启动 Ticket B。依据：L01 任务账与命令证据账（workbench/bootstrap.py）、L02 仓库规则（CLAUDE.md）、L03 六段 Spec 解析器（workbench/spec.py）、ADR-0002 执行器合同（写集、预算、JSON 事件流、沙箱边界、退出码）。

## 目标

补齐并复验工作台 V0，使其能够：绑定 Spec 创建任务（含前置任务绑定）、在受控模式下调用执行器（Claude Code 无头会话，测试经替身执行器）、限制写入范围（写集事前声明 + 事后实测比对）、保存执行证据（invocation、write_scope、change_manifest、diff、退出码、超时标志，逐项 SHA-256）、运行最小 Eval（绑定候选工作目录）、生成结果摘要，并把结果停在人工复核前（review 态 + 具名复核命令 + 自验拒绝）。

## 非目标

- 本 Ticket 不实现库存导出（Ticket B 的事，且 flowERP 终态已有该能力，B 按 ADR-0005 驱动交付而非从零实现）。
- 本 Ticket 不把自动检查通过写成业务验收通过：eval 通过只推进到 review 态，`acceptance: pending_human_review` 恒待人签。
- 本 Ticket 不修改客户仓库 vendors/flowERP（只读铁律不变；B 的候选为其本地 clone）。
- 不建 serve-workbench Web 服务、不建 eval.harness（L05+）、不建 course-submit 与根 FDE_SPEC.md（讲义 D5/D6，挂待建设清单）。

## 约束

- 必须声明工作目录、允许写集和超时；三者缺一，code 模式拒绝启动。
- 越界、失败或超时必须停止推进并保留证据：越界在 eval 前停止，不自动回滚（停止推进 ≠ 自动还原），旧尝试记录保留不覆盖。
- 已经存在的能力只复验，不重复实现。
- 新命令沿 REGISTRY 注册缝挂接（workbench- 前缀），不建第二套解析与存储；执行记录与上游 07-real-codex-executor.json 同形。
- 五类情境各有可观察行为：仅复验（不调用执行器进程）、缺写集（拒绝启动）、失败/超时（输出与 Diff 保留）、越界（eval 前停止）、待人审（review 态拒绝重跑）。
- 测试经 CLI 真实子进程；合同测试用替身执行器，其证明边界（证明 V0 机制，不证明真实 claude -p 行为）须写入证据。

## 验收用例

- 正常任务（code 模式，替身执行器在写集内改动，Eval 通过）保存调用、输出、退出码、change_manifest（逐文件前后 SHA-256）与 Diff，状态停 review。
- 仅复验模式不调用执行器进程，只运行 Eval。
- 缺写集/工作目录/超时声明，拒绝启动且执行器进程未被调用。
- 越界写入（退出码 0）在 eval 前停止：状态记 out_of_scope，越界文件留证，Eval 未运行。
- 执行器失败（退出码非零）与超时：stdout/stderr 与 Diff 保留，状态如实记 failed/timeout，不写成成功。
- Eval 失败如实保留；review 态拒绝再次运行且不毁待审状态。
- 复核命令记录具名 reviewer 与 approve/reject；执行者自批被拒绝；B 任务创建时前置任务缺失或未具名接受则拒绝且不静默链接。
- workbench-status 完整性复核覆盖新增记录（SHA-256 复核），acceptance 恒为 pending_human_review。

## 完成定义

由非执行者（用户具名）核对当前源码版本、实际 Diff、命令结果与退出码、五类情境证据和剩余风险，按讲义 §4 A 段清单具名接受；A 验收记录时间先于 B 段全部记录。
