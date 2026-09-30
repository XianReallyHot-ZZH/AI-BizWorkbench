# hook_staging/——L07 待审 Hook 投影

本目录是 L07「提交前本地护栏」的**待审产物**（合同 acceptance[3]）：配置片段与检查目标
pin 在这里接受人工审查，**审查通过并具名授权后才安装**到 `.claude/settings.json`
（acceptance[4]）。本目录不被 Claude Code 自动加载——放这里 ≠ 已启用。

## 三件与指纹（commit 2 时点初冻；收口复核以证据账 L07 为准）

| 文件 | 作用 | sha256 |
|---|---|---|
| `settings.hooks.json` | 待审安装片段：Stop → `./bin/wb quality-gate`，外层 timeout 120s | `3f343208be2d9155655b0b87d3b82f12e31462b30af5fd8d35e84688b661e0d8` |

处理器与统一入口源件指纹（待审对象 = 配置片段 + 以下实现）：

| 源件 | sha256 |
|---|---|
| `src/main/java/workbench/evals/l07/QualityGate.java`（处理器：stdin 事件 → 校验 → 重入短路 → 子进程调统一入口 → Stop 协议翻译 → 事件留痕；失败明细点名失败登记项，非报告面回退尾十行——D5 回退语义保留，改进注记见证据账） | `42047d0f91c1c7b9d408a4f7fe4549ebc10d1b430cb5a3974936339e7b4f2eab` |
| `src/main/java/workbench/evals/l07/OrderChecks.java`（统一入口：六登记项收口） | `5c1b72d32d4b2603f05c816b372de5345961a7d42fa6845437a7df6286300671` |
| `src/main/java/workbench/evals/l07/OrderProbe.java`（订单面探针，断言留 Java；拒绝比三表、草稿不预占只比库存表——上游同形，首采绿腿抓获的三表误比缺陷已修，失败现场保留见证据账） | `4c99703cee02e0a04e83013fe3d55c2241dda3ee973d78dcd2564baf2103edff` |
| `src/main/java/workbench/cli/Main.java`（REGISTRY 注册缝） | `d12bdc260702a8974b9dfcb6446c0a48a915baaa2cd96c12ff23e4b81675b08b` |
| `src/main/java/workbench/evals/l06/StockConsistencyCheck.java`（L07 开放复用缝两词可见性，行为零变化） | `193ff6e1292218471db05ba41eb99ef0b4c4aab15eba9f4c524b796f91c84827` |

`target.json`：quality-gate 缺省统一入口的被检客户树 pin（`.runtime/course/L07-candidate`，
相对仓库根解析）。它指向 `.runtime/`（gitignored）运行树，本身无入库指纹——核对以建树
记录与证据账为准。

## 安装流程（人审后，逐项合并不覆盖）

1. 审查者核对上表指纹与实际文件（`shasum -a 256 <file>`）一致；
2. 具名授权后执行合并：把 `settings.hooks.json` 的 `hooks` 段逐项并入 `.claude/settings.json`
   （该文件此前不存在，首次安装即新建；若已存在其他配置，保留原键只追加 Stop 段）；
3. 安装前后 `.claude/settings.json` 内容留证（前后指纹 + diff），随收口提交入库；
4. 安装后本仓库任何 Claude Code 会话（含交互）每次准备结束都会过闸——这是护栏生效的
   预期行为，不是故障。

## 回滚

`.claude/settings.json` 随收口提交入库 → 回滚 = `git revert` 该文件（或删除 Stop 段后
提交）。`hook_staging/` 投影不删（审查记录留痕）。

## 重指 target（验收后如需）

改 `target.json` 的 `target` 值（如重指 `vendors/flowERP` 客户真理面只读照跑）是一次
**配置变更**：小步提交、指纹/建树记录同步证据账、必要时重新真实事件验证一次。

## 处理器行为摘要（详参 QualityGate javadoc 与讲义 §1.4）

- 通过 → `{"systemMessage":"当前阻断级检查已通过；不代表 FlowERP 业务验收。"}`（放行停止）；
- 阻断级检查失败 → `{"decision":"block","reason":"阻断级检查未通过。修复后显式复验：\n"+失败明细}`（Claude 续跑，reason 即继续理由；失败明细 = 报告可解析时点名失败登记项 + summary，否则输出尾十行——D5 回退语义保留）；
- 处理器自身任何故障（事件不可解析/非 Stop/cwd 越界/统一入口启动失败/超时）→ block「未完成验证」（失败不能显示成成功）；
- 重入（`stop_hook_active:true`）→ 跳过响应，不再次调统一入口——**跳过 ≠ 复验通过**，结束前仍须显式运行；
- 处理器恒 rc 0 交付协议（协议交付成功 ≠ 业务通过）；stdout 只出协议 JSON，诊断走 stderr；
- 事件留痕：`.runtime/hooks/<UTC 毫秒>-<session>/` 独占目录，event.json / response.json / harness 输出两件。

## 参数面（正式安装命令不带；六类协议合同测试用）

`--harness-command <shell 形命令串>`（替身注入缝）、`--harness-timeout-ms`（缺省 100000，
外层 120s 留 20s 余量）、`--events-dir`（缺省 `.runtime/hooks`）。

## 边界注记（C8，如实标注）

- 手工喂事件 JSON 只算协议测试，**不算真实宿主事件**（上游同款边界；真实事件链见证据账）；
- 外层 timeout 到期即取消 hook 并放行停止（宿主行为）——外层超时 ≠ 验证通过；
- `claude -p --bare` 会整体跳过 hooks（实证）；`if` 字段在 Stop 上写了 hook 永不运行（官方
  文档）——两者都是绕闸通道，依赖护栏时须知；
- 连续 block 8 次后宿主强制结束回合（`CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` 可调）——门永远
  过不去时会话不会被锁死，但也意味着第 9 次起护栏放行；
- gate 经 `bin/wb` 启动，`target/child-classpath.txt` 缺失时 bin/wb 以 rc 2 退出（stderr
  提示先构建）——该退出码在 Stop 上不构成阻断（宿主按非阻断错误处理），环境恢复后下次
  停止恢复拦阻。
