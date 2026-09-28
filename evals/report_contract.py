"""Eval 报告合同：按调用方请求的用例身份复核报告，不信报告自己的绿色汇总。

review 缝（L06 合同 acceptance[1]）：阻断失败、报告 decision 和进程退出码必须
一致；汇总与逐项结果矛盾、报告结论与退出码矛盾、用例身份被替换——一律 RuntimeError。
可信红报告同样通过复核（复核只验"报告如实"，不验"业务通过"）。报告 schema 与
客户 flowERP eval/harness 报告同形（schema_version 1.0），对两边的报告均可复核。
"""
from __future__ import annotations

LEVELS = {"blocking", "observing"}
SUMMARY_COUNT_KEYS = ("total", "passed", "blocking_failed", "observing_failed")


def validate_report(report: dict, case_names, returncode: int, suite: str = "all") -> None:
    case_names = tuple(case_names)
    if not isinstance(report, dict) or report.get("schema_version") != "1.0":
        raise RuntimeError("Eval 报告 Schema 无效")
    if report.get("suite") != suite:
        raise RuntimeError("Eval 报告 suite 与请求不一致")

    requested = report.get("requested_cases")
    if (not isinstance(requested, list)
            or any(not isinstance(name, str) for name in requested)
            or len(requested) != len(set(requested))
            or set(requested) != set(case_names)):
        raise RuntimeError("Eval 请求用例与本次任务不一致")

    results = report.get("results")
    if not isinstance(results, list) or not results or any(not isinstance(item, dict) for item in results):
        raise RuntimeError("Eval 结果必须包含实际用例")
    names = [item.get("name") for item in results]
    if (any(not isinstance(name, str) for name in names)
            or len(names) != len(set(names))
            or set(names) != set(case_names)):
        raise RuntimeError("Eval 实际用例缺失、重复或被替换")

    for item in results:
        if type(item.get("passed")) is not bool:
            raise RuntimeError("Eval passed 必须为布尔值")
        if item.get("level") not in LEVELS:
            raise RuntimeError("Eval 用例等级无效")
        if suite in LEVELS and item["level"] != suite:
            raise RuntimeError("Eval 用例等级与 suite 不一致")

    blocking_failed = sum(1 for item in results if not item["passed"] and item["level"] == "blocking")
    expected = {
        "total": len(results),
        "passed": sum(1 for item in results if item["passed"]),
        "blocking_failed": blocking_failed,
        "observing_failed": sum(1 for item in results if not item["passed"] and item["level"] == "observing"),
        "decision": "block" if blocking_failed else "pass",
    }
    summary = report.get("summary")
    if not isinstance(summary, dict) or any(summary.get(key) != value for key, value in expected.items()):
        raise RuntimeError("Eval 汇总与逐项结果不一致")
    if any(type(summary.get(key)) is not int for key in SUMMARY_COUNT_KEYS):
        raise RuntimeError("Eval 汇总计数必须为整数")
    if returncode != (1 if blocking_failed else 0):
        raise RuntimeError("Eval 进程退出码与报告结论不一致")
