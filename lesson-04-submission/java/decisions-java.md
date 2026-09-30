# decisions-java（L04 Java 侧决定记录，指针化不复制）

- 讲义级裁定：**全部承袭 [L04-受控执行.md 附录 A](../../docs/lessons/L04-受控执行.md) A6**（2026-09-29 用户"没问题，继续"批量确认：A6.1 移植裁定 + JD1–JD8 + golden 方案八组 47 场景），不在此复制。
- 配合点 1 复认（2026-09-29）：用户显式选择 to-spec 降级路径（每次重新确认，L02/L03-java 先例）。
- 执行期待定项：A 票 `--owner`/`--actor` 与 B 票执行器会话的模型/权限参数**执行前向用户索取，不代填**（L01–L03 先例）。
- 前置生成期失败现场与根因（s47 SQL 列名、`_now()` 秒级精度指纹漂移、任务编号按创建序字典序命名）：见 [evidence/L04-java.md §G](../../docs/replication/evidence/L04-java.md)。
- 红绿间测试修正两处（期望对齐冻结实测，非放宽）：见 [A-evidence-java.md](A-evidence-java.md)。
