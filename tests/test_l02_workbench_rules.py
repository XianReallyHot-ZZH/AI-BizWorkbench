"""L02 合同测试：CLAUDE.md 规则要素在场（文本层；行为层由 N0/N1 会话对照承担）。

验收项 → 测试名 → 实际操作 → 比较什么
- C3 → test_five_business_invariants_present   → 读根目录 CLAUDE.md → 五条 FlowERP 业务边界逐条有语义要素
- C3 → test_invariants_carry_applicability     → 读根目录 CLAUDE.md → 边界带适用条件（涉及 FlowERP 业务实现时）
- C2 → test_structure_convention_present       → 读根目录 CLAUDE.md → 调用方向约定与新命令复用语义在场
- C5 → test_definition_of_done_present         → 读根目录 CLAUDE.md → 完成定义要素（交回物/正反路径/未实现不冒充）
- C4 → test_referenced_commands_are_real       → CLAUDE.md 的验证入口字面量 → 被引用的仓库路径真实存在

口径：断言语义要素（组内任一同义词命中即算在场），不锁定措辞；文本在场 ≠ 行为遵守。
ReferencedCommandsAreReal 在起始红时即绿——它是防将来规则写虚构路径的回归护栏，
不是本讲红点；红点集中在 Invariants / Structure / DoD 三组（讲义 §2 C1）。
"""
from __future__ import annotations

from pathlib import Path
import re
import unittest

REPO = Path(__file__).resolve().parents[1]
RULES = REPO / "CLAUDE.md"


def rules_text() -> str:
    return RULES.read_text(encoding="utf-8")


def missing_groups(text: str, *groups: tuple[str, ...]) -> list[tuple[str, ...]]:
    """逐组检查：组内任一关键词出现即算该组在场；返回全部缺席的组。"""
    return [group for group in groups if not any(word in text for word in group)]


class FiveBusinessInvariants(unittest.TestCase):
    """C3：五条 FlowERP 业务边界（讲义 §1 逐字清单）。"""

    def test_five_business_invariants_present(self) -> None:
        text = rules_text()
        per_invariant = [
            # 库存非负 + 原子预占
            missing_groups(text, ("库存",), ("不能为负", "不为负", "永不为负", "非负"), ("预占",), ("原子",)),
            # 同一幂等键只生效一次
            missing_groups(text, ("幂等",), ("一次",)),
            # 状态机迁移 + 取消释放预占
            missing_groups(text, ("状态机",), ("取消",), ("释放",)),
            # 具名审批后才入库
            missing_groups(text, ("审批",), ("入库",), ("具名", "批准", "人工批准")),
            # 记录可追溯，失败不伪装成成功
            missing_groups(text, ("可追溯",), ("失败",), ("不能显示成成功", "不伪装", "不能伪装", "不得把失败", "失败不能")),
        ]
        self.assertEqual(per_invariant, [[]] * 5, f"业务边界语义要素缺席：{per_invariant}")

    def test_invariants_carry_applicability(self) -> None:
        # 适用条件：边界是给"涉及 FlowERP 业务实现"的场景预埋的，不是日常文档改动的负担
        self.assertRegex(rules_text(), r"涉及.{0,8}[Ff]lowERP.{0,12}业务")


class StructureConvention(unittest.TestCase):
    """C2：目录职责与调用方向约定（对齐 L01 已确认结构，未满足项写待办不冒充事实）。"""

    def test_structure_convention_present(self) -> None:
        text = rules_text()
        missing = missing_groups(
            text,
            ("调用方向", "调用关系", "分层"),
            ("命令入口", "入口"),
            ("存储",),
            ("复用",),
        )
        self.assertEqual(missing, [], f"结构约定语义要素缺席：{missing}")


class DefinitionOfDone(unittest.TestCase):
    """C5：完成定义（Definition of Done）。"""

    def test_definition_of_done_present(self) -> None:
        text = rules_text()
        self.assertRegex(text, r"完成定义|Definition of Done")
        missing = missing_groups(
            text,
            ("正常路径",),
            ("失败路径", "失败用例", "反例"),
            ("退出码",),
            ("未解决", "待办", "待确认"),
            ("不写成已实现", "不能写成已经实现", "不得写成已实现", "未实现不得", "不冒充"),
        )
        self.assertEqual(missing, [], f"完成定义语义要素缺席：{missing}")


class ReferencedCommandsAreReal(unittest.TestCase):
    """C4：规则里的验证入口必须指向仓库真实存在的被引用物（不照抄不存在的路径）。"""

    def test_referenced_commands_are_real(self) -> None:
        text = rules_text()
        self.assertIn("unittest discover", text)
        for relative in ("tests", "workbench/cli.py", "workbench/course_contracts.py"):
            self.assertTrue((REPO / relative).exists(), f"规则引用的路径不存在：{relative}")
        self.assertTrue(RULES.name == "CLAUDE.md" and RULES.is_file())


if __name__ == "__main__":
    unittest.main()
