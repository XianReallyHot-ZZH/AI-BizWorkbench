# L04 交接

1. **执行器如何参与 A 和 B？哪些决定仍由人负责？** A 票由人会话直接监督 Claude 建设工作台 V0（L01–L03 同形态）；B 票改由已具名接受的 V0 组织：工作台绑定候选与写集、经 stdin 送能力信封给 `claude -p` 无头会话、收回 change_manifest/Diff/事件流、跑最小 Eval、停 review。执行器全程不做决定：范围扩充请求要停下报告（本票执行器对 lot_id 语义与 BOM 分歧如实报告而非绕行，即此机制的实例）；需求确认、写集批准、接受/打回永远由人（用户）具名——B 的 CSV 正确也不构成验收。

2. **FlowERP 新增了什么可观察产品状态？** 无新增业务代码（终态即客户真理，ADR-0005）。新增的可观察状态是一份交付物：候选内 `delivery/inventory.csv`（八列、两行明细、批次身份与 ERP 账一致、可用量 5/8、稳定排序），以及其可复现来源 `delivery/practice.db`。导出行为本身的既有状态经六场景 probe 实证（含 BOM/CRLF 行为与写失败保护）。

3. **这次交付暴露了什么可重复的工程问题？** ①执行器合同与真实 operator 的接口缝（nargs 收 argv 被 `-X`/`-p` 终止）；②预算/启动失败必须落账（裸崩丢执行记录——首次 B 实跑的教训）；③**复验器自身会错**：eval 把 lot_number 硬编码进 lot_id 期望造成误报，靠"对照 ERP 权威账 + 独立复验器口径"裁决修正——检查器也要被检查；④跨 cwd 命令一律绝对路径。四条都已进合同测试或证据账，迁移到下个需求时随能力信封模板带走。

4. **工作台新增或验证了什么能力？** 新增：受控执行三命令（task-run verify|code / task-review / task-show）、写集事前声明+事后实测、执行记录同形上游（change_manifest 逐文件字节指纹、双流与 Diff 摘要、timed_out/eval_timed_out）、最小 Eval 绑定候选工作目录、复核门（review 态拒重跑、自批拒绝、前置任务须具名接受）。验证：两票全流程——A 由 V0 verify 自举复验，B 由 V0 code 组织真实 claude -p 交付，失败路径（eval_failed、launch error）均如实落账未丢现场。

5. **哪些证据能证明可以把这套方法迁移到另一个最小需求？** 可迁移件：`lesson-04-submission/WB-L04-BOOTSTRAP.md`（建设合同六段模板实例）+ `.runtime/course/L04-delivery/prompt.txt`（能力信封五问模板）+ `07-eval-check.py`（"期望值来自权威源而非执行器"的 eval 模板）+ 证据账 C1–C15 逐项核对面。迁移采购申请导出时：流程与命令面全复用；必须重问的业务口径=数据来源、权限、验收数值（同理 eval 期望不得沿用库存字面量）；check_inventory_delivery 类 probe 需按新对象重写场景。任务账 CASE-WB-L04-001/-002 的红绿链、执行记录、复核记录即全流程机器可查的先例。
