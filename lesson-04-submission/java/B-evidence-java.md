# B-evidence-java（B 票：V0 受控交付 SKU 库存导出，commit 3）

> C13 字段体例（Python 时代 `B-evidence.md` 同形）。**组织权在 Java V0**：本票交付由 `workbench-task-run --mode code` 受控组织（execution 2 在任务账），非人会话直接指挥的追认。导出口径如实：已有能力（`flowerp.inventory_export:export_inventory_file`）复验交付，非从零实现（ADR-0005 客户真理）。

## 字段

- **B 编号**：CASE-WB-L04-JAVA-002（`--prerequisite-task CASE-WB-L04-JAVA-001`，A 已具名接受后放行——C9 前置绑定账本可证）
- **Spec SHA-256**：`f3e816690867c1cfd329f7535bdfad64ad51fbf1077765272106a1450869dbe2`（`lesson-03-submission/FDE_SPEC.md`；运行前后两次核对一致——B 执行与验收依据同一份合同；Java CLI spec 入口解析 rc 0）
- **候选目录**：`.runtime/course/L04-delivery-java/candidate`（vendors/flowERP 本地 clone，起点 `e0088d3`；不入 Git、不写 submodule、不复制回仓库；`git -C vendors/flowERP status --short` 恒空）
- **实际写集**（执行后候选 `git status --porcelain -uall` 实测）：`delivery/build_practice.py`、`delivery/expected_balances.json`、`delivery/inventory.csv`、`delivery/verify_inventory.py`——全部在声明写集 `delivery` 内，`out_of_scope_files: []`；`delivery/practice.db` 被 flowERP 仓库既有 `.gitignore` 遮蔽不入 change_manifest（git 实测口径的既有语义），其存在与可读性由 eval 权威账对照独立证实（`authority_rows: 7`）
- **复验命令与退出码**：
  - 受控执行（execution 2）：`workbench-task-run --mode code --write-scope delivery --executor-command "sh -c 'claude -p --permission-mode bypassPermissions --output-format stream-json > …/executor-events.jsonl 2> …/executor-stderr.log'" --eval-command "…/.venv/bin/python -X utf8 …/lesson-04-submission/java/07-eval-check.py --workspace …/candidate"`（全绝对路径）→ rc 0，`completed`，eval rc 0（CSV 7 行 = 权威账 7 行，零失败）
  - N0 基线：vendor probe `--case all` 对未动候选 → rc 0 五组全 pass（`06-probe-n0/`）
  - 终验：vendor probe `--case all` 对交付后候选 → rc 0 五组全 pass（`07-probe-final/`，含 file-failure 注入真实失败实验与只读性 iterdump 对照——按 report 逐项非按结论文字）
  - eval（V2 形态，§3-17 裁决移植）：对照 practice.db 权威账（与 `import_export.py` inventory 查询同源的 SQL 只读）+ Spec 八列/公式/排序常量
- **六场景独立复验**：normal（8−3=5、12−4=8、4−1=3，多仓位批次不合并）/ empty（仅八列表头）/ special（中文逗号引号换行保真）/ ordering（sku,site,location,lot_id 升序）/ file-failure（probe 注入中途写失败：明确报错、旧文件原样、无半成品、DB 不变）/ 只读性（iterdump 前后一致）——N0 与终验两组报告在案，按 eval 名 `inventory_export_is_stable` 登记，**harness 双轨口径如实注明（Python 侧已建成冻结 / Java 侧待建设）**
- **Diff 与范围检查**：改动仅 delivery/ 四件（A 状态新增）；无密钥/.env 命中；`lesson-03-submission/FDE_SPEC.md` SHA 运行前后一致；vendors 双查恒空；候选内 flowerp 未复制回仓库
- **执行记录完整性（C3）**：invocation（argv/stdin 提示词/cwd=候选）/ write_scope / change_manifest / diff / returncode / timed_out / 双流 SHA / 事件流（`executor-events.jsonl` 26,991 行 stream-json 落盘可查）全量在任务账 executions 表
- **剩余风险（4 条，已知悉留档）**：
  1. `delivery/practice.db` 系 gitignored，写集门对它按 git 口径天然不可见——由 eval/probe 文件面独立复验兜底；未来交付若需强制可见，属工作台增量（待建设，不在本讲合同）。
  2. 执行器会话 stderr 记录 `[claude-code:unrecognized_model] {"model":"glm-5.3-flash[1m]"}`——用户默认模型串在无头会话侧未被识别（别名大小写回退），会话以回退模型完成交付；交付质量由独立复验判定不受影响，参数事实如实留档。
  3. 提示词约定 ≠ 事前全部防住（D4 如实声明）：事前 = 候选绑定 + argv 注入；事后 = 候选 git 实测比对；不自动回滚。
  4. 自批拒绝为名义级比较（§6 Python 口径承袭）；单人仓库内唯一真人具名验收人是该门最终权威。
- **责任口径（C6 措辞，逐字）**：调用正常结束 ≠ 业务正确；退出码 0 ≠ 客户结果正确——故有 probe 六场景独立复验与 eval 权威账对照，接受与否归人。
