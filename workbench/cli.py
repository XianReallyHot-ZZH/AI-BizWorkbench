"""Workbench CLI 入口。

骨架只含注册表与分发缝：L01 合同测试（tests/test_l01_workbench_bootstrap.py）
钉住的五个目标命令 workbench-init / workbench-project-add / workbench-task-create /
workbench-evidence-add / workbench-status 由 workbench.bootstrap 提供后在此注册。
注册表为空时任何子命令都应报 invalid choice（起始红的预期形态）。
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Callable, Sequence

# 子命令注册表：键为子命令名，值为注册函数（接收 subparsers，挂 parser 与 handler）。
# 接缝约定：实现方在 bootstrap 等模块中提供 register_xxx(subparsers)，在此表登记。
REGISTRY: dict[str, Callable[[argparse._SubParsersAction], None]] = {}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m workbench.cli",
        description="AI-BizWorkbench 个人研发工作台 CLI",
    )
    subparsers = parser.add_subparsers(dest="command", metavar="<command>")
    for register in REGISTRY.values():
        register(subparsers)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    handler = getattr(args, "handler", None)
    if handler is None:
        parser.print_help(sys.stderr)
        return 2
    return handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
