"""L03 合同测试：验收项 C1..C13 → 用例映射（Spec = docs/lessons/L03-可验收Spec.md §2）。

映射表（验收项 → 测试名 → 实际操作 → 比较什么）：
C1   →（本文件整体：commit 1 时解析器模块/CLI 子命令/工件文件均缺失，目标断言全部失败 = 起始红）
C7   → test_c7_parser_module_exists → 探测 workbench.spec 可导入 → 模块在场（其余 C7 用例的前置闸）
C3   → test_c3_full_contract_six_fields_verbatim → 六段完整文本经 parse_spec → 六字段保留原文
C7   → test_c7_reject_missing_section → 删"非目标"整节 → 拒绝且错误指认缺项
C7   → test_c7_reject_empty_section → 目标仅剩标题 → 拒绝且错误指认空章
C7   → test_c7_reject_duplicate_section → 目标出现两次 → 拒绝且不悄悄选一份
C7   → test_c7_reject_out_of_order → 约束与验收用例对调 → 拒绝并给出应有序
C7   → test_c7_reject_unknown_heading → 出现"附录"节 → 拒绝并指认未知标题
C7   → test_c7_reject_unclosed_fence → 代码围栏不闭合 → 拒绝，不吞掉后续正式章节
C7   → test_c7_fence_heading_is_body_not_section → 验收用例内放含 ## 目标 的围栏示例 → 解析通过且示例保留为正文
C8   → test_c8_load_spec_leaves_file_unchanged → load_spec 前后对同一文件算 SHA-256 → 摘要一致
C9   → test_c9_cli_spec_valid_input_exit_zero → 真实子进程 workbench.cli spec <完整合同> → 退出码 0 + 六字段 JSON
C9   → test_c9_cli_missing_section_error_same_source → CLI 拒绝缺章合同的错误词面与 parse_spec 抛出的一致 → 同源，非第二套解析
C8   → test_c8_cli_rejection_leaves_file_unchanged → CLI 拒绝缺章合同前后 SHA-256 → 摘要一致
C5   → test_c5_template_has_no_inventory_answers → 检查 SPEC_TEMPLATE.md → 六标题在场且无库存专属答案
C6   → test_c6_procurement_draft_reuses_structure → 检查 procurement-draft.md → 同一解析器接受 + 待确认在场 + 无库存答案照搬
D2   → test_d2_capture_tool_submission_root → 采集工具带 --submission-root 运行 → 捕获落指定根且 meta.json 九字段同形

诚实声明（L02 三层分检同形）：本文件断言的是程序结构与 CLI 行为。
C2/C4（决定记录、歧义保留）、C10（三份输入复验）、C11（eval 名登记）、C12/C13（写集、
非目标）由流程与人审承载；C11 的绑定 Eval spec_contract_rejects_ambiguity 以本文件
C7/C3 缺项拒绝用例 discharge，eval.harness 待建设（讲义 D1 裁决），不冒充已建成。
--submission-root 默认值向后兼容（lesson-02-submission/）由代码复查核对，
不用真实执行验证——避免向 L02 封存目录写入。

所有 CLI 用例经真实子进程（sys.executable -X utf8 -m workbench.cli）驱动，
不绕开命令入口直调内部函数；解析器行为在公开函数 parse_spec/load_spec/
ParsedSpec.as_dict() 上断言（vendor 同形冻结接口）。
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

# 独立来源的期望字面量（非由被测代码重算，避免同义反复）。
FULL_SPEC_TEXT = (
    "## 来源\n"
    "测试夹具：运营需要核对可售数量，来源为课堂需求。\n"
    "## 目标\n"
    "提供库存明细文件，供运营核对当前可售数量。\n"
    "## 非目标\n"
    "不发送邮件，不自动补货，不修改库存。\n"
    "## 约束\n"
    "只读；available = on_hand - reserved。\n"
    "## 验收用例\n"
    "在库 8、预占 3 时可用量为 5；导出前后库存不变。\n"
    "## 完成定义\n"
    "结构可解析，业务验收由人确认。\n"
)
EXPECTED_SIX_FIELDS = {
    "source": "测试夹具：运营需要核对可售数量，来源为课堂需求。",
    "goal": "提供库存明细文件，供运营核对当前可售数量。",
    "non_goals": "不发送邮件，不自动补货，不修改库存。",
    "constraints": "只读；available = on_hand - reserved。",
    "acceptance": "在库 8、预占 3 时可用量为 5；导出前后库存不变。",
    "done": "结构可解析，业务验收由人确认。",
}

FENCE_EXAMPLE = (
    "正文中的示例（围栏内，不是正式章节）：\n"
    "```markdown\n"
    "## 目标\n"
    "这是示例正文，不应被当成第二个目标章节。\n"
    "```\n"
)
FENCED_SPEC_TEXT = FULL_SPEC_TEXT.replace(
    "## 验收用例\n在库 8、预占 3 时可用量为 5；导出前后库存不变。\n",
    "## 验收用例\nA1：在库 8、预占 3 时可用量为 5。\n" + FENCE_EXAMPLE + "A2：导出前后库存不变。\n",
)
UNCLOSED_FENCE_TEXT = FENCED_SPEC_TEXT.replace(FENCE_EXAMPLE, FENCE_EXAMPLE.replace("```\n", ""))

MISSING_SECTION_TEXT = FULL_SPEC_TEXT.replace("## 非目标\n不发送邮件，不自动补货，不修改库存。\n", "")
EMPTY_SECTION_TEXT = FULL_SPEC_TEXT.replace(
    "## 目标\n提供库存明细文件，供运营核对当前可售数量。\n", "## 目标\n## 非目标\n")
DUPLICATE_SECTION_TEXT = FULL_SPEC_TEXT.replace(
    "## 约束\n", "## 目标\n重复出现的目标正文。\n## 约束\n")
OUT_OF_ORDER_TEXT = FULL_SPEC_TEXT.replace(
    "## 约束\n只读；available = on_hand - reserved。\n## 验收用例\n在库 8、预占 3 时可用量为 5；导出前后库存不变。\n",
    "## 验收用例\n在库 8、预占 3 时可用量为 5；导出前后库存不变。\n## 约束\n只读；available = on_hand - reserved。\n")
UNKNOWN_HEADING_TEXT = FULL_SPEC_TEXT.replace("## 完成定义\n", "## 附录\n额外章节。\n## 完成定义\n")

# 库存专属答案词面：模板与采购稿不得照抄（讲义 C5/C6）。
INVENTORY_ANSWER_TOKENS = ("available", "on_hand", "reserved", "仓位", "批次", "在库", "预占")
SIX_HEADINGS = ("## 来源", "## 目标", "## 非目标", "## 约束", "## 验收用例", "## 完成定义")
CAPTURE_META_FIELDS = {"phase", "argv", "command", "cwd", "returncode",
                       "observed_at", "output_file", "sha256", "provenance"}


def run_cli(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-X", "utf8", "-m", "workbench.cli", *args],
        cwd=str(REPO_ROOT), capture_output=True, text=True,
    )


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class L03ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)

    def require_parser_module(self) -> None:
        if importlib.util.find_spec("workbench.spec") is None:
            self.fail("L03 目标缺口：workbench/spec.py 尚不存在（解析器模块未实现）")

    def write_tmp_spec(self, name: str, text: str) -> Path:
        path = self.tmp / name
        path.write_text(text, encoding="utf-8")
        return path

    def parse(self, text: str):
        from workbench.spec import parse_spec

        return parse_spec(text)


class C7ParserModuleTest(L03ContractTest):
    def test_c7_parser_module_exists(self) -> None:
        self.require_parser_module()


class C3FullContractTest(L03ContractTest):
    def test_c3_full_contract_six_fields_verbatim(self) -> None:
        self.require_parser_module()
        parsed = self.parse(FULL_SPEC_TEXT)
        self.assertEqual(parsed.as_dict(), EXPECTED_SIX_FIELDS)


class C7RejectionTest(L03ContractTest):
    def assert_rejects(self, text: str, *word_faces: str) -> None:
        self.require_parser_module()
        with self.assertRaises(ValueError) as ctx:
            self.parse(text)
        for word_face in word_faces:
            self.assertIn(word_face, str(ctx.exception))

    def test_c7_reject_missing_section(self) -> None:
        self.assert_rejects(MISSING_SECTION_TEXT, "缺少必要章节", "非目标")

    def test_c7_reject_empty_section(self) -> None:
        self.assert_rejects(EMPTY_SECTION_TEXT, "内容不能为空", "目标")

    def test_c7_reject_duplicate_section(self) -> None:
        self.assert_rejects(DUPLICATE_SECTION_TEXT, "重复章节")

    def test_c7_reject_out_of_order(self) -> None:
        self.assert_rejects(OUT_OF_ORDER_TEXT, "顺序错误")

    def test_c7_reject_unknown_heading(self) -> None:
        self.assert_rejects(UNKNOWN_HEADING_TEXT, "未知章节", "附录")

    def test_c7_reject_unclosed_fence(self) -> None:
        self.assert_rejects(UNCLOSED_FENCE_TEXT, "围栏未闭合")


class C7FenceSemanticsTest(L03ContractTest):
    def test_c7_fence_heading_is_body_not_section(self) -> None:
        self.require_parser_module()
        parsed = self.parse(FENCED_SPEC_TEXT)
        fields = parsed.as_dict()
        # 围栏内同名标题不制造重复章节：六个字段仍是原六节，目标字段未被示例覆盖。
        self.assertEqual(fields["goal"], EXPECTED_SIX_FIELDS["goal"])
        # 示例原文保留为验收用例正文。
        self.assertIn("## 目标", fields["acceptance"])
        self.assertIn("这是示例正文，不应被当成第二个目标章节。", fields["acceptance"])
        self.assertIn("A2：导出前后库存不变。", fields["acceptance"])


class C8ReadonlyTest(L03ContractTest):
    def test_c8_load_spec_leaves_file_unchanged(self) -> None:
        self.require_parser_module()
        from workbench.spec import load_spec

        path = self.write_tmp_spec("full.md", FULL_SPEC_TEXT)
        before = sha256_of(path)
        load_spec(path)
        self.assertEqual(sha256_of(path), before)


class C9CliIntegrationTest(L03ContractTest):
    def test_c9_cli_spec_valid_input_exit_zero(self) -> None:
        self.require_parser_module()
        path = self.write_tmp_spec("full.md", FULL_SPEC_TEXT)
        proc = run_cli("spec", str(path))
        self.assertEqual(proc.returncode, 0,
                         f"完整合同应解析通过；实际输出：{proc.stdout}{proc.stderr}")
        payload = json.loads(proc.stdout)
        self.assertTrue(payload["ok"])
        for field, expected in EXPECTED_SIX_FIELDS.items():
            self.assertEqual(payload["spec"][field], expected)

    def test_c9_cli_missing_section_error_same_source(self) -> None:
        self.require_parser_module()
        from workbench.spec import parse_spec

        with self.assertRaises(ValueError) as ctx:
            parse_spec(MISSING_SECTION_TEXT)
        expected_word_face = str(ctx.exception)
        path = self.write_tmp_spec("missing.md", MISSING_SECTION_TEXT)
        proc = run_cli("spec", str(path))
        self.assertNotEqual(proc.returncode, 0, "缺章合同应被 CLI 拒绝")
        # 错误词面同源：CLI 输出包含 parse_spec 抛出的同一条消息（非第二套解析逻辑）。
        self.assertIn(expected_word_face, proc.stdout,
                      f"CLI 错误词面与 workbench.spec 不同源；实际输出：{proc.stdout}{proc.stderr}")

    def test_c8_cli_rejection_leaves_file_unchanged(self) -> None:
        self.require_parser_module()
        path = self.write_tmp_spec("missing.md", MISSING_SECTION_TEXT)
        before = sha256_of(path)
        run_cli("spec", str(path))
        self.assertEqual(sha256_of(path), before)


class C5TemplateTest(L03ContractTest):
    def test_c5_template_has_no_inventory_answers(self) -> None:
        path = REPO_ROOT / "workbench" / "templates" / "SPEC_TEMPLATE.md"
        if not path.exists():
            self.fail("L03 目标缺口：workbench/templates/SPEC_TEMPLATE.md 尚不存在（模板未抽取）")
        text = path.read_text(encoding="utf-8")
        for heading in SIX_HEADINGS:
            self.assertIn(heading, text)
        lowered = text.lower()
        for token in INVENTORY_ANSWER_TOKENS:
            self.assertNotIn(token.lower(), lowered,
                             f"模板不得残留库存专属答案：{token}")


class C6ProcurementDraftTest(L03ContractTest):
    def test_c6_procurement_draft_reuses_structure(self) -> None:
        path = REPO_ROOT / "lesson-03-submission" / "procurement-draft.md"
        if not path.exists():
            self.fail("L03 目标缺口：lesson-03-submission/procurement-draft.md 尚不存在（迁移练习未完成）")
        text = path.read_text(encoding="utf-8")
        parsed = self.parse(text)
        self.assertEqual(len(parsed.as_dict()), 6, "采购草稿应被同一解析器接受为六段结构")
        self.assertIn("待确认", text, "无来源的采购字段应保持待确认，不得虚构")
        lowered = text.lower()
        for token in INVENTORY_ANSWER_TOKENS:
            self.assertNotIn(token.lower(), lowered,
                             f"采购草稿不得照抄库存答案：{token}")


class D2CaptureToolTest(L03ContractTest):
    def test_d2_capture_tool_submission_root(self) -> None:
        submission_root = self.tmp / "capture-root"
        proc = subprocess.run(
            [sys.executable, "-X", "utf8", "tools/capture_evidence.py",
             "--submission-root", str(submission_root),
             "observation", "--", sys.executable, "-c", "print('probe')"],
            cwd=str(REPO_ROOT), capture_output=True, text=True,
        )
        self.assertEqual(proc.returncode, 0,
                         f"采集工具应真实执行并返回命令退出码；实际输出：{proc.stdout}{proc.stderr}")
        phase_dirs = list((submission_root / "06-observations").iterdir())
        self.assertEqual(len(phase_dirs), 1, "每次捕获应有独立新目录")
        meta = json.loads((phase_dirs[0] / "meta.json").read_text(encoding="utf-8"))
        self.assertEqual(set(meta.keys()), CAPTURE_META_FIELDS, "meta.json 九字段磁盘合同不得漂移")
        self.assertEqual(meta["phase"], "observation")
        self.assertEqual(meta["provenance"], "local_teaching_capture")
        self.assertEqual(meta["returncode"], 0)
        output_bytes = (phase_dirs[0] / "output.txt").read_bytes()
        self.assertEqual(meta["sha256"], hashlib.sha256(output_bytes).hexdigest())
        self.assertNotIn("acceptance", meta, "采集工具永不签验收")


if __name__ == "__main__":
    unittest.main()
