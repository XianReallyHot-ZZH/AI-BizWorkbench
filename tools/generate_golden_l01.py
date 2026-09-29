"""L01 对照基准（golden）生成器：用冻结的 Python 工作台采出规范化场景输出（ADR-0006 附录 A3）。

单一会话 22 个场景按序驱动真实 CLI 子进程（与合同测试同形，不绕入口直调），
原始 stdout 落 `.runtime/golden-l01/raw/`（gitignore，不入库），规范化后落
`src/test/resources/golden/l01/`（随 Git 提交，生成后永不手改；要变只能重生成并留证据）。

规范化规则（同时写入 manifest.json 的 normalization 块，Java 测试侧按同一规则复现）：
- JSON 输出：字段名掩码（`created_at`/`recorded_at` → `"<TS>"`，`workbench_id` →
  `"<WORKBENCH_ID>"`——分别是墙钟与 uuid4，属于不可复现字段）后按
  `json.dumps(sort_keys=True, ensure_ascii=False, indent=2)` 规范化 + 末尾换行；
- 非 JSON 输出：逐字保留（L01 五命令恒出 JSON，此条为防御性规则）；
- `observed_at` 是合同字段（链时序语义），生成时用固定字面量，保持逐字不掩码。

确定性证据：生成器幂等（先清场再采），连续两跑 golden 目录 SHA-256 必须一致；
双跑比对结果记入 `docs/replication/evidence/L01-java.md` §G。
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
WORK_ROOT = REPO_ROOT / ".runtime" / "golden-l01"
RT = ".runtime/golden-l01/rt"  # 传给 CLI 的必须是仓库相对路径字面量（避免绝对路径泄漏）
RT_EMPTY = ".runtime/golden-l01/rt-empty"
RAW_DIR = WORK_ROOT / "raw"
INPUTS = WORK_ROOT / "inputs"
GOLDEN_DIR = REPO_ROOT / "src" / "test" / "resources" / "golden" / "l01"

OWNER = "GOLDEN-OWNER"
ACTOR = "GOLDEN-ACTOR"
CHAIN_COMMAND = "mvn test"  # 链命令文本 = 重走后的 Java 测试命令（fixture 常量，非本次真实执行）
SPEC_TEXT = "# 对照基准需求 V1\n\n查回任务当时依据的要求。\n"

# 固定观察时间（严格递增；observed_at 是合同字段，golden 中逐字保留）
T_RED, T_DIFF, T_GREEN, T_POST = (
    "2026-09-29T09:00:01+08:00", "2026-09-29T09:00:02+08:00",
    "2026-09-29T09:00:03+08:00", "2026-09-29T09:00:04+08:00",
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
        return stdout  # 非 JSON 逐字保留（防御性，L01 不应触达）


def _write_input(name: str, text: str) -> str:
    """写固定输入件，返回仓库相对路径字面量——进 manifest 的 argv 必须可跨机复现。"""
    (INPUTS / name).write_text(text, encoding="utf-8")
    return f".runtime/golden-l01/inputs/{name}"


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
    out_post = _write_input("post-red.txt", "post-green red output\n")
    out_empty = _write_input("empty.txt", "")

    # ---- 会话一：rt（主账，22 场景中 21 个）--------------------------------
    capture("s01_init_ok", 0, "workbench-init", "--runtime-dir", RT,
            "--name", "对照基准工作台", "--owner", OWNER)
    capture("s02_init_same_owner_idempotent", 0, "workbench-init", "--runtime-dir", RT,
            "--name", "换了名字也不生效", "--owner", OWNER, note="同 owner 幂等，身份不变（F13 同形）")
    capture("s03_init_owner_clash", 1, "workbench-init", "--runtime-dir", RT,
            "--name", "对照基准工作台", "--owner", "GOLDEN-OTHER")
    capture("s04_project_add_ok", 0, "workbench-project-add", "--runtime-dir", RT,
            "--project-id", "PROJECT-A", "--name", "合同测试项目",
            "--path", ".", "--purpose", "golden 对照")
    capture("s05_project_add_duplicate", 1, "workbench-project-add", "--runtime-dir", RT,
            "--project-id", "PROJECT-A", "--name", "重复项目", "--path", ".", "--purpose", "")
    capture("s06_task_create_with_spec", 0, "workbench-task-create", "--runtime-dir", RT,
            "--project-id", "PROJECT-A", "--task-id", "T-GOLDEN",
            "--requirement-id", "T-GOLDEN", "--request", "对照基准第一版请求",
            "--actor", ACTOR, "--spec-file", str(spec))
    capture("s07_task_create_missing_project", 1, "workbench-task-create", "--runtime-dir", RT,
            "--project-id", "GHOST-PROJECT", "--task-id", "T-GHOST",
            "--request", "无所属任务必须被拒", "--actor", ACTOR)
    capture("s08_task_create_duplicate", 1, "workbench-task-create", "--runtime-dir", RT,
            "--project-id", "PROJECT-A", "--task-id", "T-GOLDEN",
            "--request", "换掉第一版的不同内容", "--actor", ACTOR)
    capture("s09_status_plain", 0, "workbench-status", "--runtime-dir", RT)
    capture("s10_status_chain_missing", 1, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "T-GOLDEN",
            "--require-red-green-evidence")

    def evidence(phase: str, output: str, returncode: int, observed_at: str,
                 command_text: str = CHAIN_COMMAND) -> tuple:
        return ("workbench-evidence-add", "--runtime-dir", RT, "--task-id", "T-GOLDEN",
                "--phase", phase, "--command-text", command_text,
                "--output-file", str(output), "--returncode", str(returncode),
                "--observed-at", observed_at)

    capture("s11_evidence_add_red", 0, *evidence("red", out_red, 1, T_RED))
    capture("s12_status_chain_red_only", 1, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "T-GOLDEN",
            "--require-red-green-evidence")
    capture("s13_evidence_add_diff", 0, *evidence("diff", out_diff, 0, T_DIFF))
    capture("s14_evidence_add_green", 0, *evidence("green", out_green, 0, T_GREEN))
    capture("s15_status_chain_complete", 0, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "T-GOLDEN",
            "--require-red-green-evidence")
    capture("s16_evidence_add_post_green_red", 0, *evidence("red", out_post, 1, T_POST),
            note="绿后新失败：追加本身成功（账本只追加），链由 s17 判失权")
    capture("s17_status_chain_stale_green", 1, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "T-GOLDEN",
            "--require-red-green-evidence", note="全局最新 red/diff/green 须为成功绿（F2 跨命令同罪口径）")
    capture("s18_status_missing_task", 1, "workbench-status", "--runtime-dir", RT,
            "--require-project", "PROJECT-A", "--require-task", "CASE-WB-L01-MISSING",
            "--require-red-green-evidence")
    capture("s19_evidence_add_naive_time", 1, *evidence("observation", out_green, 0,
                                                        "2026-09-29T09:00:00"))
    capture("s20_evidence_add_empty_output", 1, *evidence("observation", out_empty, 0,
                                                          "2026-09-29T09:00:05+08:00"))

    # ---- 会话二：rt-empty（未初始化查询，且验证不偷偷建库）------------------
    capture("s21_status_uninitialized", 1, "workbench-status", "--runtime-dir", RT_EMPTY,
            "--require-project", "PROJECT-A", "--require-task", "T-GOLDEN",
            "--require-red-green-evidence",
            note="查询后目录仍空（WB-10，重放侧按 assert_db_absent 机检）")
    records[-1]["assert_db_absent"] = ".runtime/golden-l01/rt-empty/workbench.db"
    if (REPO_ROOT / RT_EMPTY / "workbench.db").exists():
        raise SystemExit("s21 违反 WB-10：查询偷偷建了库")

    # ---- 白盒篡改 → 摘要复核（F3 先例；放在最后，此后账本不再使用）----------
    conn = sqlite3.connect(REPO_ROOT / RT / "workbench.db")
    conn.execute("UPDATE evidence SET output_text = '篡改后的内容' WHERE record_id = 1")
    conn.commit()
    conn.close()
    capture("s22_status_after_tamper", 1, "workbench-status", "--runtime-dir", RT,
            note="status 每次重算 SHA-256 复核；期望 errors 含 output_digest_mismatch")
    records[-1]["tamper_before"] = {
        "db": ".runtime/golden-l01/rt/workbench.db",
        "sql": "UPDATE evidence SET output_text = '篡改后的内容' WHERE record_id = 1",
        "note": "白盒篡改（F3 先例）：直接 sqlite 改写存储内容，检验读取路径的摘要复核",
    }

    # ---- manifest（确定性内容，不含墙钟）-----------------------------------
    manifest = {
        "generated_from": "frozen Python workbench @ master（见 evidence/L01-java.md §G 记录的 commit）",
        "generator": "tools/generate_golden_l01.py",
        "chain_command_text": CHAIN_COMMAND,
        "normalization": {
            "mask_fields": {"created_at": "<TS>", "recorded_at": "<TS>",
                            "workbench_id": "<WORKBENCH_ID>"},
            "mask_note": "墙钟与 uuid4 为不可复现字段；observed_at 是合同字段，逐字保留",
            "canonical_json": "json.dumps(sort_keys=True, ensure_ascii=False, indent=2) + 换行",
            "non_json": "逐字保留",
        },
        "inputs": {f"golden-l01/inputs/{name}": (INPUTS / name).read_text(encoding="utf-8")
                   for name in ("spec.md", "red.txt", "diff.txt", "green.txt",
                                "post-red.txt", "empty.txt")},
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
