# L11 真实并行·乙交回产物（风险审查子代理原始回复，主 Agent 落盘）

- 委派载体：Claude Code Agent tool（general-purpose fresh 子代理）；与甲同一发起批次（主 Agent 发起 2026-10-02T03:04:16Z）
- **活动时间**：开始 `2026-10-02T03:04:15Z` → 结束 `2026-10-02T03:10:52Z`——与甲（03:04:09Z→03:07:20Z）**重叠 03:04:15–03:07:20**（原生并行可证）
- 零写入达成：运行验证全部 `:memory:` 库 + `python -B` + stdin 传脚本 + /tmp 起跑——拷贝树零文件创建

## 子代理回复原文（风险清单）

**输入核验**：`flowerp/service.py` / `store.py` / `models.py` SHA-256 与 `vendors/flowERP` 逐字节一致（6c372dcd… / fb2ff797… / 9084d4b2…）——以下全部为上游客户实现原样行为，非 L11 复刻产物引入。静态定位：service.py 对 `stock` 的写点仅 4 处（:170 入库 / :244 预占 / :270 释放 / :295 发货）；schema_v2 与 schema_extensions 的触发器（stock_balance 非负、journal 不可变等）无一挂在 `purchase_requests` 上，不存在隐式库存移动。

### 风险清单

**R1【已证实】收货两段事务窗口——成功显示成失败**。`service.py:399`（receive_stock 自带事务提交库存+事件）与 `service.py:400-404`（状态翻转是第二个事务）之间无原子性。触发：两事务之间任一失败（崩溃/busy 超时；本次以代理连接在 `UPDATE purchase_requests` 处注入 OperationalError 复现）。预期「失败不残留、失败不冒充成功且状态与库存一致」；事实：异常后库存已 +3、单据仍 approved、调用方收到异常。同 event_key 重驱可安全补齐（重放不加库存，status→received，事件数恒 1）。

**R2【已证实】换键重驱双重入库**。`service.py:397`（状态守卫）与 `receive_stock :157`（幂等只认 event_key）——单据身份不参与跨键防重。触发：status 仍 approved 的窗口内（R1 之后，或并发交错）用不同 event_key 再驱。事实：同一采购单落两条 +3 收货事件（EV-D3A/EV-D3B），库存双重增加。预期「同一入库幂等键只能生效一次」在键维度成立、在单据维度不成立。

**R3【已证实】event_key=None 完全绕过幂等——身份缺失被当幂等键**。`service.py:386` 签名不校验 event_key；`:157` `WHERE event_key=NULL` 永不匹配；`store.py:41` `event_key TEXT PRIMARY KEY` 无 NOT NULL，SQLite 允许多行 NULL。事实：两次 None 各 +2，两行 NULL 事件，全程无异常。幂等键必须存在且唯一的预期落空。

**R4【已证实】已消费 event_key 静默吞掉采购入库——失败显示成成功 + 账面无痕**。触发：传给 receive_purchase 的键已被其它收货消费（如手工 receive_stock）。`receive_stock :157-165` 重放分支返回成功快照，采购单翻 received，但库存分文未增，且 `inventory_events` 中 reference=该单 的收货事件数为 0——追溯链断裂（违反业务边界 5「失败不能显示成成功」）。

**R5【已证实·低危·后果项】双事件状态下重放守卫误拒**。`service.py:389-393` 按无序 `fetchone` 取 reference 对应的"那一次"事件；reference 非唯一（R2 终态）时两条键恰有一条被 InvalidTransition 误拒（复现：EV-E2 被拒、EV-E1 通过）。只影响重放便利性，不造成库存错误。

**R6【待验证·低危】`_publish_authority` 在库存事务外**（`service.py:171-173`，提交后才调）：若在该点崩溃，v2 `stock_balance` 镜像滞后。机制由代码结构可读出，未运行复现，如实标待验证。

### 范围内未发现（边界成立）

- **申请路径提前入库**：propose/approve/reject/purchase 四函数零 stock 写点（静态 grep + 运行证实 stock 恒 0/0），无触发器隐式移动。
- **覆盖旧申请**：重复 request_id → `sqlite3.IntegrityError` + 回滚，旧申请逐字保留。附带发现：错误面是裸 sqlite3 异常而非域错误（Conflict），错误契约未结构化，上游原样。
- **单事务内部分写入**：receive_stock 的 INSERT 事件与 UPDATE stock 同一 with 块，异常整体回滚。
- 观察（上游原样，不丢信息）：驳回人写进 `approved_by` 列（`service.py:365`），靠 status 列区分语义；`supplier_id` 无 FK（`store.py:70`），仅应用层校验，无删除 API 故不可达。

### 总体结论（乙原文）

四条真实风险路径（R1–R4）全部集中在 **receive_purchase→receive_stock** 一段，且互为因果链（R1 窗口 → R2 换键双入 → R5 守卫误拒；R3/R4 是键治理缺失的两个独立面）。申请/审批/驳回面的三条核心边界（不提前入库、不覆盖、状态机守卫）全部成立。对 L11 的意义：这些是客户真理的上游事实，探针/Checks 的断言设计应钉住"同键重放安全、换键必拒、rejected/proposed 必拒、库存增量与事件严格对应"这几点；R1/R2 教学上最贴近「失败不残留」。零写入合同达成——写集回查除甲并行正在写的 `tests/` 外无任何改动。

---

## 主 Agent 溯源三分类（整合判断，2026-10-02）

| 分类 | 项 | 处置 |
|---|---|---|
| 已证实（子任务运行复现 + 主 Agent 抽核行号在场） | R1–R5（全部位于 receive_purchase→receive_stock 段 = **L12 业务增量面**，讲义 §1.3 第 2 条「本讲不动」） | **不在本讲修**——业务改动越出 L11 合同（propose 面）写集；逐条落账移交 L12 讲义材料（L12「交付审批后入库」的活教材：两段事务原子性 / 单据维度防重 / 键治理三面）。行号抽核：service.py:397/:399/:400-404/:386/:157/:389-393/:365、store.py:41/:70 均在场 |
| 待验证 | R6（_publish_authority 事务外，机制可读未复现） | 保留待验证标注移交 L12（与 R1 同段）；不升格为已证实 |
| 需求未定 / 范围内未发现 | propose 面三条边界成立（不提前入库 / 不覆盖旧申请 / 拒绝后五表不变）——与本讲合同 C1/C2 直接对应 | 甲产物七用例 + PurchaseChecks 六拍已机器覆盖；「重复编号裸 IntegrityError 非域错误」记录为上游原样事实（合同口径一致——上游钉死不得改写为幂等） |

整合动作：本讲**无需修改业务实现**（发现全部在 L12 范围；propose 面成立）——串行整合 = 甲产物纳入同版复验 + 乙发现落账移交 L12 + 本文件三分类留证。V1 无失败报告（受控实验的注入失败属教学注入非本并行候选缺陷）。
