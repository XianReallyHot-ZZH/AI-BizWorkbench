# L05 讲义：先设计失败，再编写 Eval

- 状态：**草案待审**（用户审完才动手；候选表 §5 一次列全）
- 起草：2026-09-28；上游 pin `CodexFDE@7f67533`（本日 fetch 现查未移动，无检查点动作）
- 合同来源：`workbench/course_contracts.py` LESSONS[number=5]（冻结合同，逐字引用不修）
- 上游参考：`vendors/CodexFDE/docs/courses/L05/辅导资料.md`（只读对照）
- 本讲性质：**客户真理重大现查**——讲义形状因此与上游课程有偏离，见 §1.3 与候选表 D1

## 1. 讲解

### 1.1 本讲要什么

L04 完成了"有检查、有人工决定"的库存导出交付。L05 的问题是下一层：<strong>你的检查器自己会错吗？</strong>——检查在正确程序上通过，只说明它没报错；要知道它能不能抓住错误，必须让它检查一个**已知带缺陷的版本**，看它是否真的报红。0004 课留的引子在这里接上。

### 1.2 业务案例：幂等收货

一批货 8 件，操作员页面没响应又点一次——系统会不会记成两批？识别重试靠**幂等键**：第一次用 A，重试仍用 A；真正的新一批才用 B。业务要求（FlowERP 边界 #2，CLAUDE.md 逐字）：<strong>同一个入库幂等键只能生效一次</strong>——既不重复加库存，也不重复留流水。预期独立计算（20+8=28，不是从程序返回值抄），这是 Eval 与"被检查者自己出答案"的分界线。

### 1.3 客户真理现查（讲前 2026-09-28，本讲形状改变的依据）

按 ADR-0005 现查 `vendors/flowERP`（只读）：

1. `flowerp/inventory.py:102` `InventoryService.receive` **已实现幂等**：`event_key` 重放直接返回余额（`idempotent_replay: true`），不二次入账、不二次写 `stock_moves`；
2. 客户自己的 eval 套件里 `receiving_is_idempotent` **已是 blocking 用例**（`eval/harness.py:27`、`eval/cases.py:46`）。

**结论**：冻结合同写的"先构造失败 Eval，再实现幂等"，其"实现"半边在真实客户里已存在。我们不删弱客户检查、不装作从零实现（那违背客户真理与诚实口径）；本讲增量如实转为——<strong>证明检查有辨别力（缺陷基线两连红），再证明真实候选绿（同命令），最后冻结检查防止"改标准凑绿"</strong>。上游辅导资料同样预见此情形："个人课程候选也可能已经能报红，不必为了复现'旧检查绿'而删弱原检查；课堂对照与个人起始状态分别记录。"

### 1.3.1 因果交接（本讲为下一讲准备什么）

L06 用 Harness 把多项检查分级汇总（LESSONS[5] number=6：用 Harness 汇总证据和等级）。本讲留下的"业务 Eval + 工程约束 Eval + 冻结指纹"就是 L06 汇总的对象；eval 用例身份（`receiving_is_idempotent`，blocking）从本讲起固定，本地/远端同一身份是 L08 收口目标。

## 2. 本讲合同（C 编号清单）

blocking / observing 标注；【现查】= 按客户真理调整后的口径。

| C | 验收项（来源） | 类型 | 怎么验 |
|---|---|---|---|
| C1 | 首次入库增加库存（合同 acceptance[0]） | blocking | eval 场景 1：期初 20，A 收 8 → 库存 28，新增 A 流水一条 |
| C2 | 同一幂等键重放不再次增加库存（acceptance[1]） | blocking | eval 场景 2：A 重放 → 仍 28，流水不增；B 新收 → 36（防"拦掉一切"） |
| C3 | 缺陷基线两连红（上游通过标准："缺陷基线连续运行两次均失败"；【现查】红来自基线非真实客户） | blocking | 同一 eval 对已知坏 `receive`（重试多写流水、返回值仍正确）从干净数据连跑两次，均因 C2 的业务要求失败且失败信息定位到具体要求 |
| C4 | 同命令红绿链入账（合同 acceptance[2]"保留修复前红灯和修复后绿灯"→ 本仓库信用内核口径） | blocking | 红与绿同一条 eval 命令，经 `workbench-evidence-add` 落 red/green 相位，`workbench-status` 链判定完整 |
| C5 | 工程约束：检查冻结不被偷偷改动（上游 §4"防止为了通过而改标准"） | blocking | 冻结 eval 文件 sha256；候选收口时复核指纹未变；改动检查须单独说明并保留旧结果 |
| C6 | 换数据迁移检查（上游 §5：11→14→14→17） | observing | 换一组数据重跑 eval 场景，排除"永远返回 28"式写死答案 |
| C7 | 边界留痕（上游 §5"把边界留在交接记录"：并发/进程崩溃/同键不同内容） | observing | 未覆盖边界如实写入证据账，不冒充已覆盖 |

