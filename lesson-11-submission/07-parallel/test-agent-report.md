# L11 真实并行·甲交回产物（测试补全子代理原始回复，主 Agent 落盘）

- 委派载体：Claude Code Agent tool（general-purpose fresh 子代理）；主 Agent 发起时刻 2026-10-02T03:04:16Z
- **活动时间**：开始 `2026-10-02T03:04:09Z` → 结束 `2026-10-02T03:07:20Z`（子代理自跑 `date -u` 留戳）
- 实跑命令（合同原文）：`cd .runtime/course/L11-candidate && .venv/bin/python -X utf8 -m unittest tests.test_l11_purchase -v`
- 退出码 **0**；`Ran 7 tests in 1.357s` / `OK`——首跑即绿，无修复迭代
- 产物：`tests/test_l11_purchase.py`（6194 字节，写集唯一文件；全文见拷贝树与下方原文）
- 七用例：normal 双拍（五字段规范化 + 五表/三量 10/2/8/PR-OTHER 保护）+ 五拒绝拍（ValueError×3 / NotFound / IntegrityError——重复编号用「先成功再重复」序列证明非幂等成功，五表逐字不变）
- 实现形态：sys.path 指向拷贝树根加载真实 flowerp；tempfile 独立临时库；夹具与五表快照同形 integration_lab.py:29-38（PR-OTHER SQL 直插）；库存不变断言与上游注入点（teaching-premature 行）同位覆盖
- 未覆盖项（甲自报）：approve/reject/receive 状态机、supplier_id 校验、list_purchases 排序、并发写入（合同四条未涉及，未扩围）；拒绝路径未断言异常消息文本（合同未要求）
- 运行副作用（甲如实自报）：命令无 `-B`，实跑产生 `tests/__pycache__/` 字节码缓存（及 `__init__` 缓存）；`flowerp/__pycache__` 此前已存在（主会话净树九件实跑遗留）。均为运行缓存非源件修改，`flowerp/` 源文件零触碰
- 未发现疑似业务缺陷；无停止条件触发

## 子代理回复原文

任务完成，一次实跑全绿，未改任何业务代码。

实跑输出摘要：

```
test_normal_request_keeps_stock_events_orders_and_pr_other_untouched ... ok
test_normal_request_saved_as_proposed_with_normalized_fields ... ok
test_reject_blank_reason ... ok
test_reject_duplicate_request_id_is_not_idempotent_success ... ok
test_reject_negative_quantity ... ok
test_reject_unknown_sku ... ok
test_reject_zero_quantity ... ok
----------------------------------------------------------------------
Ran 7 tests in 1.357s
OK
```

测试文件全文（拷贝树 `tests/test_l11_purchase.py`，逐字）：

