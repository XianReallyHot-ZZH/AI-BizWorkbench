# 受控交付任务：SKU 库存导出（能力信封五问）

你在候选工作目录内受合同约束执行本次交付。逐条回答执行器的五个前置问题：

## 1. 按哪份 Spec

`/Users/xianreallyhotzzh/Development/Code/github/AI-BizWorkbench/lesson-03-submission/FDE_SPEC.md`
（SHA-256 `f3e816690867c1cfd329f7535bdfad64ad51fbf1077765272106a1450869dbe2`——B 执行与验收依据同一份合同；八列、available = on_hand - reserved、不合并行、稳定排序、空库存出表头、写失败不毁旧文件等约束以该文件为准。）

## 2. 你在哪

候选工作目录：`/Users/xianreallyhotzzh/Development/Code/github/AI-BizWorkbench/.runtime/course/L04-delivery-java/candidate`（flowERP 本地 clone，起点版本 `e0088d3`）。你的进程 cwd 已绑定为该目录。

## 3. 允许改哪些（写集）

只允许写候选内 `delivery/` 目录。要求产出：

- `delivery/build_practice.py`——用 flowERP **自身服务层**（import flowerp，经其 store/service API）种子一个练习账 `delivery/practice.db`（含多产品、多仓、多批次，覆盖同一 SKU 分布多仓位批次与一个 reserved > on_hand 的记录，照 Spec A2/A3/A8 口径）；
- `delivery/practice.db`——上述脚本产出；
- `delivery/inventory.csv`——**绑定既有入口** `flowerp.inventory_export:export_inventory_file`（或其 CLI `python -m flowerp.inventory_export --database … --output …`）从 practice.db 导出。该能力已存在：只复验交付，不重写导出逻辑；
- `delivery/verify_inventory.py`——自检脚本：以 `utf-8-sig` 读回 inventory.csv，断言八列列序、available = on_hand - reserved、按 sku,site,location,lot_id 升序、行不合并，打印 JSON 结果并给出退出码。

执行事件流与日志落 `.runtime/course/L04-delivery-java/`（候选外，由工作台管理，你无需写入）。

## 4. 何时停

- 超时（工作台进程侧 900 秒预算）、写集越界风险、无法继续推进——立即停止推进，保留现场与已完成产物，不伪造完成。
- 禁止：修改 flowERP 业务源码（只读）、写 `.git/`、写候选外路径、联网、重写导出算法。

## 5. 怎样判断（自检）

- 导出后以 `delivery/verify_inventory.py` 自检；
- 交付正确性由独立复验器（vendor probe `check_inventory_delivery.py` 六场景 + eval 对照 ERP 权威账 SQL）事后判定，不由你的自述决定——调用正常结束 ≠ 业务正确。
