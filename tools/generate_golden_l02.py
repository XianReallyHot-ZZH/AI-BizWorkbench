"""L02 对照基准（golden）生成器：用冻结的 Python 工作台采出规范化场景输出（L02 讲义附录 A3）。

单一会话 15 个场景按序驱动真实 CLI 子进程（与合同测试同形，不绕入口直调），
原始 stdout 落 `.runtime/golden-l02/raw/`（gitignore，不入库），规范化后落
`src/test/resources/golden/l02/`（随 Git 提交，生成后永不手改；要变只能重生成并留证据）。

场景面锁定 L02 流的工作台增量语义（L02 讲义附录 A3）：observation 绿后导入**不参与
链判定、不破坏链**（t09/t10），observation **不满足红绿链要求**（t15）——均为
l01 golden 未覆盖的面。审定方案表 t13 一行按单命令粒度展开为 t13–t15 三场景，语义不变。

规范化规则（与 golden/l01 逐字相同，同时写入 manifest.json 的 normalization 块，Java 测试侧按同一规则复现）：
- JSON 输出：字段名掩码（`created_at`/`recorded_at` → `"<TS>"`，`workbench_id` →
  `"<WORKBENCH_ID>"`——分别是墙钟与 uuid4，属于不可复现字段）后按
  `json.dumps(sort_keys=True, ensure_ascii=False, indent=2)` 规范化 + 末尾换行；
- 非 JSON 输出：逐字保留（防御性规则）；
- `observed_at` 是合同字段（链时序语义），生成时用固定字面量，保持逐字不掩码。

确定性证据：生成器幂等（先清场再采），连续两跑 golden 目录 SHA-256 必须一致；
双跑比对结果记入 `docs/replication/evidence/L02-java.md` §G。
"""

from __future__ import annotations

import hashlib
import json
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
WORK_ROOT = REPO_ROOT / ".runtime" / "golden-l02"
RT = ".runtime/golden-l02/rt"  # 传给 CLI 的必须是仓库相对路径字面量（避免绝对路径泄漏）
RAW_DIR = WORK_ROOT / "raw"
INPUTS = WORK_ROOT / "inputs"
GOLDEN_DIR = REPO_ROOT / "src" / "test" / "resources" / "golden" / "l02"

OWNER = "GOLDEN-OWNER"
ACTOR = "GOLDEN-ACTOR"
CHAIN_COMMAND = "mvn test"  # 链命令文本 = 重走后的 Java 测试命令（fixture 常量，非本次真实执行）
SPEC_TEXT = "# 仓库规则对照基准需求 V1\n\n查回规则固化任务的当时依据。\n"

# 固定观察时间（严格递增；observed_at 是合同字段，golden 中逐字保留）
T_RED, T_DIFF, T_GREEN, T_OBS1, T_OBS2 = (
    "2026-09-29T09:00:01+08:00", "2026-09-29T09:00:02+08:00",
    "2026-09-29T09:00:03+08:00", "2026-09-29T09:00:04+08:00",
    "2026-09-29T09:00:05+08:00",
)

MASK_TO_TS = ("created_at", "recorded_at")


def _run_cli(*argv: str) -> tuple[int, str, str]:
    proc = subprocess.run(
        [sys.executable, "-X", "utf8", "-m", "workbench.cli", *argv],
        cwd=str(REPO_ROOT), capture_output=True,
    )
    return proc.returncode, proc.stdout.decode("utf-8"), proc.stderr.decode("utf-8")


def _mask(node):
    if isinstance(node, dict):
        return {key: ("<TS>" if key in MASK_TO_TS
                      else "<WORKBENCH_ID>" if key == "workbench_id"
                      else _mask(value))
                for key, value in node.items()}
    if isinstance(node, list):
        return [_mask(item) for item in node]
    return node


def _normalize(stdout: str) -> str:
    try:
        return json.dumps(_mask(json.loads(stdout)), ensure_ascii=False,
                          indent=2, sort_keys=True) + "\n"
    except json.JSONDecodeError:
        return stdout  # 非 JSON 逐字保留（防御性）


def _write_input(name: str, text: str) -> str:
    """写固定输入件，返回仓库相对路径字面量——进 manifest 的 argv 必须可跨机复现。"""
    (INPUTS / name).write_text(text, encoding="utf-8")
    return f".runtime/golden-l02/inputs/{name}"


