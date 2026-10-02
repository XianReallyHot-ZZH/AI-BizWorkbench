# L12 受控对账表：Graph 状态 × ERP 权威状态（合同 acceptance[2]，讲义 D6）

- 时间：2026-10-02（候选 `lesson-12` @ `93b01d4`）；教学 reviewer 如实标注（真实具名验收 = 用户按讲义 §4 恒例）
- **graph 端的 test 节点跑的是真实统一检查**（`workbench.evals.l12.ApprovalChecks --target vendors/flowERP`，非合成替身）——「ERP 权威状态」面对应真实检查报告
- 双端同源性机检：`state-approved.json` 内嵌 `report` 字段与 `business-gate.json` **JSON 相等**（同一次子进程 stdout，同一来源）

## 原件清单（本目录，永不移动）

| 原件 | 内容 | sha256（前 12） |
|---|---|---|
| `state-awaiting.json` | graph 等待态封存（run 1，rc 3） | `1facc03dca70` |
| `state-approved.json` | graph 批准后终态（run 2，rc 0） | `830765d7175c` |
| `business-gate.json` | graph test 节点产出的业务检查报告（10/10 pass） | `b64fb21e6712` |
| `state.json` | 终态现行文件（== state-approved.json） | — |

## 双端身份对账（人工核对表，手册第 8 步同形）

| 对账问题 | Graph 端（状态文件） | ERP 端（业务报告行） | 一致性 |
|---|---|---|---|
| 软件交付走到哪里？ | `status: awaiting_human_review → completed`；trace 四步（develop→test→human_review→awaiting→completed），round 1 | — | 轨迹在案，可逐条回查 |
| 检查依据是哪份报告？ | `report_summary: {total: 10, passed: 10, blocking_failed: 0, decision: pass}`；内嵌全量报告（== business-gate.json，同源机检过） | `business-gate.json` 同一报告 | ✅ 同一来源 |
| 谁接受软件？ | `reviewer: TEACHING reviewer`、`review_decision: approve`、`reviewed_at: 2026-10-02T07:52:12Z`（教学替身，如实标注） | — | 具名留痕在案 |
| 哪张采购单获批入库？ | —（业务决定属 ERP 端，两种批准不互替——讲义 §1.2） | `l12_approval_approved`：PR-TARGET `received`、`approved_by: TEACHING business reviewer` | ✅ 对象分开记录 |
| 入库后数据一致吗？ | — | `17/2/15`、恰一条 +7 流水（`receipt:target`）、OTHER 与 PR-OTHER 保留（探针五表阶段快照 Java 断言） | ✅ |
| 重试重复入库吗？ | — | `l12_approval_replay`：五表不变 + `idempotent_replay=true` | ✅ |
| 故障后怎么继续？ | — | `l12_approval_recovery_same_key`：部分提交实录（17/approved）→ 原键重试补齐、不重复加库存；**恢复成功不证明第一次原子性成立** | ✅ 如实分层 |

## 复现命令（cwd = 仓库根；`$CP` = `target/classes:$(cat target/child-classpath.txt)`）

```bash
# run 1 等待（rc 3 = 正常等待不是失败）——suite = 真实 ApprovalChecks
./bin/wb graph-run --require-human-review \
  --state-file lesson-12-submission/12-reconciliation/state.json \
  --suite-command "java -cp \"$CP\" workbench.evals.l12.ApprovalChecks --target vendors/flowERP --python .venv/bin/python --report-path lesson-12-submission/12-reconciliation/business-gate.json"
# run 2 具名批准（同一 state-file，rc 0；零新检查）
./bin/wb graph-run --require-human-review \
  --state-file lesson-12-submission/12-reconciliation/state.json \
  --suite-command "<同上（不会被调用）>" \
  --review-decision approve --reviewer "TEACHING reviewer"
```

- 两端各自的机检承载：graph 端 = `L12GraphRunnerContractTest` 十七用例；ERP 端 = `ApprovalChecks` 默认门十件（本表 `business-gate.json` 即其原件）。上游无单一跨端机检件（手册第 8 步人工对账表）——「可对账」= 证据链组织 + 人审核对，不硬造跨端机检（讲义 D6）。
