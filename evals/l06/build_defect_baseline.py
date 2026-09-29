"""构造 L06 缺陷基线：CSV available 字段误写 on_hand 的 flowERP clone（讲义 §3.3）。

缺陷（上游 L06 教学缺陷原样）：``ERPService.export_inventory`` 的 CSV 模板把
``{row['available']}`` 换成 ``{row['on_hand']}``——查询 [8,3,5]、导出 [8,3,8]，
reserved>0 时两出口口径分裂。客户 ``inventory_export_is_stable`` 用 reserved=0
数据（,3,0,3 / ,2,0,2），对本缺陷不可见（盲区探针另有 driver 采证）。

基线固定落在 ``.runtime/course/L06-defect-baseline/``，永不合入，仅作检查的靶子；
锚点不唯一即拒绝动手（上游形状漂移时构造失败而非错改）。脚本自带缺陷生效自检
（查询 available=5、CSV available=8），自检不过即非零退出。
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE = REPO_ROOT / "vendors" / "flowERP"
DEFAULT_BASELINE = REPO_ROOT / ".runtime" / "course" / "L06-defect-baseline"

# 只命中 export_inventory 的 CSV 模板 available 字段（上游 prepare 同锚点）。
ANCHOR = "{row['available']}"


def build(baseline: Path) -> None:
    if baseline.exists():
        raise SystemExit(f"基线已存在，不覆盖：{baseline}（重建请先手动删除）")
    subprocess.run(["git", "clone", "--quiet", "--no-hardlinks", str(SOURCE), str(baseline)], check=True)
    service_file = baseline / "flowerp" / "service.py"
    source = service_file.read_text(encoding="utf-8")
    hits = source.count(ANCHOR)
    if hits != 1:
        raise SystemExit(f"锚点命中 {hits} 次（应为 1），上游形状变了，停止构造")
    service_file.write_text(source.replace(ANCHOR, "{row['on_hand']}"), encoding="utf-8")


def self_check(baseline: Path) -> dict:
    sys.path.insert(0, str(baseline))
    from flowerp import ERPService, ERPStore  # noqa: E402  故意从基线树导入被测实现

    with tempfile.TemporaryDirectory(prefix="l06-baseline-check-") as tmp:
        service = ERPService(ERPStore(Path(tmp) / "check.db"))
        service.add_product("L06-A", "口径检查", 100)
        service.receive_stock("L06-A", 8, "opening")
        order = service.create_order("已有订单", [_order_line("L06-A", 3)], "order-A")
        service.reserve_order(order["id"])
        query = service.product("L06-A")
        rows = list(csv.DictReader(io.StringIO(service.export_inventory().lstrip("﻿"))))
        csv_available = int([row for row in rows if row["sku"] == "L06-A"][0]["available"])
        defect_live = query["available"] == 5 and csv_available == 8
        result = {"defect_live": defect_live,
                  "query_available": query["available"], "csv_available": csv_available}
        print(json.dumps(result, ensure_ascii=False))
        if not defect_live:
            raise SystemExit("缺陷未生效：预期查询 5 / CSV 8 分裂，实际见上")
        return result


def _order_line(sku: str, quantity: int):
    from flowerp.models import OrderLine  # noqa: E402

    return OrderLine(sku, quantity, 100)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--skip-self-check", action="store_true")
    args = parser.parse_args()
    build(args.baseline)
    if not args.skip_self_check:
        self_check(args.baseline)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
