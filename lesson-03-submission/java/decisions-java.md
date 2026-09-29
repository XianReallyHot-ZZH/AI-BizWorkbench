# L03-java 决定记录（Java 重走侧增补）

> 承 L03 讲义 §3.C 的决定记录结构（C2 的 Java 口径，移植注记 A2）。**需求八问、V1→反例→确认版
> 的内容裁定是 Python 时代具名验收的历史事实，零重写**——原件见
> [../decisions.md](../decisions.md)（八问 Q1..Q8 与反例审查 F1..F4，2026-09-27 用户确认）、
> [../FDE_SPEC.md](../FDE_SPEC.md)（确认版）、[../FDE_SPEC-v1.md](../FDE_SPEC-v1.md)（歧义保留副本）；
> 时代裁定移植表见讲义附录 A6.1。本文件只记录 Java 重走侧的增量决定，指针化不复制原件。

## 1. Java 时代裁定（2026-09-29）

| # | 决定 | 来源 | 性质 |
|---|---|---|---|
| J1 | 移植注记附录 A 全案审定（重建口径四层 + A6.1 移植裁定表 + JD1–JD6 推荐口径 + golden 方案 11 场景） | 用户"没问题，继续"批量确认（2026-09-29） | 用户决定 |
| J2 | 配合点 1 复认：本讲合同表 + 冻结合同 fixture 即为已签署 Spec，不调用 `/mattpocock-skills:to-spec`（每次重新确认，L02-java 先例） | 用户 AskUserQuestion 选择"确认降级路径"（2026-09-29） | 用户决定 |
| J3 | `CASE-WB-L03-JAVA-001` 的 `--owner`/`--actor` = `XianReallyHot-ZZH` | 用户 AskUserQuestion 确认（2026-09-29，不代填） | 用户决定 |
| J4 | 解析器落位新包 `workbench/spec`（SpecParser + SpecCommands），Main 注册缝 +1 调用，分层 cli → spec，不触 sqlite | 附录 A JD1（经 J1 审定） | 已确认的设计判断 |
| J5 | Args 位置参数扩展（spec_path，argparse 同形；两参构造器行为零变化，40 既有用例回归保证） | 附录 A JD2（经 J1 审定） | 已确认的设计判断 |
| J6 | 模板双载体：`src/main/resources/templates/SPEC_TEMPLATE.md` 逐字拷贝 + 机检测试锁字符串等价 | 附录 A JD3（经 J1 审定） | 已确认的设计判断 |
| J7 | 需求工件零重写 + 护栏断言 + 结构半边重验；本文件即 Java 侧决定记录载体 | 附录 A JD5（经 J1 审定） | 已确认的设计判断 |
| J8 | 已知行为边界：非 UTF-8 解码与文件不存在 errno 词面系语言绑定，如实记录偏差（JSON 形状 + rc 1 保持），不入 golden、不伪造 Python 词面 | 附录 A JD6（经 J1 审定；L01-java parseAware 先例） | 已确认的设计判断 |

## 2. 设计判断（只读调查结论，3.0 同形）

- **调用路径**：`workbench.cli.Main`（注册缝静态块）→ `workbench.spec.SpecCommands.register` →
  `SpecCommands::spec`（适配器）→ `SpecParser.load/parse`（解析半边）——与 Python 侧
  `workbench/cli.py → workbench/spec.py` 同形；适配器不复制解析逻辑（C9 同源）。
- **当前事实 / 拟新增**：Python 冻结面 `workbench/spec.py` 为对照基准（只读）；Java 面拟新增
  `src/main/java/workbench/spec/`（本讲交付）与 `src/main/resources/templates/`（J6）。
- **数据归属**：解析器纯函数无存储；spec 命令不触账本（读命令不建库不建目录，golden 场景无账本）。
- **失败不变状态**：任何输入经 spec 入口前后文件 SHA-256 不变；解析失败不留部分状态（C8）。

## 3. 待确认

（本节无未决项——Python 时代八问已全部有来源或裁定；Java 时代 J1–J8 均已具名确认。）
