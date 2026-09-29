# L03-java 重走证据账（lesson-03-java 前置与候选）

> ADR-0006 重走线第三讲证据。本账只追加；Python 时代证据见 [L03.md](L03.md)，不重开。移植注记与 golden 方案见 [L03-可验收Spec.md 附录 A](../../lessons/L03-可验收Spec.md)（2026-09-29 用户审定，"没问题，继续"批量确认：重建口径四层 + A6.1 全部移植裁定 + JD1–JD6 推荐口径 + golden 方案 11 场景）。

## G. 对照基准（golden）生成 —— 2026-09-29（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 工作台（master，Python 实现、测试、pyproject 零改动）
- 生成工具：`tools/generate_golden_l03.py`（只用标准库；11 场景单会话按序驱动真实 CLI 子进程，与合同测试同形，不绕入口直调）
- 场景面 = `spec` 命令结构闸门（纯解析，**无账本场景**——L03 工作台增量只有解析器与 spec 入口）；errno 类场景（文件不存在 / 非 UTF-8 解码）不入 golden：词面系语言绑定，按移植注记 JD6 如实记录偏差、不伪装同形
- 上游现查（起草时，2026-09-29）：`origin/main` = `7f67533`，与本讲对照基线一致（检查点 0002 后无新变更，冻结合同零变化）→ 不触发检查点采纳

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l03.py`（首跑） | 0 | 11/11 场景按预期退出码通过（t02/t06–t11 预期 rc 1，其余 rc 0），EXIT=0 |
| G2 | 同命令（第 2 跑，确定性比对） | 0 | 11/11 ok，EXIT=0 |
| G3 | `find src/test/resources/golden/l03 -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`（第 2、3 跑各一次） | 0 | 两跑目录指纹同为 `f5c7a611694262f14eaee1c9168ad9538f73e36d83540600402c0cbad82d4a7d` → **golden 字节级确定**（首跑未独立留存指纹，确定性由第 2/3 跑连续两跑各取指纹一致证明，如实记录） |

### 场景清单（11，单命令粒度，逐条见 `manifest.json` 的 scenarios 数组）

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 通过面 | t01–t05 | 冻结确认版六字段原文（t01）/ 冻结错误公式版结构通过（t03，**结构通过 ≠ 业务正确**）/ 围栏内标题不误认（t04，检查点 0002 教学节）/ 冻结采购稿（t05，C6 重验） |
| 拒绝面 | t02, t06–t11 | 冻结缺章版（t02，词面 `Spec 缺少必要章节：非目标`——C11 eval `spec_contract_rejects_ambiguity` 承载）+ 合成夹具五类：空章 / 重复 / 乱序 / 未知标题 / 围栏未闭合 / 空文件缺章全列表 |

输入固定件三源：①冻结三工件（`lesson-03-submission/` 的 FDE_SPEC.md / missing-section.md / wrong-formula.md，生成时读取内容冻结入 manifest——golden 直接封存本讲自己的三份输入）+ 冻结采购稿；②合成夹具七件（生成器常量，镜像讲义 §3.G"手工复制并编辑"）。错误词面经 G 批次抽查七条，全部与冻结解析器逐字同形（t02 与 Python 时代台账 #8 先例逐字一致）。

### 规范化与掩码规则（与 golden/l01、l02 逐字相同，Java 测试侧按 `manifest.json` normalization 块复现）

- 掩码块（`created_at`/`recorded_at` → `<TS>`、`workbench_id` → `<WORKBENCH_ID>`）为三套 golden 统一形状，重放测试断言其在场；spec 输出本身无账本时间戳字段
- canonical JSON：`sort_keys=True, ensure_ascii=False, indent=2` + 末尾换行；非 JSON 逐字保留（防御性）
- golden 共 12 个文件（11 份 stdout + manifest.json）；**生成后永不手改**，要变只能重生成并留证据

### 落位与继承

golden 前置生成，连同生成器与本 §G、附录 A 审定稿随前置提交落 master（L01/L02 先例：`evidence/L02-java.md` §G）；`lesson-03-java` 自 master 分出即继承。

### raw 现场

`.runtime/golden-l03/raw/`（gitignore，不入库）：11 组 stdout/stderr + inputs 固定件。重跑生成器即可复现，不依赖本机留存。

---
