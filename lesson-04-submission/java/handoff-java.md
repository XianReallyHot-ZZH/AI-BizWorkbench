# handoff-java（上游五问）

1. **执行器如何参与 A 和 B，哪些决定归人**：A 票（V0 建设）由 Claude 在用户显式技能门（配合点 1 复认 / 配合点 2 implement）与候选分支 + 起始红机制内建造，审定与验收归人（A 具名验收 2026-09-30）；B 票由 Java V0 受控组织 `claude -p` 无头交付（组织权在 V0，执行器只见能力信封五问），交付接受与否归人（B 具名验收待签）。执行器不批准自己（自批拒绝在账）。
2. **FlowERP 新增什么可观察状态**：候选内 `delivery/` 五件——`build_practice.py`、`practice.db`（经 flowERP 自身服务种子的练习账）、`inventory.csv`（经既有导出入口产出）、`verify_inventory.py`、`expected_balances.json`；flowERP 业务源码零改动（vendors 双查恒空）。
3. **暴露什么可重复工程问题**：① 写集门以候选 git 实测为准，gitignored 产物（practice.db）天然不入 change_manifest——需要文件面独立复验兜底；② 跨 cwd 的 eval/执行器命令必须绝对路径（L04 Python 时代首跑丢记录教训的直接移植）；③ 无头会话的模型别名解析可能回退（stderr 留痕），交付质量判定必须落在独立复验而非执行器自述；④ `_now()` 秒级精度下任务编号若不按创建序字典序命名，账面投影排序跨跑不可复现（golden 前置生成期的实测教训）。
4. **工作台新增或验证什么能力**：Java V0 三命令（`workbench-task-run/-review/-show`）——受控执行（写集正反、五情境、执行记录同形、停 review）、具名复核（自批拒绝、execution_id 锚定）、摘要回显；golden l04 47 场景字节级对照 + 前置绑定门 + S-c1 摘要复核全部入验收门；A 门自举实跑证明 V0 第一次被真人 operator 使用即可用。
5. **哪些证据支撑方法迁移**：链三封条（red→diff→green 同命令 observed_at 严格递增）、golden 47 场景双跑指纹 + Java 字节级重放、probe N0/终验 rc 0 六场景、eval 权威账对照 rc 0、A/B 双任务账四词面密封、C9 前置绑定时间序（A review 先于 B 全部记录）、事件流 26,991 行落盘——"Spec—授权—前红—Diff—后绿—人审"交付循环在换语言后逐件可回查。
