"""Ticket B 最小 Eval：在绑定候选工作目录实跑，核对交付 CSV 是否满足 L03 合同预期。

独立于执行器自检（不相信执行者的总结）；也与独立复验 probe 分工——probe 对
既有入口做六场景行为复验（含失败注入与只读性），本脚本只核对【本次交付产物】。
期望值来自 L03 确认版 Spec（8−3=5、12−4=8，两行不合并，稳定排序），不是执行器输出。
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path

EXPECTED_HEADER = ["sku", "name", "site", "location", "lot_id", "on_hand", "reserved", "available"]
EXPECTED_ROWS = [
    ["P001", "螺丝", "EAST", "A01", "LOT01", "8", "3", "5"],
    ["P001", "螺丝", "WEST", "B01", "LOT02", "12", "4", "8"],
]


def main() -> int:
    target = Path("delivery/inventory.csv")
    if not target.is_file():
        print(f"eval: FAIL——交付物不存在：{target}", file=sys.stderr)
        return 3
    rows = list(csv.reader(target.read_text(encoding="utf-8").removeprefix("﻿").splitlines()))
    problems: list[str] = []
    if not rows or rows[0] != EXPECTED_HEADER:
        problems.append(f"表头不符：{rows[0] if rows else '（空文件）'}")
    body = rows[1:]
    if len(body) != 2:
        problems.append(f"明细行数应为 2（两仓位不合并），实际 {len(body)}")
    ordered = sorted(body, key=lambda r: (r[0], r[2], r[3], r[4]))
    if body != ordered:
        problems.append("未按 sku,site,location,lot_id 稳定排序")
    for expected in EXPECTED_ROWS:
        if expected not in body:
            problems.append(f"缺少预期明细（或数值/字段不符）：{expected}")
    if problems:
        print("eval: FAIL", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 3
    print("eval: OK——八列、两行明细、可用量 5/8、稳定排序均满足 L03 合同")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