无效数量（0/负数）拒绝场景：`receive` 已有 `require_positive` 校验（inventory.py:105），并入 eval 场景（C2 同包），不单列 C 项。

## 3. 实操流程 + Claude 简报

1. **候选分支 `lesson-05`**：commit 1 = 缺陷基线 + eval 驱动脚本 + **红证据**（C3 两连红）。
   基线位置：`.runtime/course/L05-defect-baseline/`（flowERP clone + 构造坏 `receive`，**永不合入**，仅作检查的靶子）。
2. **eval 接入工作台**：eval 命令经 `workbench-task-run --eval-command` 在候选 cwd 实跑客户 blocking eval（`python -m eval.harness` 或单用例入口）；红绿经 `workbench-evidence-add` 落账（C4）。
3. **冻结与修复验证**：冻结 eval 指纹（C5）→ 真实候选跑同命令 eval → 绿 → 换数据迁移（C6）。
4. **复查轮**：`code-review` 双轴 → 修复 → 全局锚定重采。
5. **证据账 `docs/replication/evidence/L05.md`**：C1–C7 逐项 + 命令台账 + 失败现场 + 具名验收行。
6. **具名验收 → `git merge --no-ff lesson-05`**。

### Claude 简报（第 3 段粘贴用）

> 在 lesson-05 候选分支上：①在 `.runtime/course/L05-defect-baseline/` 构造已知坏 receive（A 重试多写一条 stock_moves、返回值仍正确），写驱动脚本从干净数据两次运行客户 blocking eval `receiving_is_idempotent`，确认两次都因"同一幂等键重放"失败且失败信息指向具体要求（C3）；②冻结 eval 文件 sha256（C5）；③在真实 flowERP 候选以同一条命令运行 eval 确认绿（C1/C2），换数据 11/3/3 复跑（C6）；④证据全部经 workbench-evidence-add 落账，红绿同命令（C4）；⑤未覆盖边界写证据账（C7）。vendors/ 只读；失败记录一律保留。

## 4. 人审清单

- **看哪个 diff**：commit 1 应是基线+驱动+红证据（无产品代码）；候选内产品 diff 应为空或极小（客户已实现幂等——若出现"实现幂等"的大 diff 即红旗：违背客户真理现查）。
- **跑哪些门**：亲手跑 C3 两连红命令；`workbench-status --require-red-green-evidence`；核对 eval 文件当前 sha256 与冻结值一致。
- **什么算作弊**：改弱客户的 blocking eval 凑"新写检查"；把基线缺陷说成真实客户缺陷；用不同命令分别跑红绿；迁移检查用例里写死 28；失败记录消失。

## 5. 候选表（决策点，一次列全，等具名确认）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| D1 | 本讲增量重定位（客户真理现查） | **接受 §1.3 裁决**：增量=Eval 辨别力证明+冻结，不重写客户已有 eval/实现 | 按合同字面从零写 eval+实现（删弱客户检查，违背 ADR-0005，不建议） |
| D2 | 缺陷基线的构造方式 | **独立 clone + 构造坏 receive**（重试多写流水返回正确库存，上游课堂同款缺陷），放 `.runtime/` 永不合入 | 用 git 历史找历史坏版本（依赖不存在的提交）；mock 层注入（测不到真 receive 路径） |
| D3 | workbench 侧增量 | **零新命令**：红绿链（L01）+ task-run eval（L04）已覆盖 C1–C4、C6–C7；C5 冻结用现有 sha256 机制落 observation 相位记录 | 新增 workbench-eval-freeze 命令（机制化但本讲场景收益薄）；起步 eval.harness 待建设项（L06 正题，提前做会与上游 L06 撞车） |
| D4 | eval 用例身份 | **沿用客户用例 `receiving_is_idempotent`（blocking）**，本地命令身份固定写入证据账（L08"同一 Eval 身份"的起点） | 自写平行 eval（两套身份，L08 必撞） |
| D5 | 候选分支机制 | 照旧：`lesson-05`，commit 1 = 基线+红证据 | 直接 master（违背 ADR-0004） |

定稿动作（候选表确认后）：按裁决修订本文 → `lesson-05` 动工（commit 1 = 缺陷基线 + 红证据）。
