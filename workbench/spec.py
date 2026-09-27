"""六段式 Spec 结构解析器与 CLI 适配器（L03 合同，docs/lessons/L03-可验收Spec.md §2）。

只回答一个问题：合同的六段结构是否完整。缺章、空章、重复、乱序、未知标题、
未闭合代码围栏各给可指导修订的原因；不判断业务语义——结构通过 ≠ 业务正确，
业务取舍由人检查（上游辅导资料 §5 分层纪律，讲义 C7/C10）。

接口与错误词面同 vendors/CodexFDE workbench/spec.py 解析半边（讲义 D5：
``build_delivery_spec`` 等需求生成机器属上游 L04 消费面，本讲不复刻）。
CLI 适配器经 REGISTRY 缝注册（workbench/cli.py 只加注册调用，缝位置不变）：
成功输出 ``{"ok": true, "spec": {六字段}}`` + 退出码 0；结构错误在 handler 层
接住 ValueError（不走顶层异常边界，避免合同拒绝被误标"内部错误"），
输出 ``{"ok": false, "error": "<parse_spec 原词面>"}`` + 退出码 1。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

from .bootstrap import FLOWERP_CONNECTED  # 冻结边界单一来源：L04 换挡点只改 bootstrap 一处


REQUIRED_SECTIONS = ("来源", "目标", "非目标", "约束", "验收用例", "完成定义")


@dataclass(frozen=True)
class ParsedSpec:
    source: str
    goal: str
    non_goals: str
    constraints: str
    acceptance: str
    done: str

    def as_dict(self) -> dict[str, str]:
        return {
            "source": self.source,
            "goal": self.goal,
            "non_goals": self.non_goals,
            "constraints": self.constraints,
            "acceptance": self.acceptance,
            "done": self.done,
        }


def _contract_headings(text: str) -> list[re.Match]:
    """Ignore headings in fenced examples while preserving original text offsets.

    This handles the subset used by our six-section contract, not arbitrary
    Markdown. Unclosed examples are rejected instead of hiding later sections.
    """
    masked: list[str] = []
    fence: tuple[str, int] | None = None
    for line in text.splitlines(keepends=True):
        match = re.match(r"^[ ]{0,3}(`{3,}|~{3,})([^\r\n]*)", line)
        if fence:
            if match and match[1][0] == fence[0] and len(match[1]) >= fence[1] and not match[2].strip():
                fence = None
            masked.append("".join(char if char in "\r\n" else " " for char in line))
        elif match and not (match[1][0] == "`" and "`" in match[2]):
            fence = (match[1][0], len(match[1]))
            masked.append("".join(char if char in "\r\n" else " " for char in line))
        else:
            masked.append(line)
    if fence:
        raise ValueError("Spec 代码围栏未闭合，请补齐示例的结束标记")
    return list(re.finditer(r"^##[ \t]+([^\r\n]+?)[ \t]*\r?$", "".join(masked), flags=re.MULTILINE))


def parse_spec(text: str) -> ParsedSpec:
    matches = _contract_headings(text)
    names = [match.group(1).strip() for match in matches]
    unknown = list(dict.fromkeys(name for name in names if name not in REQUIRED_SECTIONS))
    if unknown:
        raise ValueError(f"Spec 包含未知章节：{', '.join(unknown)}")
    duplicates = list(dict.fromkeys(name for name in names if names.count(name) > 1))
    if duplicates:
        raise ValueError(f"Spec 包含重复章节：{', '.join(duplicates)}")
    missing = [name for name in REQUIRED_SECTIONS if name not in names]
    if missing:
        raise ValueError(f"Spec 缺少必要章节：{', '.join(missing)}")
    if tuple(names) != REQUIRED_SECTIONS:
        expected = " → ".join(REQUIRED_SECTIONS)
        actual = " → ".join(names)
        raise ValueError(f"Spec 章节顺序错误；应为：{expected}；实际为：{actual}")

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        name = match.group(1).strip()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        sections[name] = text[match.end():end].strip()
    empty = [name for name in REQUIRED_SECTIONS if not sections[name]]
    if empty:
        raise ValueError(f"Spec 章节内容不能为空：{', '.join(empty)}")
    return ParsedSpec(
        source=sections["来源"], goal=sections["目标"], non_goals=sections["非目标"],
        constraints=sections["约束"], acceptance=sections["验收用例"], done=sections["完成定义"],
    )


def load_spec(path: str | Path = "FDE_SPEC.md") -> ParsedSpec:
    # 默认值系 vendor 冻结接口继承（上游根 FDE_SPEC.md 为 REQ 级交付主合同）；
    # 本讲不建根文件（讲义 D4），调用方一律显式传路径。
    return parse_spec(Path(path).read_text(encoding="utf-8"))


def _cmd_spec(args: argparse.Namespace) -> int:
    try:
        parsed = load_spec(args.spec_path)
    except (ValueError, OSError) as error:  # UnicodeDecodeError ⊂ ValueError：非 UTF-8 输入同形拒绝
        print(json.dumps({"ok": False, "flowerp_connected": FLOWERP_CONNECTED, "error": str(error)},
                         ensure_ascii=False, indent=2))
        return 1
    print(json.dumps({"ok": True, "flowerp_connected": FLOWERP_CONNECTED, "spec": parsed.as_dict()},
                     ensure_ascii=False, indent=2))
    return 0


def _register_spec(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("spec", help="解析六段式 Spec，输出六字段 JSON；结构错误非零退出")
    parser.add_argument("spec_path", help="Spec 文件路径（UTF-8，只读不修改）")
    parser.set_defaults(handler=_cmd_spec)


def register_commands(registry: dict) -> None:
    """REGISTRY 缝注册入口（与 bootstrap.register_commands 同形）。"""
    registry["spec"] = _register_spec


if __name__ == "__main__":  # pragma: no cover - 模块以 workbench.cli 为唯一入口
    print("请经 python -X utf8 -m workbench.cli spec <文件> 调用", file=sys.stderr)
    raise SystemExit(2)
