# Ticket B 复验证据（库存导出交付）

> 状态：受控执行进行中，本文件为骨架；执行结束后逐项回填，未运行项写"未验证"。

- **B 任务编号**：CASE-WB-L04-002（前置绑定 CASE-WB-L04-001，已具名接受）
- **L03 Spec SHA-256**：`f3e816690867c1cfd329f7535bdfad64ad51fbf1077765272106a1450869dbe2`（shasum 实测；任务账 requirement_summary 同值——B 执行与验收依据同一份合同）
- **隔离候选目录**：`.runtime/course/L04-delivery/candidate`（vendors/flowERP 本地 clone，起点 `e0088d3a11ae68b7e23c2c6986167b87768d909e`；不入 Git）
- **实际写集**：`delivery/`（数据与产物）；flowerp/、tests/ 等产品代码不在写集——已有能力只驱动不重写（ADR-0005）
- **执行记录**：workbench-task-run code 模式（执行器 `claude -p --permission-mode acceptEdits --allowedTools Bash Write Edit --output-format stream-json`，提示词经 stdin 送达，模型=账号默认，用户 2026-09-27 选择）；invocation/write_scope/change_manifest/diff/returncode/timed_out 全量在任务账执行记录内
- **最小 Eval**：`lesson-04-submission/07-eval-check.py`（在绑定候选 cwd 实跑；核对交付 CSV：八列、两行不合并、可用量 5/8、稳定排序——期望值来自 L03 Spec 而非执行器输出）
- **N0 基线（已有能力如实记录）**：probe `--case all` 于未动候选实跑全 pass、退出码 0（`06-probe-n0/`，交付驱动前采）
- **独立复验（交付驱动后）**：待回填（probe 六场景逐项 + 退出码）
- **Diff 与范围检查**：待回填（候选 git status vs 写集；无密钥/.env/运行库/无关改动；未改写 L03 Spec）
- **只读性**：待回填（probe before/after iterdump 快照对照）
- **复验命令与退出码**：待回填
- **正常库存结果**：待回填
- **空库存结果**：待回填
- **特殊字符结果**：待回填
- **稳定排序结果**：待回填
- **文件失败结果**：待回填
- **剩余风险**：待回填
