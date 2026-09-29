# L01-java 重走证据账（lesson-01-java 前置与候选）

> ADR-0006 重走线首讲证据。本账只追加；Python 时代证据见 [L01.md](L01.md)，不重开。

## G. 对照基准（golden）生成 —— 2026-09-29（重走前置，implement 授权前完成）

- 生成对象：冻结 Python 工作台（master @ e3d09bf；Python 实现、测试、pyproject 零改动）
- 生成工具：`tools/generate_golden_l01.py`（只用标准库；22 场景单会话按序驱动真实 CLI 子进程，与合同测试同形，不绕入口直调）

### 命令台账

| # | 命令 | 退出码 | 输出摘要 |
|---|---|---|---|
| G1 | `.venv/bin/python -X utf8 tools/generate_golden_l01.py`（首跑） | 先 1 后 0 | **失败现场**：s01 采集后因清场时未重建 `GOLDEN_DIR` 抛 `FileNotFoundError` 中止；修复（补 `mkdir(parents=True)`）后重跑，22/22 场景按预期退出码通过，EXIT=0 |
| G2 | 同命令（第 2 跑，确定性比对） | 0 | 22/22 ok，EXIT=0 |
| G3 | `find src/test/resources/golden/l01 -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`（两跑各一次） | 0 | 两跑目录指纹同为 `a694bf72e9f941a634b4d0917f9bfac17d69b58cce950ea69156aaae719eb8e5` → **golden 字节级确定** |

### 场景清单（22，逐条见 `manifest.json` 的 scenarios 数组）

| 组 | 场景 | 覆盖面 |
|---|---|---|
| 身份三态 | s01–s03 | init 成功 / 同 owner 幂等（F13 同形）/ 跨 owner 拒绝 |
| 项目两态 | s04–s05 | 登记成功 / 重复编号拒绝 |
| 任务三态 | s06–s08 | 带 spec 快照创建 / 缺所属项目拒绝 / 重复编号拒绝 |
| 全量查询 | s09 | 未初始化拒绝（另有 s21）；账本全量回显（掩码后） |
| 链五态 | s10、s12、s15、s17 | 缺链 / 仅红 / 红—Diff—绿完整（`evidence_complete:true` + `acceptance:pending_human_review`）/ 绿后新失败失权（F2 全局最新口径） |
| 记录追加 | s11、s13、s14、s16 | red/diff/green/绿后 red 追加均成功（账本只追加） |
| 错误查询 | s18 | `required_task_missing` 词面 |
| 记录质量拒收 | s19–s20 | 缺时区观察时间拒 / 空输出拒 |
| 未初始化不建库 | s21 | 查询后目录仍空（WB-10，生成器内断言） |
| 篡改复核 | s22 | 白盒改写 record 1 输出后 status 报 `output_digest_mismatch`（F3 先例；每次重算 SHA-256 的信用内核面） |

### 规范化与掩码规则（Java 测试侧须按 `manifest.json` 的 normalization 块复现）

- 掩码字段（不可复现）：`created_at` / `recorded_at` → `<TS>`（墙钟）；`workbench_id` → `<WORKBENCH_ID>`（uuid4）
- `observed_at` **逐字保留**——它是合同字段（链时序语义），生成时用固定字面量
- canonical JSON：`sort_keys=True, ensure_ascii=False, indent=2` + 末尾换行；非 JSON 逐字保留（防御性）
- golden 共 23 个文件（22 份 stdout + manifest.json）；**生成后永不手改**，要变只能重生成并留证据

### 落位说明（对附录 A4 的微修正）

golden 前置生成并随本次提交落 master；`lesson-01-java` 自 master 分出即继承，commit 1 的 Java 测试直接消费。A4 原"golden 随 commit 1 入库"按此口径执行。

### raw 现场

`.runtime/golden-l01/raw/`（gitignore，不入库）：22 组 stdout/stderr + inputs 固定件。重跑生成器即可复现，不依赖本机留存。

---

（后续小节待追加：R1 起始红 → R2 实现 → R3 后绿 → R4 复查 → 具名验收行。到点须用户显式调用 `/mattpocock-skills:implement`。）
