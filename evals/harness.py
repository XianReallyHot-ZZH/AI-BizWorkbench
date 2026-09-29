"""统一 Eval Harness：按登记清单分级执行检查，产出报告与一致的进程退出码。

本讲增量（L06，LESSONS[6] workbench_increment「统一 Harness、判决与退出码」的
本仓库落地，ADR-0001 全新实现）。分级语义（CONTEXT.md）：blocking 失败即整体
失败并给出非零退出码；observing 只记录不拦截。报告 schema_version 1.0 与客户
flowERP eval/harness 对齐（L08「本地/远端同一 Eval 身份」铺垫）；报告独占写入
（x 模式），一份报告属于一次调用，绝不覆盖旧证据。

报告/退出码的一致性由 evals.report_contract 独立复核（review 缝：可信红报告
同样通过复核；是否返工看业务判决）。后续 L07 Hook 复用本入口作为同一裁判。
"""
from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = "1.0"
LEVELS = ("blocking", "observing")
SUITES = ("all", "blocking", "observing")


def run(entries, *, suite: str = "all", names=None, report_path=None):
    """运行登记项，返回 (report, exit_code)。

    entries: (name, level, callable) 序列；suite/names 选择子集（显式记录在
    requested_cases）；report_path 非 None 时独占写入 JSON 报告。
    退出码约定：存在 blocking 失败返回 1，否则 0。
    """
    if suite not in SUITES:
        raise ValueError(f"未知 suite：{suite}")
    registry = [name for name, _level, _fn in entries]
    if len(registry) != len(set(registry)):
        raise ValueError("登记项重名")
    if any(level not in LEVELS for _, level, _fn in entries):
        raise ValueError("登记项等级无效")
    requested = list(dict.fromkeys(registry if names is None else names))
    selected = [(name, level, fn) for name, level, fn in entries
                if name in requested and (suite == "all" or level == suite)]
    if not selected or set(requested) != {name for name, _level, _fn in selected}:
        raise ValueError("选择为空、含未知项或与 suite 不匹配")

    results = []
    for name, level, fn in selected:
        start = time.perf_counter()
        error = None
        try:
            evidence = str(fn())
            passed = True
        except Exception as exc:  # 检查崩溃不吞掉后续项，原因如实入报告
            evidence = str(exc)
            passed = False
            error = {"type": type(exc).__name__, "message": str(exc)}
        results.append({
            "name": name, "level": level, "passed": passed,
            "duration_ms": round((time.perf_counter() - start) * 1000),
            "evidence": evidence, "error": error,
        })

    blocking_failed = sum(1 for item in results if not item["passed"] and item["level"] == "blocking")
    observing_failed = sum(1 for item in results if not item["passed"] and item["level"] == "observing")
    report = {
        "schema_version": SCHEMA_VERSION,
        "suite": suite,
        "requested_cases": requested,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary": {
            "total": len(results),
            "passed": sum(1 for item in results if item["passed"]),
            "blocking_failed": blocking_failed,
            "observing_failed": observing_failed,
            "decision": "block" if blocking_failed else "pass",
        },
        "results": results,
    }
    if report_path is not None:
        target = Path(report_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        # 一份报告属于一次调用；旧证据不可覆盖（x 模式，FileExistsError 即拒绝）。
        with target.open("x", encoding="utf8") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
    return report, (1 if blocking_failed else 0)
