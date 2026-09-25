"""冻结合同 fixture 完整性测试（L01 合同 C1）。

数据基线 CodexFDE@58f4612（ADR-0001/0003）。本测试失败意味着 fixture 与基线漂移，
处理方式是走 ADR-0003 检查点显式采纳，而不是"修好"功能。
"""

from __future__ import annotations

import unittest

from workbench.course_contracts import LESSONS, LESSON_STORY, lesson_contract


class CourseContractFixtureTest(unittest.TestCase):
    def test_lessons_cover_l01_to_l16(self):
        self.assertEqual([item.number for item in LESSONS], list(range(1, 17)))

    def test_l01_contract_is_frozen_baseline(self):
        lesson = lesson_contract(1)
        self.assertEqual(lesson.title, "以终为始：一次可验证的 AI 交付怎样完成？")
        self.assertEqual(lesson.phase, "bootstrap")
        self.assertEqual(lesson.write_scope, ("workbench/", "tests/", "docs/courses/L01/"))
        self.assertEqual(lesson.acceptance, (
            "关键要求能回指原始痛点、Codex 建议和学生决定。",
            "测试能回指验收项，且保留执行前红灯、范围内 Diff 和执行后绿灯。",
            "学生能使用自己开发的命令完成第一次自举记录。",
            "本讲不接入或开发 FlowERP。",
        ))
        self.assertEqual(lesson.eval_cases, ("bootstrap_evidence_is_honest",))
        self.assertEqual(lesson.prerequisites, ())
        self.assertEqual(lesson.baseline_ref, "course/l01-start")
        self.assertIn("尚未接入", lesson.erp_increment)

    def test_l01_story_fields_present(self):
        story = LESSON_STORY[1]
        for field in ("codex_role", "fde_loop", "causal_link"):
            self.assertTrue(story[field].strip(), field)

    def test_out_of_range_lesson_rejected(self):
        with self.assertRaises(ValueError):
            lesson_contract(0)
        with self.assertRaises(ValueError):
            lesson_contract(17)


if __name__ == "__main__":
    unittest.main()
