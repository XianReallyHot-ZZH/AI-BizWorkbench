# Workbench 复刻学习资源

## Knowledge

以下按信任层级排列：一手课程合同 > 参考实现源码 > 本仓库真实产物 > 标准库文档。

- [上游 L01 辅导资料](../../vendors/CodexFDE/docs/courses/L01/辅导资料.md)
  本讲"为什么"的唯一权威：案例、对象模型（§5）、验收行为（§6）、自举（§7）。用于：任何"为什么要这个设计"的终审依据。
- [上游 L01 实践操作手册](../../vendors/CodexFDE/docs/courses/L01/实践操作手册.md)
  逐命令操作、预期输出、失败处理与中断恢复。用于：核对命令行为与错误词面。
- [上游 WORKBENCH_SPEC.md](../../vendors/CodexFDE/docs/courses/L01/WORKBENCH_SPEC.md)
  五命令冻结接口 + WB-01..WB-12 验收用例。用于：判断实现是否偏离合同（§5 输入输出表、§6 完整性语义）。
- [上游参考实现 bootstrap.py](../../vendors/CodexFDE/workbench/bootstrap.py)
  参考终态（含后续讲次增强）。用于：语义分歧时对照——注意区分"L01 时代语义"与终态增强。
- [本仓库 workbench/bootstrap.py](../../workbench/bootstrap.py)
  学习对象本体。模块 docstring 即结构图（四职责），`_chain_error` 是链判定心脏。
- [本仓库合同测试](../../tests/test_l01_workbench_bootstrap.py)
  行为规则的速读材料：每个测试名 = 一条规则，文件头有 WB→测试映射表。
- [L01 证据账](../../docs/replication/evidence/L01.md)
  真实证据链样本：命令台账 29 条含失败现场。用于：把抽象规则对到真实数据。
- [L01 讲义](../../docs/lessons/L01-工作台自举.md)（复刻口径）与
  [ADR-0001..0005](../../docs/adr/)
  复刻决策（全新实现 / Claude 执行器 / 候选分支 / 客户真理）的依据。用于：理解"为什么和上游不一样"。
- [上游 L03 辅导资料](../../vendors/CodexFDE/docs/courses/L03/辅导资料.md)
  Spec 讲"为什么"的权威：§5 三份输入定边界；「从一段文本看懂解析器怎样工作」节（检查点 0002 新增）是围栏语义与字段映射的终审依据。
- [本仓库 workbench/spec.py](../../workbench/spec.py)
  L03 学习对象本体：`_contract_headings()` 围栏屏蔽、`parse_spec()` 六类拒绝与检查顺序（未知→重复→缺章→乱序→空章）、`load_spec()` 只读。
- [本仓库合同测试 test_l03_spec_parser.py](../../tests/test_l03_spec_parser.py)
  16 用例 = 16 条行为规则，文件头验收项映射表；`test_c9_cli_missing_section_error_same_source` 钉"CLI 与解析器同源"。
- [L03 证据账](../../docs/replication/evidence/L03.md)
  真实复验样本：三份输入退出码、夹具修正现场、验收追认两条（handler 层接 ValueError、包络含 flowerp_connected）。
- [L03 讲义](../../docs/lessons/L03-可验收Spec.md)（复刻口径）
  C1..C13 合同表与六个决策点（D1 eval 承载 / D4 根 FDE_SPEC 不建 / D5 只复刻解析半边）——闸门语义的本仓库口径。
- [Python sqlite3 文档](https://docs.python.org/3/library/sqlite3.html)
  账本存储层。用于：事务、行工厂、schema 语义。
- [Python datetime.fromisoformat 文档](https://docs.python.org/3/library/datetime.html#datetime.date.fromisoformat)
  时区解析的坑（3.10 不认 `Z`）。用于：observed_at 处理。

## Wisdom (Communities)

- 本课程暂无已知外部社群。上游仓库 [congde/CodexFDE](https://github.com/congde/CodexFDE) 的
  issues 是唯一可公开提问的场所；使用前先查是否已有同类 issue。

## Gaps

- 缺少"AI 工程交付 / HITL 验收"主题的高信度外部社区——若后续需要同行评议，
  优先搜索课程官方渠道，其次考虑向上游仓库提 issue。
- 缺少 Spec/Eval/Harness 模式的英文一手资料对照（上游为中文课程）——L05+ 遇到
  Eval 设计时再补。
