# Ticket B 复验证据（库存导出交付）

- **B 任务编号**：CASE-WB-L04-002（前置绑定 CASE-WB-L04-001，已具名接受 2026-09-27）
- **L03 Spec SHA-256**：`f3e816690867c1cfd329f7535bdfad64ad51fbf1077765272106a1450869dbe2`（shasum 实测；终验后复核一致——B 执行与验收依据同一份合同，未被改写）
- **隔离候选目录**：`.runtime/course/L04-delivery/candidate`（vendors/flowERP 本地 clone，起点 `e0088d3a11ae68b7e23c2c6986167b87768d909e`；不入 Git；首跑失败候选归档于 `candidate-lost-run1/` 不删）
- **实际写集**：`delivery/`（数据与产物）；flowerp/、tests/ 等产品代码不在写集——已有能力只驱动不重写（ADR-0005）。实测：changed_files 仅 `delivery/inventory.csv`（practice.db 为客户仓库 .gitignore 排除项，在工作树不在账内），out_of_scope_files 空
- **执行记录**（任务账内两条，均保留）：
  - execution 2（code 模式，真实执行器 `claude -p --permission-mode acceptEdits --allowedTools Bash Write Edit --output-format stream-json`，模型=账号默认（用户选定），提示词经 stdin，历时约 270s）：executor rc 0、未超时、交付 `delivery/inventory.csv`（before/after SHA-256 在 change_manifest）、**eval_failed**——V1 eval 误报（见下），记录如实保留
  - execution 3（verify 模式，交付物不动，V2 eval 重验）：verify_completed、eval rc 0 → 任务停 review
- **最小 Eval 演进与裁决**：V1（`07-eval-check.py` 初版）把 lot_number（LOT01/LOT02）硬编码进 lot_id 期望 → 对交付误报 eval_failed。裁决依据：独立复验器 probe 自身即按"候选真实批次 API 返回的内部 ID"构造期望（其文件头注明 "IDs come from the candidate's real lot API"），且 `delivery/practice.db` 的 stock_lots 账证实 CSV 的 `LOT-79C167CE…` 即 LOT01 的 ERP 账内身份——**导出忠于 ERP 权威账，错在 eval 期望**。V2 改为对照 ERP 权威账（SQL 只读取 stock_lots 身份 + 账面数量对照）+ Spec 业务常量与形状。V1 失败记录不删
- **BOM/CRLF 裁决**：CSV 带 U+FEFF BOM 与 CRLF 为候选既有导出行为（客户真理，ADR-0005）；probe 与候选自身测试均剥离 BOM 兼容，L03 Spec 未禁止——不构成违约，eval 同口径接受并留档
- **N0 基线（已有能力如实记录）**：`06-probe-n0/`（首候选）与 `06-probe-n0-r2/`（净克隆，同源起点）均全 pass、退出码 0
- **独立复验（交付后，`07-probe-final/`）**：probe `--case all --file-entry flowerp.inventory_export:export_inventory_file` **退出码 0**，逐项：normal pass（8−3=5、12−4=8 两行明细）/ empty pass（仅八列表头无伪造行）/ special pass（特殊字符 CSV 读回保真）/ ordering pass（打乱输入按 sku,site,location,lot_id 稳定排序）/ file-failure pass（注入中途写失败：报错、旧文件原样、无半成品、DB 不变）
- **只读性**：probe 各场景执行前后 sqlite iterdump 快照对照一致（report 与 evidence 文件在场）；交付驱动本身只读库存账（eval V2 经 ERP 账复核数值一致）
- **复验命令与退出码**：probe 终验 rc 0；eval（execution 3）rc 0；范围检查 `git -C candidate status --porcelain` 仅写集内一项
- **剩余风险**：①执行器会话 stderr 有 `[claude-code:unrecognized_model]` 警告（会话环境模型名泄漏至子进程，实际以账号默认模型完成，结果不受影响）；②lot_id 列语义为 ERP 内部批次 ID，若合同方期望导出 lot_number（LOT01/LOT02）需另行走检查点裁决；③执行器 stream-json 事件流全量留证于执行记录 stdout（约 1.5MB，SHA-256 复核覆盖），未逐事件人工审读；④B 任务尚未具名验收——**待审核 ≠ 已接受**
