"""Workbench CLI 入口。

骨架只含注册表与分发缝：L01 合同测试（tests/test_l01_workbench_bootstrap.py）
钉住的五个目标命令 workbench-init / workbench-project-add / workbench-task-create /
workbench-evidence-add / workbench-status 由 workbench.bootstrap 提供后在此注册。
注册表为空时任何子命令都应报 invalid choice（起始红的预期形态）。
"""

from __future__ import annotations

import argparse
import json
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
    # L01：bootstrap 注册五个合同命令；接缝保持注册表形状，后续讲次同形扩展。
    from .bootstrap import register_commands

    register_commands(REGISTRY)
    # L03：spec 命令经同一条 REGISTRY 缝注册（适配器在 workbench/spec.py，C9 调用路径同源）。
    from .spec import register_commands as register_spec_commands

    register_spec_commands(REGISTRY)
    # L04：受控执行三命令经同一条缝注册（适配器在 workbench/execution.py，复用 bootstrap 存储入口）。
    from .execution import register_commands as register_execution_commands

    register_execution_commands(REGISTRY)
    # S01 支线：记忆系统七命令经同一条缝注册（适配器在 workbench/learning.py，复用 bootstrap 存储入口）。
    from .learning import register_commands as register_learning_commands

    register_learning_commands(REGISTRY)
    # S02 支线：工作台看板经同一条缝注册（适配器在 workbench/dashboard.py，
    # 只读观察窗：sqlite mode=ro + 仅 GET；读侧复用 bootstrap 查询函数，不建第二套账本）。
    from .dashboard import register_commands as register_dashboard_commands

    register_dashboard_commands(REGISTRY)
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
    try:
        return handler(args)
    except Exception as error:  # 基础设施失败也保持 JSON 错误契约，不裸 traceback
        print(json.dumps({"ok": False, "flowerp_connected": False,
                          "error": f"内部错误：{type(error).__name__}: {error}"},
                         ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
