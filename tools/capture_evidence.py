"""L02+ teaching aid: execute commands and retain observations, never sign acceptance.

磁盘合同与 vendors/CodexFDE/docs/courses/L01/tools/evidence.py 的 capture() 逐字段同形
（phase/argv/command/cwd/returncode/observed_at/output_file/sha256/provenance），
差异仅在捕获根：vendor 工具硬编码 lesson-01-submission/（docs/replication/evidence/L01.md
附记裁决），本工具写 lesson-02-submission/。导入仍用 vendor import_evidence.py
（只依赖 meta.json 字段 + 导入时 SHA-256 复核）。捕获目录随 Git 提交且永不移动。
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid


PHASES = {"red": "03-failure", "diff": "04-diff", "green": "05-green", "observation": "06-observations"}


def capture(root: Path, phase: str, command: list[str]) -> tuple[Path, int]:
    """Each attempt gets a new directory, including failures and missing executables."""
    now = datetime.now(timezone.utc)
    directory = root / "lesson-02-submission" / PHASES[phase] / (now.strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True, exist_ok=False)
    try:
        result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
        output, code = result.stdout, result.returncode
    except OSError as error:
        output, code = str(error).encode("utf-8"), 127
    (directory / "output.txt").write_bytes(output)
    meta = {"phase": phase, "argv": command, "command": json.dumps(command, ensure_ascii=False),
            "cwd": str(root.resolve()), "returncode": code, "observed_at": now.isoformat(),
            "output_file": str((directory / "output.txt").resolve()),
            "sha256": hashlib.sha256(output).hexdigest(), "provenance": "local_teaching_capture"}
    (directory / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(output.decode("utf-8", errors="replace"))
    print(f"记录：{directory / 'meta.json'}\n真实退出码：{code}")
    return directory, code


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=list(PHASES))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        parser.error("请在 -- 后给出要实际执行的命令")
    # Use the same virtual environment; a restarted shell cannot silently change Python.
    if command[0] == "python":
        command[0] = sys.executable
    _, code = capture(Path.cwd(), args.phase, command)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
