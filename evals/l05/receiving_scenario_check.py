"""L05 收货场景驱动：对 --target 树独立复算预期，读库存与流水（上游 §2 场景表）。

预期一律由 --opening/--receipt/--new 算术独立计算（上游：预期的 28 来自 20＋8，
不能抄程序返回值）；除 on_hand 外同时逐条读 inventory_events——客户 blocking eval
不读流水，本驱动是 observing 补充（C6 迁移 / C7 盲区对照），blocking 身份仍是
客户用例 receiving_is_idempotent（D4，不另立身份）。

默认数据 20/8/8 复现 C1/C2（20→28→28→36，流水 1→2→2→3，0/负数拒绝且状态不变）；
--opening 11 --receipt 3 --new 3 为 C6 迁移（11→14→14→17）。每次运行自建临时库，
天然从干净数据开始。
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

REQUIREMENT = "同一笔收货只入账一次：库存与流水都不得重复"


def fail(step: str, expected: object, actual: object) -> int:
    print(json.dumps({"step": step, "status": "fail", "expected": expected,
                      "actual": actual, "requirement": REQUIREMENT}, ensure_ascii=False))
    return 1


def snapshot(service: object, sku: str) -> tuple:
    events = service.store.rows(
        "SELECT event_key,sku,quantity,reserved_delta,event_type,reference "
        "FROM inventory_events WHERE sku=? ORDER BY rowid", (sku,))
    stock = service.store.rows(
        "SELECT on_hand,reserved FROM stock WHERE sku=?", (sku,))
    return (tuple(tuple(sorted(row.items())) for row in events),
            tuple(tuple(sorted(row.items())) for row in stock))


def run(opening: int, receipt: int, new: int) -> int:
    from flowerp import ERPService, ERPStore

    sku = "SKU-A"
    with tempfile.TemporaryDirectory(prefix="l05-scenario-") as tmp:
        service = ERPService(ERPStore(Path(tmp) / "scenario.db"))
        service.add_product(sku, "验收商品", 1000, 2)

        service.receive_stock(sku, opening, "opening")
        if service.product(sku)["on_hand"] != opening:
            return fail("opening", {"on_hand": opening}, {"on_hand": service.product(sku)["on_hand"]})
        events = service.store.rows(
            "SELECT event_key,quantity,event_type FROM inventory_events WHERE event_key='opening'")
        if len(events) != 1 or events[0]["quantity"] != opening or events[0]["event_type"] != "receive":
            return fail("opening-ledger", 1, len(events))

        first = service.receive_stock(sku, receipt, "receipt:A")
        expected_first = opening + receipt
        if first["on_hand"] != expected_first or first["idempotent_replay"]:
            return fail("first", {"on_hand": expected_first, "replay": False},
                        {"on_hand": first["on_hand"], "replay": first["idempotent_replay"]})
        if len(service.store.rows(
                "SELECT event_key FROM inventory_events WHERE event_key='receipt:A'")) != 1:
            return fail("first-ledger", 1, "receipt:A 流水数≠1")

        before = snapshot(service, sku)
        second = service.receive_stock(sku, receipt, "receipt:A")
        after = snapshot(service, sku)
        if second["on_hand"] != expected_first:
            return fail("replay", {"on_hand": expected_first}, {"on_hand": second["on_hand"]})
        if not second["idempotent_replay"]:
            return fail("replay-flag", True, second["idempotent_replay"])
        if before != after:
            return fail("replay-ledger", "库存与流水均不变", "重放改变了账面")

        third = service.receive_stock(sku, new, "receipt:B")
        expected_new = opening + receipt + new
        if third["on_hand"] != expected_new:
            return fail("new", {"on_hand": expected_new}, {"on_hand": third["on_hand"]})
        total = service.store.rows("SELECT event_key FROM inventory_events WHERE sku=?", (sku,))
        if len(total) != 3:
            return fail("new-ledger", 3, len(total))

        for invalid in (0, -1):
            state = snapshot(service, sku)
            try:
                service.receive_stock(sku, invalid, f"invalid:{invalid}")
            except ValueError:
                pass
            else:
                return fail("invalid", f"数量 {invalid} 被拒绝", "被接受")
            if snapshot(service, sku) != state:
                return fail("invalid-state", "拒绝后状态不变", "拒绝后改变了账面")

    print(json.dumps({"case": "receiving_scenario", "status": "pass",
                      "opening": opening, "receipt": receipt, "new": new,
                      "on_hand_path": [opening, expected_first, expected_first, expected_new],
                      "ledger_path": [1, 2, 2, 3], "invalid_quantities_rejected": [0, -1]},
                     ensure_ascii=False))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, help="被测 flowERP 树（clone 根目录）")
    parser.add_argument("--opening", type=int, default=20)
    parser.add_argument("--receipt", type=int, default=8)
    parser.add_argument("--new", type=int, default=8)
    args = parser.parse_args()
    target = Path(args.target).resolve()
    if not (target / "flowerp").is_dir():
        parser.error(f"--target 下没有 flowerp/：{target}")
    sys.path.insert(0, str(target))
    return run(args.opening, args.receipt, args.new)


if __name__ == "__main__":
    raise SystemExit(main())
