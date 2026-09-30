"""B 票 eval（V2 形态直接采用，Python 时代 §3-17 裁决移植）：对照 ERP 权威账 + Spec 业务常量。

权威账 = 候选内 delivery/practice.db（种子经 flowERP 自身服务写入）；对照查询与
flowerp/import_export.py 的 inventory 导出查询**同源**（SQL 只读，字段与排序逐字一致，
practice 账为单组织故免 organization 过滤）。CSV 以 utf-8-sig 读回（BOM 为候选导出
链既有行为，probe 兼容，ADR-0005 客户真理，不构成违约）。比较按 csv.DictWriter 的
str() 转换口径逐字段逐序对照。eval 与交付均跨 cwd 以绝对路径调用（L04 时代教训）。

用法：07-eval-check.py --workspace <候选绝对路径>   # 退出码 0=通过 1=失败
"""
from __future__ import annotations

import argparse
import csv
import json
import sqlite3
import sys
from pathlib import Path

HEADER = ["sku", "name", "site", "location", "lot_id", "on_hand", "reserved", "available"]

# 与 flowerp/import_export.py inventory 导出查询同源（WHERE b.organization_id=? 在单组织
# practice 账下免参；ORDER BY 即 Spec A6 的稳定排序口径）
AUTHORITY_SQL = (
    "SELECT p.sku,p.name,s.code AS site,l.code AS location,b.lot_id,b.on_hand,b.reserved,"
    "b.on_hand-b.reserved AS available "
    "FROM stock_balance b "
    "JOIN product_master p ON p.id=b.product_id "
    "JOIN storage_locations l ON l.id=b.location_id "
    "JOIN sites s ON s.id=l.site_id "
    "ORDER BY p.sku,s.code,l.code,b.lot_id"
)


def main() -> int:
    parser = argparse.ArgumentParser(description="B 票 eval：CSV 对照 practice.db 权威账与 Spec 常量")
    parser.add_argument("--workspace", required=True, help="候选绝对路径")
    args = parser.parse_args()
    workspace = Path(args.workspace)
    csv_path = workspace / "delivery" / "inventory.csv"
    db_path = workspace / "delivery" / "practice.db"
    failures: list[str] = []

    if not csv_path.is_file():
        print(json.dumps({"ok": False, "failures": [f"交付物缺失：{csv_path}"]}, ensure_ascii=False))
        return 1
    if not db_path.is_file():
        print(json.dumps({"ok": False, "failures": [f"练习账缺失：{db_path}"]}, ensure_ascii=False))
        return 1

    with csv_path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.reader(stream)
        rows = [row for row in reader]
    if not rows:
        failures.append("CSV 为空：连八列表头都不在场（Spec A4 要求空库存也出表头）")
    elif rows[0] != HEADER:
        failures.append(f"列序不符 Spec A1：期望 {HEADER}，实际 {rows[0]}")

    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        authority = conn.execute(AUTHORITY_SQL).fetchall()
    finally:
        conn.close()

    data_rows = rows[1:] if rows else []
    if len(data_rows) != len(authority):
        failures.append(f"行数不符权威账：CSV {len(data_rows)} 行 vs 权威账 {len(authority)} 行"
                        "（同 SKU 多仓位批次不得合并，Spec A3）")
    for index, (row, expected) in enumerate(zip(data_rows, authority), start=2):
        expected_cells = ["" if value is None else str(value) for value in expected]
        if row != expected_cells:
            failures.append(f"第 {index} 行与权威账不一致：期望 {expected_cells}，实际 {row}")
        if len(row) == len(HEADER):
            try:
                on_hand, reserved, available = int(row[5]), int(row[6]), int(row[7])
                if available != on_hand - reserved:
                    failures.append(f"第 {index} 行公式不符 Spec A2：available 应为 {on_hand - reserved}，实际 {available}")
            except ValueError:
                failures.append(f"第 {index} 行数量列非整数：{row[5:]}")

    result = {"ok": not failures, "csv_rows": len(data_rows), "authority_rows": len(authority),
              "failures": failures}
    print(json.dumps(result, ensure_ascii=False))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
