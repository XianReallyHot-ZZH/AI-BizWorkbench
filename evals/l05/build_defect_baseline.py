"""构造 L05 缺陷基线：带已知坏门面重放的 flowERP clone（讲义 §3.1，R2 裁决形状）。

缺陷（裁决记录二）：``ERPService.receive_stock`` 的重放分支重执行库存更新——
重试再次入账，on_hand 5→10，流水（inventory_events）不重复。客户 blocking eval
``receiving_is_idempotent`` 对此两连红（C3）。上游课堂窄缺陷「同键多写流水」
因 ``inventory_events.event_key`` 主键不可构造（store.py:41，schema 事实入证据账）。

基线固定落在 ``.runtime/course/L05-defect-baseline/``，永不合入，仅作检查的靶子；
脚本自带缺陷生效自检（on_hand 翻倍、流水不重复），自检不过即非零退出。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE = REPO_ROOT / "vendors" / "flowERP"
DEFAULT_BASELINE = REPO_ROOT / ".runtime" / "course" / "L05-defect-baseline"

# 只命中门面重放分支（service.py receive_stock）；锚点不唯一即拒绝动手。
ANCHOR = """            if exists:
                row = conn.execute("""
PATCH = """            if exists:
                conn.execute("UPDATE stock SET on_hand=on_hand+? WHERE sku=?", (quantity, sku))
                row = conn.execute("""


def build(baseline: Path) -> None:
    if baseline.exists():
        raise SystemExit(f"基线已存在，不覆盖：{baseline}（重建请先手动删除）")
    subprocess.run(["git", "clone", "--quiet", "--no-hardlinks", str(SOURCE), str(baseline)], check=True)
    service_file = baseline / "flowerp" / "service.py"
    source = service_file.read_text(encoding="utf-8")
    if source.count(ANCHOR) != 1:
        raise SystemExit(f"锚点命中 {source.count(ANCHOR)} 次（应为 1），上游形状变了，停止构造")
    service_file.write_text(source.replace(ANCHOR, PATCH), encoding="utf-8")


def self_check(baseline: Path) -> dict:
    sys.path.insert(0, str(baseline))
    from flowerp import ERPService, ERPStore  # noqa: E402  故意从基线树导入被测实现

    with tempfile.TemporaryDirectory(prefix="l05-baseline-check-") as tmp:
        service = ERPService(ERPStore(Path(tmp) / "check.db"))
        service.add_product("SKU-A", "验收商品", 1000, 2)
        service.receive_stock("SKU-A", 5, "receipt:001")
        service.receive_stock("SKU-A", 5, "receipt:001")
        events = service.store.rows(
            "SELECT event_key,quantity FROM inventory_events WHERE event_key='receipt:001'")
        report = {"on_hand": service.product("SKU-A")["on_hand"], "events": len(events)}
    if report["on_hand"] != 10 or report["events"] != 1:
        raise SystemExit(f"缺陷未生效，基线不可用：{report}")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE, help="基线clone位置")
    args = parser.parse_args()
    build(args.baseline.resolve())
    report = self_check(args.baseline.resolve())
    print(json.dumps({"baseline": str(args.baseline), "defect_live": True, **report},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