```python
"""L11 采购申请业务预期的可重复 unittest 断言（并行子任务合同·甲）。

共同依据（不得自行改写）：
1. PR-TARGET（A×7，理由「低于补货点」带首尾空白、SKU 小写 ``a`` 输入）保存为 proposed：
   id=PR-TARGET、sku=A（规范化）、quantity=7、reason 去空白、approved_by 为空，可按编号再查。
2. 申请后库存三量 (on_hand, reserved, available)=(10, 2, 8)（走 ``service.product()``）；
   库存流水、OTHER 订单、PR-OTHER 不变；purchase_requests 恰 +1。
3. 五拒绝：零数量/负数量（ValueError）、未知 SKU（NotFound）、
   重复编号（sqlite3.IntegrityError——不是幂等成功）；拒绝后五表不变。
4. 夹具：A 在库 10（``receive_stock("A",10,...)``）+ OTHER 订单预占 2；
   PR-OTHER 为固定夹具（SQL 直插，不经 propose——integration_lab 同形）。

运行约定：本模块加载拷贝树真实服务（sys.path 指向拷贝树根）；
运行数据一律 ``tempfile.TemporaryDirectory`` 独立临时库，不落拷贝树其它文件。
"""
from __future__ import annotations

import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

_COPY_TREE_ROOT = Path(__file__).resolve().parents[1]
if str(_COPY_TREE_ROOT) not in sys.path:
    sys.path.insert(0, str(_COPY_TREE_ROOT))

from flowerp import ERPService, ERPStore  # noqa: E402
from flowerp.models import NotFound, OrderLine  # noqa: E402

TABLES = ("purchase_requests", "stock", "inventory_events", "sales_orders", "sales_order_lines")


class PurchaseProposalExpectationsTest(unittest.TestCase):
    """采购申请（propose_purchase）合同业务预期。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="l11-purchase-tests-")
        self.addCleanup(self._tmp.cleanup)
        self.store = ERPStore(Path(self._tmp.name) / "eval.db")
        self.service = ERPService(self.store)
        # 夹具（integration_lab 同形）：A 在库 10 + OTHER 订单预占 2。
        self.service.add_product("A", "商品 A", 100)
        self.service.receive_stock("A", 10, "opening")
        self.service.create_order("其他客户", [OrderLine("A", 2, 100)], "OTHER")
        self.service.reserve_order("OTHER")
        # PR-OTHER 固定夹具：SQL 直插，不经 propose（同形 integration_lab.py:34）。
        with self.store.connect() as conn:
            conn.execute(
                "INSERT INTO purchase_requests(id,sku,quantity,status,reason) VALUES(?,?,?,?,?)",
                ("PR-OTHER", "A", 3, "proposed", "另一项需求"),
            )

    def snapshot(self) -> dict:
        """五表全量快照（同形 integration_lab.py:38）。"""
        return {t: self.store.rows(f"SELECT * FROM {t} ORDER BY 1") for t in TABLES}

    def propose_target(self) -> dict:
        """受测动作：PR-TARGET，A×7，理由带首尾空白，SKU 小写 a 输入。"""
        return self.service.propose_purchase("a", 7, "  低于补货点  ", "PR-TARGET")

    def assert_rejection_preserves_state(self, action, expected_exception) -> None:
        """断言动作抛 expected_exception 且五表不变。"""
        before = self.snapshot()
        with self.assertRaises(expected_exception):
            action()
        self.assertEqual(self.snapshot(), before, "rejection changed persisted state")

    # 业务预期 1：正常申请保存为 proposed，字段规范化，可按编号再查。

    def test_normal_request_saved_as_proposed_with_normalized_fields(self) -> None:
        result = self.propose_target()
        self.assertEqual(
            (result["id"], result["sku"], result["quantity"], result["reason"],
             result["status"], result["approved_by"]),
            ("PR-TARGET", "A", 7, "低于补货点", "proposed", None),
        )
        self.assertEqual(self.service.purchase("PR-TARGET"), result)

    # 业务预期 2：申请后三量不变、旁表不变、PR-OTHER 不变、purchase_requests 恰 +1。

    def test_normal_request_keeps_stock_events_orders_and_pr_other_untouched(self) -> None:
        before = self.snapshot()
        self.propose_target()
        after = self.snapshot()
        product = self.service.product("A")
        self.assertEqual((product["on_hand"], product["reserved"], product["available"]), (10, 2, 8))
        self.assertEqual(after["stock"], before["stock"])
        self.assertEqual(after["inventory_events"], before["inventory_events"])
        self.assertEqual(after["sales_orders"], before["sales_orders"])
        self.assertEqual(after["sales_order_lines"], before["sales_order_lines"])
        self.assertEqual(
            [p for p in after["purchase_requests"] if p["id"] != "PR-TARGET"],
            before["purchase_requests"],
        )
        self.assertEqual(len(after["purchase_requests"]), len(before["purchase_requests"]) + 1)

    # 业务预期 3：五拒绝各类型，拒绝后五表不变。

    def test_reject_zero_quantity(self) -> None:
        self.assert_rejection_preserves_state(
            lambda: self.service.propose_purchase("a", 0, "  低于补货点  ", "PR-TARGET"),
            ValueError,
        )

    def test_reject_negative_quantity(self) -> None:
        self.assert_rejection_preserves_state(
            lambda: self.service.propose_purchase("a", -1, "  低于补货点  ", "PR-TARGET"),
            ValueError,
        )

    def test_reject_blank_reason(self) -> None:
        self.assert_rejection_preserves_state(
            lambda: self.service.propose_purchase("a", 7, "   ", "PR-TARGET"),
            ValueError,
        )

    def test_reject_unknown_sku(self) -> None:
        self.assert_rejection_preserves_state(
            lambda: self.service.propose_purchase("UNKNOWN", 7, "  低于补货点  ", "PR-TARGET"),
            NotFound,
        )

    def test_reject_duplicate_request_id_is_not_idempotent_success(self) -> None:
        first = self.propose_target()
        self.assertEqual(first["id"], "PR-TARGET")
        self.assert_rejection_preserves_state(self.propose_target, sqlite3.IntegrityError)


if __name__ == "__main__":
    unittest.main()
```

## 未覆盖项 / 说明清单（甲自报原文）

1. 合同范围外未覆盖：`approve_purchase`/`reject_purchase`/`receive_purchase` 后续状态机、`supplier_id` 校验路径、`list_purchases` 排序、并发写入——合同业务预期四条未涉及，未擅自扩围。
2. 拒绝路径只断言「异常类型 + 五表不变」，未逐条断言异常消息文本（合同未要求）。
3. 运行副作用如实报：合同给定命令无 `-B`/`PYTHONDONTWRITEBYTECODE`，实跑在拷贝树产生了 `tests/__pycache__/test_l11_purchase.cpython-313.pyc`（及 `__init__` 缓存）字节码缓存；`flowerp/__pycache__` 此前已存在（主会话净树九件实跑遗留）。均为 Python 运行缓存，非源件修改；未触碰 `flowerp/` 任何源文件，拷贝树其余文件零改动。
4. 未发现疑似业务缺陷：7 项预期一次全过，无需修改实现，无停止条件触发。
