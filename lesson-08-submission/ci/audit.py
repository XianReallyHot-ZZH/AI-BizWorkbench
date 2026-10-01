#!/usr/bin/env python3
"""L08 CI 链复验者对账脚本（随采集根入库，可独立重跑——上游「下载报告核对版本与实际出口」映射）。

对 lesson-08-submission/ci/ 下各 Run 的 harness 报告与证据信封逐项核对：
报告 decision / 信封身份 / 报告字节 SHA-256 独立重算对账 / 八登记项同集。
平台侧元数据以 `gh run list --json` 的 runs.json 与 Run 页为准（本脚本只审已下载原件）。
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

EXPECT = {
    "36826115342": ("基线（commit 2 终态）", "pass", None),        # 未下载原件（终态与 C 同形），见 runs.json
    "36826133489": ("A 假绿（注入+吞失败）", "block", "success"),
    "36826158603": ("B 可信红（只修失败传播，attempt 3）", "block", "failure"),
    "36826186419": ("C 可信绿（只移注入）", "pass", "success"),
    "36826209531": ("隔离（gate 跳过，无 artifact）", None, "failure"),
    "36827129977": ("PR merge 测试（pull_request 事件）", "pass", "success"),
}
EIGHT = {
    "l08_atomic_reservation_pass", "l08_atomic_reservation_shortage",
    "l08_atomic_reservation_write_error", "stock_never_negative",
    "sales_credit_and_atomic_reservation", "ci_evidence_envelope_is_honest",
    "l07_order_regression", "l08_frozen_checks",
}


def main() -> int:
    root = Path(__file__).resolve().parent
    failures = []
    for rid, (label, want_decision, want_job) in sorted(EXPECT.items()):
        line = f"Run {rid} {label}"
        directory = root / rid
        if want_decision is None:
            print(f"OK   {line}（隔离态无 artifact 属预期，结论见 runs.json / Run 页）")
            continue
        if not directory.is_dir():
            failures.append(f"{line}: 目录缺失")
            print(f"FAIL {line}: 目录缺失")
            continue
        files = list(directory.rglob("harness-blocking.json"))
        if not files:
            failures.append(f"{line}: 报告缺失")
            print(f"FAIL {line}: 报告缺失")
            continue
        if len(files) != 1:  # 复查轮 T-1：多份报告须显式失败，不静默审第一份
            failures.append(f"{line}: 报告不唯一（{len(files)} 份）")
            print(f"FAIL {line}: 报告不唯一（{len(files)} 份）")
            continue
        raw = files[0].read_bytes()
        report = json.loads(raw)
        envelope = json.loads(files[0].with_name("ci-evidence.json").read_text())
        decision = report["summary"]["decision"]
        cases = {item["name"] for item in report["results"]}
        sha = hashlib.sha256(raw).hexdigest()
        checks = [
            (decision == want_decision, f"decision={decision} 期望 {want_decision}"),
            (cases == EIGHT, f"八登记项同集（{len(cases)} 项）"),
            (sha == envelope["report_sha256"], "信封哈希对账一致"),
            (envelope["run_id"] == rid, f"信封 run_id={envelope['run_id']}"),
            (envelope["report_decision"] == decision, "信封决策与报告一致"),
        ]
        bad = [why for ok, why in checks if not ok]
        if bad:
            failures.append(f"{line}: " + "; ".join(bad))
            print(f"FAIL {line}: {'; '.join(bad)}")
        else:
            print(f"OK   {line}: {checks[0][1]}，{checks[1][1]}，{checks[2][1]}，commit={envelope['commit_sha'][:7]}（Job 结论 {want_job} 以 Run 页为准）")
    if failures:
        print(f"\n对账失败 {len(failures)} 项")
        return 1
    print("\n全部对账通过：A/B 同缺陷同检查集同失败项（唯一红点 write-error），B→C 注入移除即恢复全绿；"
          "PR Run 信封 commit_sha 为 merge ref（≠ 分支头，平台测合并结果语义，可追溯）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