def main() -> int:
    # 清场：golden 与运行库一律重采，不做增量
    shutil.rmtree(WORK_ROOT, ignore_errors=True)
    shutil.rmtree(GOLDEN_DIR, ignore_errors=True)
    INPUTS.mkdir(parents=True)
    RAW_DIR.mkdir()
    GOLDEN_DIR.mkdir(parents=True)

    records: list[dict] = []

    def capture(scenario_id: str, expected_rc: int, *argv: str, note: str = "") -> None:
        returncode, stdout, stderr = _run_cli(*argv)
        (RAW_DIR / f"{scenario_id}.stdout.txt").write_text(stdout, encoding="utf-8")
        (RAW_DIR / f"{scenario_id}.stderr.txt").write_text(stderr, encoding="utf-8")
        status = "ok" if returncode == expected_rc else "MISMATCH"
        records.append({"id": scenario_id, "argv": list(argv), "exit_code": returncode,
                        "expected_exit_code": expected_rc, "stdout_file": f"{scenario_id}.stdout.txt",
                        **({"note": note} if note else {})})
        print(f"[{status}] {scenario_id} rc={returncode}（预期 {expected_rc}）")
        if status != "ok":
            raise SystemExit(f"{scenario_id} 退出码偏离预期，中止生成（旧文件已保留供排查）")
        (GOLDEN_DIR / f"{scenario_id}.stdout.txt").write_text(_normalize(stdout), encoding="utf-8")

    # ---- 输入固定件 -------------------------------------------------------
    spec = _write_input("spec.md", SPEC_TEXT)
    out_red = _write_input("red.txt", "red output\n")
    out_diff = _write_input("diff.txt", "diff output\n")
    out_green = _write_input("green.txt", "green output\n")
    out_obs1 = _write_input("obs-n0.txt", "N0 observation output\n")
    out_obs2 = _write_input("obs-regression.txt", "regression observation output\n")
    out_obs3 = _write_input("obs-only.txt", "observation-only output\n")

    # ---- 主账：rt（t01–t12）------------------------------------------------
    capture("t01_init_ok", 0, "workbench-init", "--runtime-dir", RT,
            "--name", "仓库规则对照基准工作台", "--owner", OWNER)
    capture("t02_project_add_ok", 0, "workbench-project-add", "--runtime-dir", RT,
            "--project-id", "PROJECT-A", "--name", "规则固化项目",
            "--path", ".", "--purpose", "golden 对照（L02 流）")
    capture("t03_task_create_with_spec", 0, "workbench-task-create", "--runtime-dir", RT,
            "--project-id", "PROJECT-A", "--task-id", "T-GOLDEN-L02",
            "--requirement-id", "T-GOLDEN-L02", "--request", "把仓库规则固化进规则文件",
            "--actor", ACTOR, "--spec-file", str(spec))
    capture("t04_status_chain_missing", 1, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "T-GOLDEN-L02",
            "--require-red-green-evidence")

    def evidence(phase: str, output: str, returncode: int, observed_at: str,
                 command_text: str = CHAIN_COMMAND) -> tuple:
        return ("workbench-evidence-add", "--runtime-dir", RT, "--task-id", "T-GOLDEN-L02",
                "--phase", phase, "--command-text", command_text,
                "--output-file", str(output), "--returncode", str(returncode),
                "--observed-at", observed_at)

    capture("t05_evidence_add_red", 0, *evidence("red", out_red, 1, T_RED))
    capture("t06_evidence_add_diff", 0, *evidence("diff", out_diff, 0, T_DIFF))
    capture("t07_evidence_add_green", 0, *evidence("green", out_green, 0, T_GREEN))
    capture("t08_status_chain_complete", 0, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "T-GOLDEN-L02",
            "--require-red-green-evidence")
    capture("t09_evidence_add_observation_n0", 0,
            *evidence("observation", out_obs1, 0, T_OBS1, "claude -p N0-probe"),
            note="绿后导入 observation：追加本身成功（账本只追加），链是否仍完整由 t10 判定")
    capture("t10_status_chain_complete_after_obs", 0, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "T-GOLDEN-L02",
            "--require-red-green-evidence",
            note="observation 不参与链判定、不破坏链（L02 流核心语义，bootstrap 链判定只看 red/diff/green）")
    capture("t11_evidence_add_observation_regression", 0,
            *evidence("observation", out_obs2, 0, T_OBS2, "mvn test full-regression"))
    capture("t12_status_plain_full_ledger", 0, "workbench-status", "--runtime-dir", RT,
            note="无 require 标志的全量回显：1 任务 + 5 记录（红/Diff/绿/两 obs）")

    # ---- 第二任务：仅 observation → 不满足红绿链要求（t13–t15）--------------
    capture("t13_task_create_obs_only", 0, "workbench-task-create", "--runtime-dir", RT,
            "--project-id", "PROJECT-A", "--task-id", "T-OBS-ONLY",
            "--requirement-id", "T-OBS-ONLY", "--request", "只有观察记录的任务",
            "--actor", ACTOR)
    capture("t14_evidence_add_obs_only", 0,
            "workbench-evidence-add", "--runtime-dir", RT, "--task-id", "T-OBS-ONLY",
            "--phase", "observation", "--command-text", "claude -p boundary-probe",
            "--output-file", str(out_obs3), "--returncode", "0",
            "--observed-at", T_OBS2,
            note="时间字面量与 t11 相同：不同任务之间互不影响（链判定按任务隔离）")
    capture("t15_status_obs_only_chain_missing", 1, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "T-OBS-ONLY",
            "--require-red-green-evidence",
            note="observation 不满足红绿链要求（仍是 same_command_red_diff_green_missing 缺链）")

    # ---- manifest（确定性内容，不含墙钟）-----------------------------------
    manifest = {
        "generated_from": "frozen Python workbench @ master（见 evidence/L02-java.md §G 记录的 commit）",
        "generator": "tools/generate_golden_l02.py",
        "chain_command_text": CHAIN_COMMAND,
        "normalization": {
            "mask_fields": {"created_at": "<TS>", "recorded_at": "<TS>",
                            "workbench_id": "<WORKBENCH_ID>"},
            "mask_note": "墙钟与 uuid4 为不可复现字段；observed_at 是合同字段，逐字保留",
            "canonical_json": "json.dumps(sort_keys=True, ensure_ascii=False, indent=2) + 换行",
            "non_json": "逐字保留",
        },
        "inputs": {f"golden-l02/inputs/{name}": (INPUTS / name).read_text(encoding="utf-8")
                   for name in ("spec.md", "red.txt", "diff.txt", "green.txt",
                                "obs-n0.txt", "obs-regression.txt", "obs-only.txt")},
        "scenarios": records,
    }
    (GOLDEN_DIR / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8")

    print(f"\n共 {len(records)} 场景；golden → {GOLDEN_DIR.relative_to(REPO_ROOT)}；"
          f"raw → {RAW_DIR.relative_to(REPO_ROOT)}（gitignore）")
    print("确定性自查：请再跑一遍本生成器，两次 golden 目录 SHA-256 必须一致。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
