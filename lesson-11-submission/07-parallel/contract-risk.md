# L11 子任务合同·乙：风险审查（真实并行委派，只读）

- 委派时间：2026-10-02；主 Agent：本会话（Claude）；载体：Claude Code Agent tool（general-purpose 子代理，fresh 上下文——不携带主会话历史，只持本合同）
- 共同输入（固定版本）：`.runtime/course/L11-candidate/`（净态拷贝树，git 起点封存 `53f77a8`；与甲同一份——git 同一起点、文件指纹相同）
- 目标：沿固定实现找出可能违反「申请保存意图、不提前入库、失败不残留」的具体路径，交回可回查的风险判断
- 读集：`flowerp/service.py`（采购面：propose_purchase / purchase / approve_purchase / reject_purchase / receive_purchase）、`flowerp/store.py`（事务与表结构）、`flowerp/models.py`
- 写集：**空**（不创建、不修改任何文件；风险清单经回复交回，由主 Agent 落盘）
- 审查面（上游委托同形）：提前入库（申请路径是否可能增加库存）、覆盖旧申请、异常后部分写入、身份被误当幂等、以及其它真实风险；**发现问题的最短复现优先，不扩大任务、不顺手修复**
- 每项发现必须含：文件:行位置、触发条件、预期 vs 事实依据、待验证标注（不能复现的明确标「待验证」，不写成已发生）
- 停止条件：证据不足则标待核实；需要运行验证时用独立 `tempfile`（不写拷贝树）；不读取甲正在修改的 `tests/test_l11_purchase.py`
- 验收标准：每条发现可回到文件与行号；区分「已证实 / 待验证 / 范围内未发现」；零写入（写集回查 diff 为空）
- 交回：风险清单（经回复）；开始/结束各留一条 `date -u` 时间戳
