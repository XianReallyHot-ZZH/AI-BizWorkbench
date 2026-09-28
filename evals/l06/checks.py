"""L06 登记项收口：统一 Harness 按本清单运行（讲义 §2 登记项构成，D4）。

五项（四 blocking + 一 observing，对上游 ENTRIES 四项；差异如实记录：
上游 l05_personal_* 是其 L05 私有文件，本仓库对应物为指纹复核 + 客户用例回归）：
  l06_stock_consistency      blocking  口径交叉检查驱动（查询/CSV 同口径 + 超额拒绝状态不变）
  l05_receiving_regression   blocking  客户 blocking eval receiving_is_idempotent 照跑（L05 能力不回归）
  l06_customer_stock_case    blocking  客户 blocking eval stock_never_negative 照跑（合同绑定 eval）
  l06_frozen_checks          blocking  客户 eval 文件指纹复核（检查不被偷偷改动，L05 C5 机制）
  teaching_observation       observing 如实标注的教学告警（演示非阻断语义，不是真实缺陷）

目标树经环境变量 L06_EVAL_TARGET 注入（同命令红绿链的异树切换，L05 三重披露先例）。
主入口：python -m evals.l06.checks [--no-report] [--report-path P] [--opening N --reserved N]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SUBPROCESS_TIMEOUT = 300

FROZEN_EVAL_FILES = ("eval/harness.py", "eval/cases.py")


def _target() -> Path:
    value = os.environ.get("L06_EVAL_TARGET")
    if not value:
        raise RuntimeError("L06_EVAL_TARGET 未设置：本收口必须显式指定被测 flowERP 树")
    target = Path(value).resolve()
    if not (target / "flowerp").is_dir():
        raise RuntimeError(f"L06_EVAL_TARGET 下没有 flowerp/：{target}")
    return target


def _subprocess_evidence(command: list[str], *, cwd: Path | None = None) -> str:
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                            encoding="utf8", timeout=SUBPROCESS_TIMEOUT)
    output = (result.stdout + result.stderr).strip()
    tail = output.splitlines()[-1] if output else ""
    if result.returncode != 0:
        raise AssertionError(f"rc={result.returncode}: {tail[:2000]}")
    return tail


def _run_driver(opening: int, reserved: int) -> str:
    target = _target()
    driver = Path(__file__).resolve().parent / "stock_consistency_check.py"
    return _subprocess_evidence(
        [sys.executable, "-X", "utf8", str(driver),
         "--target", str(target), "--opening", str(opening), "--reserved", str(reserved)])


def _customer_case(case: str) -> str:
    target = _target()
    return _subprocess_evidence(
        [sys.executable, "-X", "utf8", "-m", "eval.harness", "--case", case, "--no-report"],
        cwd=target)


def _frozen_checks() -> str:
    target = _target()
    mismatched = {}
    for rel in FROZEN_EVAL_FILES:
        target_hash = hashlib.sha256((target / rel).read_bytes()).hexdigest()
        source_hash = hashlib.sha256((REPO_ROOT / "vendors" / "flowERP" / rel).read_bytes()).hexdigest()
        if target_hash != source_hash:
            mismatched[rel] = {"target": target_hash[:12], "vendors": source_hash[:12]}
    if mismatched:
        raise AssertionError(f"冻结检查被改动：{json.dumps(mismatched, ensure_ascii=False)}")
    return "冻结检查指纹一致: " + ", ".join(
        f"{rel}={hashlib.sha256((target / rel).read_bytes()).hexdigest()[:12]}"
        for rel in FROZEN_EVAL_FILES)


def _teaching_observation() -> None:
    raise AssertionError("教学观察项：模拟非阻断提示，不代表实际缺陷")


def entries(opening: int = 8, reserved: int = 3) -> list[tuple[str, str, object]]:
    return [
        ("l06_stock_consistency", "blocking", (lambda: _run_driver(opening, reserved))),
        ("l05_receiving_regression", "blocking", (lambda: _customer_case("receiving_is_idempotent"))),
        ("l06_customer_stock_case", "blocking", (lambda: _customer_case("stock_never_negative"))),
        ("l06_frozen_checks", "blocking", _frozen_checks),
        ("teaching_observation", "observing", _teaching_observation),
    ]


def main() -> int:
    from evals.harness import run

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-path", help="将完整 JSON 报告写入指定路径（不覆盖既有文件）")
    parser.add_argument("--no-report", action="store_true", help="只打印报告不落盘（红绿链口径）")
    parser.add_argument("--opening", type=int, default=8)
    parser.add_argument("--reserved", type=int, default=3)
    args = parser.parse_args()
    report_path = None if (args.no_report or not args.report_path) else Path(args.report_path)
    report, code = run(entries(args.opening, args.reserved), report_path=report_path)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
