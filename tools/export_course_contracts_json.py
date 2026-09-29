"""把冻结 Python 合同 fixture 投影为中性 JSON，供 Java 完整性测试做全字段逐字等价机检。

铁律 4（ADR-0006 口径）：Java 常量类 CourseContracts 与 Python 载体必须字符串内容
逐字等价。本工具把 Python 侧数据（含 as_dict 派生字段）机械导出为排序后的 JSON，
落到 src/test/resources/coursecontracts/frozen-python-projection.json；Java 测试侧
逐字段比对，任何转录滑手（错字、漏项、串位）都会被抓住。

重生成时机：仅当 ADR-0003 检查点显式采纳上游合同变更、或 Python 载体换挡时。
只读 workbench/course_contracts.py，不写回任何冻结文件。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

from workbench.course_contracts import (  # noqa: E402
    COURSE_BUILD_THESIS,
    COURSE_WORKSPACE_BOUNDARY,
    LESSONS,
    LESSON_STORY,
)

OUT = REPO_ROOT / "src" / "test" / "resources" / "coursecontracts" / "frozen-python-projection.json"


def main() -> int:
    payload = {
        "provenance": (
            "workbench/course_contracts.py 的逐字 JSON 投影（工具 tools/export_course_contracts_json.py）。"
            "Java 测试以本文件为独立事实源做全字段等价比对；本文件不手改，只随投影重生成。"
        ),
        "course_build_thesis": COURSE_BUILD_THESIS,
        "course_workspace_boundary": COURSE_WORKSPACE_BOUNDARY,
        "lessons": [lesson.as_dict() for lesson in LESSONS],
        "lesson_story": {str(key): value for key, value in LESSON_STORY.items()},
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"已投影 {len(LESSONS)} 讲合同 → {OUT.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
