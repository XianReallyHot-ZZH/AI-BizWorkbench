"""L06 合同测试：统一 Harness 的分级、报告与退出码（验收项→测试映射见下表）。

上游九项运行器合同映射（vendors/CodexFDE/docs/courses/L06/examples/test_runner_contract.py，
语义对齐、实现自写——ADR-0001）：
  1  pass_and_report            → C3/C4  通过 + 报告落盘（时区、耗时、文件与返回值一致）
  2  blocking_is_not_majority_vote → C4   一项 blocking 失败即 block + 退出 1（非多数表决）
  3  observing_keeps_warning    → C3     observing 失败只记告警，退出 0
  4  exception_keeps_reason_and_continues → C3 异常保留类型+原因，后续项继续跑
  5  observing_exception_is_not_silently_promoted → C3 observing 异常不升格为 blocking
  6  invalid_selection          → C3     空/未知/错 suite/重复名选择一律拒绝
  7  subset_is_explicit         → C3     子集选择必须显式（requested_cases 如实记录）
  8  write_failure_cannot_reuse_old_report → C4 旧报告不可覆盖、写失败不留半份证据
  9  consumer_rejects_summary_or_exit_contradiction → C4 报告与退出码矛盾必须被拒绝

分级语义（CONTEXT.md）：blocking 失败即整体失败并给出非零退出码；observing 只记录不拦截。
"""
from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from evals.harness import run
from evals.report_contract import validate_report


def _ok() -> str:
    return "verified"


def _fail() -> None:
    raise AssertionError("expected=5, actual=8")


def _crash() -> None:
    raise RuntimeError("dependency unavailable; business result unknown")


class RunnerContract(unittest.TestCase):
    """九项合同。被测缝：run(entries) -> (report, exit_code)。"""

    def test_01_pass_and_report(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "report.json"
            report, code = run([("a", "blocking", _ok)], report_path=path)
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(path.read_text(encoding="utf8")), report)
            self.assertIsNotNone(datetime.fromisoformat(report["generated_at"]).tzinfo)
            self.assertIsInstance(report["results"][0]["duration_ms"], int)
            self.assertGreaterEqual(report["results"][0]["duration_ms"], 0)
            validate_report(report, ("a",), code, "all")

    def test_02_blocking_is_not_majority_vote(self):
        report, code = run([("a", "blocking", _ok), ("b", "blocking", _fail), ("c", "blocking", _ok)])
        self.assertEqual(code, 1, "HARNESS-BLOCK: 一项 blocking 失败必须阻断交付")
        self.assertEqual(report["summary"]["blocking_failed"], 1)
        self.assertIn("actual=8", report["results"][1]["error"]["message"])
        validate_report(report, ("a", "b", "c"), code, "all")

    def test_03_observing_keeps_warning(self):
        report, code = run([("a", "blocking", _ok), ("b", "observing", _fail)])
        self.assertEqual(code, 0)
        self.assertEqual(report["summary"]["observing_failed"], 1)
        validate_report(report, ("a", "b"), code, "all")

    def test_04_exception_keeps_reason_and_continues(self):
        called = []
        report, code = run([("a", "blocking", _crash), ("b", "blocking", lambda: called.append("b"))])
        self.assertEqual(called, ["b"], "前项崩溃不得吞掉后续检查")
        self.assertEqual(code, 1, "HARNESS-ERROR")
        self.assertEqual(report["results"][0]["error"]["type"], "RuntimeError")
        self.assertIn("unknown", report["results"][0]["error"]["message"])

    def test_05_observing_exception_is_not_silently_promoted(self):
        report, code = run([("a", "observing", _crash)])
        self.assertEqual(code, 0)
        self.assertEqual(report["summary"]["observing_failed"], 1)

    def test_06_invalid_selection(self):
        cases = [
            (([], {}), "空登记"),
            (([("a", "blocking", _ok)], {"names": []}), "空选择"),
            (([("a", "blocking", _ok)], {"names": ["missing"]}), "未知用例"),
            (([("a", "observing", _ok)], {"suite": "blocking"}), "suite 过滤后为空"),
            (([("a", "blocking", _ok)], {"suite": "wrong"}), "未知 suite"),
            (([("a", "blocking", _ok), ("a", "blocking", _ok)], {}), "重复登记"),
        ]
        for (entries, kwargs), label in cases:
            with self.subTest(selection=label):
                with self.assertRaises(ValueError):
                    run(entries, **kwargs)

    def test_07_subset_is_explicit(self):
        report, code = run([("a", "blocking", _ok), ("b", "observing", _fail)], names=["a"])
        self.assertEqual(code, 0)
        self.assertEqual([item["name"] for item in report["results"]], ["a"])
        self.assertEqual(report["requested_cases"], ["a"])

    def test_08_write_failure_cannot_reuse_old_report(self):
        with tempfile.TemporaryDirectory() as temp:
            old = Path(temp) / "old.json"
            old.write_text("old evidence", encoding="utf8")
            blocker = Path(temp) / "file"
            blocker.write_text("ordinary file", encoding="utf8")
            with self.assertRaises(OSError):
                run([("a", "blocking", _ok)], report_path=blocker / "new.json")
            self.assertEqual(old.read_text(encoding="utf8"), "old evidence", "旧证据不得被波及")
            with self.assertRaises(FileExistsError):
                run([("a", "blocking", _ok)], report_path=old)

    def test_09_consumer_rejects_summary_or_exit_contradiction(self):
        report, code = run([("a", "blocking", _ok)])
        tampered = json.loads(json.dumps(report))
        tampered["summary"]["passed"] = 0
        with self.assertRaises(RuntimeError):
            validate_report(tampered, ("a",), code, "all")
        with self.assertRaises(RuntimeError):
            validate_report(report, ("a",), 1, "all")


if __name__ == "__main__":
    unittest.main()
