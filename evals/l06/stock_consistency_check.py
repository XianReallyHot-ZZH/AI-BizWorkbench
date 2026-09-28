"""L06 库存口径交叉检查驱动：查询与 CSV 都必须遵守 可用 = 在手 − 预占。

预期由 --opening/--reserved 算术独立计算（上游 §1：8−3=5 来自业务规则，
不能拿查询返回值当导出预期）；同时覆盖超额预占拒绝且状态不变（FlowERP 边界 #1，
合同绑定 eval `stock_never_negative` 的驱动面）。每次运行自建临时库，天然从干净
数据开始。blocking 身份属登记项 `l06_stock_consistency`（讲义 §2）。

上游教学缺陷形状（对照）：flowerp/service.py export_inventory 的 CSV available
字段误写 on_hand——缺陷树下查询 [8,3,5]、CSV [8,3,8]，本驱动 AC-AVAILABLE 定位输出。
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import tempfile
from pathlib import Path

REQUIREMENT = "可用库存按在手减预占计算：查询与 CSV 同口径，且永不为负"


def fail(step: str, expected: object, actual: object) -> int:
    print(json.dumps({"step": step, "status": "fail", "expected": expected,
                      "actual": actual, "requirement": REQUIREMENT}, ensure_ascii=False))
    return 1


def snapshot(store: object) -> dict:
    return {
        "stock": store.rows("SELECT * FROM stock ORDER BY sku"),
        "events": store.rows("SELECT * FROM inventory_events ORDER BY rowid"),
        "orders": store.rows("SELECT * FROM sales_orders ORDER BY id"),
        "lines": store.rows("SELECT * FROM sales_order_lines ORDER BY rowid"),
    }


def run(opening: int, reserved: int) -> int:
    from flowerp import ERPService, ERPStore
    from flowerp.models import InsufficientStock, OrderLine

    if not 0 < reserved < opening:
        print(json.dumps({"step": "input", "status": "fail",
                          "expected": "0 < reserved < opening",
                          "actual": {"opening": opening, "reserved": reserved},
                          "requirement": REQUIREMENT}, ensure_ascii=False))
        return 1

    sku = "L06-A"
    expected = opening - reserved
    wanted = [opening, reserved, expected]
    with tempfile.TemporaryDirectory(prefix="l06-stock-") as tmp:
        store = ERPStore(Path(tmp) / "stock.db")
        service = ERPService(store)
        service.add_product(sku, "口径检查", 100)
        service.receive_stock(sku, opening, "opening")
        order = service.create_order("已有订单", [OrderLine(sku, reserved, 100)], "order-A")
        service.reserve_order(order["id"])

        query = service.product(sku)
        rows = list(csv.DictReader(io.StringIO(service.export_inventory().lstrip("﻿"))))
        exported = [row for row in rows if row["sku"] == sku]
        if len(exported) != 1:
            return fail("AC-CSV-ROW", 1, len(exported))
        observed = {
            "query": [query[k] for k in ("on_hand", "reserved", "available")],
            "csv": [int(exported[0][k]) for k in ("on_hand", "reserved", "available")],
        }
        if any(value != wanted for value in observed.values()):
            return fail("AC-AVAILABLE", {"wanted": wanted}, observed)

        before = snapshot(store)
        excessive = service.create_order("超额订单", [OrderLine(sku, expected + 1, 100)], "order-B")
        try:
            service.reserve_order(excessive["id"])
        except InsufficientStock:
            pass
        else:
            return fail("AC-REJECT", f"预占 {expected + 1} 被拒绝", "被接受")
        after = snapshot(store)
        if before != after:
            return fail("AC-UNCHANGED", "拒绝后库存/流水/订单/明细不变", "拒绝后改变了账面")

    print(json.dumps({"case": "stock_consistency", "status": "pass",
                      "opening": opening, "reserved": reserved,
                      "available": expected, "query": wanted, "csv": wanted,
                      "rejected_reserve": expected + 1, "state_unchanged": True},
                     ensure_ascii=False))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, help="被测 flowERP 树（clone 根目录）")
    parser.add_argument("--opening", type=int, default=8)
    parser.add_argument("--reserved", type=int, default=3)
    args = parser.parse_args()
    target = Path(args.target).resolve()
    if not (target / "flowerp").is_dir():
        parser.error(f"--target 下没有 flowerp/：{target}")
    sys.path.insert(0, str(target))
    return run(args.opening, args.reserved)


if __name__ == "__main__":
    raise SystemExit(main())
