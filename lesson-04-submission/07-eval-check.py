"""Ticket B 最小 Eval：在绑定候选工作目录实跑，核对交付 CSV 与 ERP 权威账一致。

独立于执行器自检（不相信执行者的总结）。V1 版把 lot_number 硬编码进 lot_id 期望，
对既有交付误报 eval_failed（execution_id=2，记录保留不删）——经裁决修正为本 V2：
期望值一律来自 ERP 权威账（delivery/practice.db 经 SQL 只读读取：stock_lots 的
批次身份、CSV 值与账面数量对照），L03 Spec 只提供业务常量（P001/螺丝/两仓数量）
与形状（八列、两行不合并、稳定排序、available=on_hand−reserved）。

BOM/CRLF 是候选既有导出行为（客户真理，ADR-0005）：probe 与候选自身测试均剥离
BOM 兼容，本脚本同口径接受，不作违约。
"""
from __future__ import annotations

import csv
import sqlite3
import sys
from pathlib import Path

EXPECTED_HEADER = ["sku", "name", "site", "location", "lot_id", "on_hand", "reserved", "available"]
# 业务常量来自 L03 确认版 Spec（8−3=5、12−4=8，两行不合并）；lot_id 取 ERP 账内身份
SPEC_ROWS = [
    ("P001", "螺丝", "EAST", "A01", "LOT01", 8, 3),
    ("P001", "螺丝", "WEST", "B01", "LOT02", 12, 4),
]


def main() -> int:
    problems: list[str] = []
    target = Path("delivery/inventory.csv")
    ledger = Path("delivery/practice.db")
    if not target.is_file():
        print(f"eval: FAIL——交付物不存在：{target}", file=sys.stderr)
        return 3
    if not ledger.is_file():
        print(f"eval: FAIL——ERP 权威账不存在：{ledger}", file=sys.stderr)
        return 3

    lots: dict[str, str] = {}
    connection = sqlite3.connect(f"file:{ledger}?mode=ro", uri=True)
    try:
        for number, lot_id in connection.execute("SELECT lot_number, id FROM stock_lots"):
            lots[number] = lot_id
    finally:
        connection.close()

    expected_rows = []
    for sku, name, site, location, lot_number, on_hand, reserved in SPEC_ROWS:
        lot_id = lots.get(lot_number)
        if lot_id is None:
            problems.append(f"ERP 账内找不到批次 {lot_number}（无法对照）")
            continue
        expected_rows.append([sku, name, site, location, lot_id,
                              str(on_hand), str(reserved), str(on_hand - reserved)])
    if problems:
        print("eval: FAIL", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 3

    text = target.read_text(encoding="utf-8").removeprefix("﻿")  # BOM=候选既有行为（见 docstring）
    rows = list(csv.reader(text.splitlines()))
    if not rows or rows[0] != EXPECTED_HEADER:
        problems.append(f"表头不符：{rows[0] if rows else '（空文件）'}")
    body = rows[1:]
    if len(body) != len(expected_rows):
        problems.append(f"明细行数应为 {len(expected_rows)}（两仓位不合并），实际 {len(body)}")
    ordered = sorted(body, key=lambda r: (r[0], r[2], r[3], r[4]))
    if body != ordered:
        problems.append("未按 sku,site,location,lot_id 稳定排序")
    for expected in expected_rows:
        if expected not in body:
            problems.append(f"缺少与 ERP 权威账一致的明细（或数值/字段不符）：{expected}")
    for row in body:
        if len(row) == 8 and row[7] != str(int(row[5]) - int(row[6])):
            problems.append(f"available ≠ on_hand−reserved：{row}")

    if problems:
        print("eval: FAIL", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 3
    print("eval: OK——八列、两行明细、批次身份与 ERP 账一致、可用量 5/8、稳定排序")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
