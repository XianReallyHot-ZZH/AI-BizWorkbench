"""L01 合同测试：WORKBENCH_SPEC WB-01..WB-12 → 用例映射（Spec = docs/lessons/L01-工作台自举.md §2）。

映射表（验收项 → 测试名 → 实际操作 → 比较什么）：
WB-01 → test_wb01_init_persists_and_cannot_be_overwritten → 独立运行目录 init、新进程查询、另一 owner 再 init → 身份跨进程可查回；拒绝覆盖
WB-02 → test_wb02_project_and_task_with_snapshots → 登记项目、创建任务并附 spec/problem 文件 → 任务归属项目，快照与原文件内容一致
WB-03 → test_wb03_task_requires_registered_project → 对未登记项目创建任务 → 失败且不产生无所属任务
WB-04 → test_wb04_duplicate_task_id_rejected → 同编号任务提交不同内容 → 第二次失败，原任务与其记录不变
WB-05 → test_wb05_snapshots_survive_source_changes → 创建/导入后修改原文件 → 已保存快照保持当时内容
WB-06 → test_wb06_missing_chain_reported → 无证据任务要求完整性 → 报缺链，任务与已有记录保留
WB-07 → test_wb07_same_command_red_diff_green_chain → 同一命令红/Diff/绿按时间追加 → evidence_complete 且仍待人审
WB-08 → test_wb08_different_command_breaks_chain → 绿改用不同命令 → 不构成同命令链，三条记录全保留
WB-09 → test_wb09_ordering_and_stale_green_rejected → 绿早于红；绿后又失败 → 均判不完整
WB-10 → test_wb10_queries_never_create_data → 查缺失任务 / 未初始化目录 → 明确失败，不偷偷建数据
WB-11 → test_wb11_recovery_after_supplement → 补齐缺失记录后重查 → 可恢复，旧失败仍可查
WB-12 → test_wb12_isolated_runtime_and_no_flowerp → 检查数据目录与字段 → 运行库独立，flowerp_connected 为 false
C6   → test_evidence_add_rejects_naive_time_and_empty_output → 缺时区时间 / 空输出 → 追加失败，旧记录保留

所有用例经真实 CLI 子进程（sys.executable -X utf8 -m workbench.cli）驱动，
不绕开命令入口直调内部函数。
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

SPEC_TEXT_V1 = "# 需求 V1\n\n查回任务当时依据的要求。\n"
SPEC_TEXT_V2 = "# 需求 V2\n\n内容已被改写，不应影响已建任务。\n"
PROBLEM_TEXT = "# 原始问题\n\n隔天继续开发，却找不到依据。\n"
OUTPUT_TEXT_V1 = "10:01 运行输出：3 项失败\n"
OUTPUT_TEXT_V2 = "输出文件事后被改写，不应影响已导入记录。\n"
CHAIN_COMMAND = "python -X utf8 -m unittest discover -s tests -v -p 'test_l01_*.py'"


def run_cli(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-X", "utf8", "-m", "workbench.cli", *args],
        cwd=str(REPO_ROOT), capture_output=True, text=True,
    )


def payload(proc: subprocess.CompletedProcess) -> dict:
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {}


class WorkbenchBootstrapContractTest(unittest.TestCase):
    """每个用例独立的临时运行目录；db 路径固定为 <runtime-dir>/workbench.db。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.rt = self.tmp / "rt" / "L01-workbench"

    # ---- 组装辅助 -------------------------------------------------------

    def init_workbench(self, owner: str = "tester-A") -> subprocess.CompletedProcess:
        return run_cli("workbench-init", "--runtime-dir", str(self.rt),
                       "--name", "合同测试工作台", "--owner", owner)

    def add_project(self, project_id: str = "PROJECT-A") -> subprocess.CompletedProcess:
        return run_cli("workbench-project-add", "--runtime-dir", str(self.rt),
                       "--project-id", project_id, "--name", "合同测试项目",
                       "--path", ".", "--purpose", "合同验证")

    def create_task(self, task_id: str = "T-1", request: str = "第一版请求",
                    spec: Path | None = None, problem: Path | None = None,
                    project_id: str = "PROJECT-A") -> subprocess.CompletedProcess:
        args = ["workbench-task-create", "--runtime-dir", str(self.rt),
                "--project-id", project_id, "--task-id", task_id,
                "--requirement-id", task_id, "--request", request,
                "--actor", "tester-A"]
        if spec is not None:
            args += ["--spec-file", str(spec)]
        if problem is not None:
            args += ["--problem-file", str(problem)]
        return run_cli(*args)

    def add_evidence(self, task_id: str, phase: str, command: str,
                     output_file: Path, returncode: int, observed_at: str) -> subprocess.CompletedProcess:
        return run_cli("workbench-evidence-add", "--runtime-dir", str(self.rt),
                       "--task-id", task_id, "--phase", phase, "--command-text", command,
                       "--output-file", str(output_file), "--returncode", str(returncode),
                       "--observed-at", observed_at)

    def require_status(self, task_id: str = "T-1", project_id: str = "PROJECT-A",
                       require_evidence: bool = True) -> subprocess.CompletedProcess:
        args = ["workbench-status", "--runtime-dir", str(self.rt),
                "--require-project", project_id, "--require-task", task_id]
        if require_evidence:
            args.append("--require-red-green-evidence")
        return run_cli(*args)

    def status(self) -> subprocess.CompletedProcess:
        return run_cli("workbench-status", "--runtime-dir", str(self.rt))

    def write_output(self, name: str, text: str) -> Path:
        path = self.tmp / name
        path.write_text(text, encoding="utf-8")
        return path

    def task_evidence(self, proc: subprocess.CompletedProcess, task_id: str = "T-1") -> list[dict]:
        data = payload(proc)
        for project in data.get("projects", []):
            for task in project.get("tasks", []):
                if task.get("task_id") == task_id:
                    return task.get("evidence", [])
        return []

    # ---- WB-01..WB-12 ---------------------------------------------------

    def test_wb01_init_persists_and_cannot_be_overwritten(self):
        proc = self.init_workbench()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        first = payload(proc)
        self.assertEqual(first["workbench"]["owner"], "tester-A")
        self.assertEqual(first["workbench"]["version"], "V0.1")

        # 进程已结束：新进程查询同一运行目录，身份仍在
        again = self.status()
        self.assertEqual(again.returncode, 0, again.stdout + again.stderr)
        self.assertEqual(payload(again)["workbench"]["owner"], "tester-A")
        self.assertEqual(payload(again)["workbench"]["name"], "合同测试工作台")

        # 另一 owner 再 init：拒绝，不覆盖
        clash = self.init_workbench(owner="tester-B")
        self.assertNotEqual(clash.returncode, 0)
        reread = payload(self.status())
        self.assertEqual(reread["workbench"]["owner"], "tester-A")

    def test_wb02_project_and_task_with_snapshots(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        spec = self.tmp / "spec.md"
        spec.write_text(SPEC_TEXT_V1, encoding="utf-8")
        problem = self.tmp / "problem.md"
        problem.write_text(PROBLEM_TEXT, encoding="utf-8")
        proc = self.create_task(spec=spec, problem=problem)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

        # WB-02 只验收归属与快照，不要求证据链（修订：初版误带 --require-red-green-evidence）
        check = self.require_status(require_evidence=False)
        self.assertEqual(check.returncode, 0, check.stdout + check.stderr)
        data = payload(check)
        self.assertTrue(data.get("ok"))
        projects = {p["project_id"]: p for p in data.get("projects", [])}
        self.assertIn("PROJECT-A", projects)
        tasks = {t["task_id"]: t for t in projects["PROJECT-A"]["tasks"]}
        self.assertIn("T-1", tasks)
        self.assertEqual(tasks["T-1"]["requirement_snapshot"], SPEC_TEXT_V1)
        self.assertEqual(tasks["T-1"]["problem_snapshot"], PROBLEM_TEXT)
        self.assertTrue(tasks["T-1"].get("requirement_summary"))

    def test_wb03_task_requires_registered_project(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        proc = self.create_task(task_id="GHOST-T1", project_id="GHOST-PROJECT")
        self.assertNotEqual(proc.returncode, 0)
        data = payload(self.status())
        all_tasks = [t for p in data.get("projects", []) for t in p.get("tasks", [])]
        self.assertNotIn("GHOST-T1", {t.get("task_id") for t in all_tasks})

    def test_wb04_duplicate_task_id_rejected(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task(request="第一版请求").returncode, 0)
        clash = self.create_task(request="换掉第一版的不同内容")
        self.assertNotEqual(clash.returncode, 0)
        data = payload(self.status())
        tasks = {t["task_id"]: t for p in data.get("projects", []) for t in p.get("tasks", [])}
        self.assertEqual(tasks["T-1"]["request"], "第一版请求")

    def test_wb05_snapshots_survive_source_changes(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        spec = self.tmp / "spec.md"
        spec.write_text(SPEC_TEXT_V1, encoding="utf-8")
        self.assertEqual(self.create_task(spec=spec).returncode, 0)
        # 原需求文件事后变化
        spec.write_text(SPEC_TEXT_V2, encoding="utf-8")

        output = self.write_output("out.txt", OUTPUT_TEXT_V1)
        self.assertEqual(self.add_evidence("T-1", "observation", "echo v1", output,
                                           0, "2026-09-25T10:00:00+08:00").returncode, 0)
        # 原输出文件事后变化
        output.write_text(OUTPUT_TEXT_V2, encoding="utf-8")

        data = payload(self.status())
        tasks = {t["task_id"]: t for p in data.get("projects", []) for t in p.get("tasks", [])}
        self.assertEqual(tasks["T-1"]["requirement_snapshot"], SPEC_TEXT_V1)
        evidence = self.task_evidence(self.status())
        self.assertEqual(len(evidence), 1)
        self.assertEqual(evidence[0]["output_text"], OUTPUT_TEXT_V1)

    def test_wb06_missing_chain_reported(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        proc = self.require_status()
        self.assertNotEqual(proc.returncode, 0)
        data = payload(proc)
        self.assertFalse(data.get("evidence_complete"))
        self.assertIn("same_command_red_diff_green_missing", data.get("error", "") + json.dumps(data, ensure_ascii=False))
        # 任务与项目仍保留
        plain = payload(self.status())
        self.assertTrue(plain.get("ok"))
        self.assertIn("T-1", {t["task_id"] for p in plain.get("projects", []) for t in p.get("tasks", [])})

    def test_wb07_same_command_red_diff_green_chain(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        outputs = [self.write_output(f"{phase}.txt", f"{phase} output\n")
                   for phase in ("red", "diff", "green")]
        self.assertEqual(self.add_evidence("T-1", "red", CHAIN_COMMAND, outputs[0], 1,
                                           "2026-09-25T10:00:01+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "diff", CHAIN_COMMAND, outputs[1], 0,
                                           "2026-09-25T10:00:02+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "green", CHAIN_COMMAND, outputs[2], 0,
                                           "2026-09-25T10:00:03+08:00").returncode, 0)
        proc = self.require_status()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        data = payload(proc)
        self.assertTrue(data.get("ok"))
        self.assertTrue(data.get("evidence_complete"))
        self.assertEqual(data.get("acceptance"), "pending_human_review")
        self.assertFalse(data.get("flowerp_connected"))

    def test_wb08_different_command_breaks_chain(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        other_command = CHAIN_COMMAND + " --broken-variant"
        self.assertEqual(self.add_evidence("T-1", "red", CHAIN_COMMAND,
                                           self.write_output("r.txt", "r\n"), 1,
                                           "2026-09-25T10:00:01+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "diff", CHAIN_COMMAND,
                                           self.write_output("d.txt", "d\n"), 0,
                                           "2026-09-25T10:00:02+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "green", other_command,
                                           self.write_output("g.txt", "g\n"), 0,
                                           "2026-09-25T10:00:03+08:00").returncode, 0)
        proc = self.require_status()
        self.assertNotEqual(proc.returncode, 0)
        data = payload(proc)
        self.assertFalse(data.get("evidence_complete"))
        self.assertIn("same_command_red_diff_green_missing", data.get("error", ""))
        # 三条记录全保留，不因判缺而删除
        self.assertEqual(len(self.task_evidence(self.status())), 3)

    def test_wb09_ordering_and_stale_green_rejected(self):
        # 情形 A：绿灯时间早于红灯
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "red", CHAIN_COMMAND,
                                           self.write_output("a-r.txt", "r\n"), 1,
                                           "2026-09-25T10:00:02+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "diff", CHAIN_COMMAND,
                                           self.write_output("a-d.txt", "d\n"), 0,
                                           "2026-09-25T10:00:03+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "green", CHAIN_COMMAND,
                                           self.write_output("a-g.txt", "g\n"), 0,
                                           "2026-09-25T10:00:01+08:00").returncode, 0)
        self.assertNotEqual(self.require_status().returncode, 0)

        # 情形 B：成功后又出现失败，旧绿灯不再说明当前完整
        rt_b = self.tmp / "rt-b"
        self.rt = rt_b
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "red", CHAIN_COMMAND,
                                           self.write_output("b-r.txt", "r\n"), 1,
                                           "2026-09-25T11:00:01+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "diff", CHAIN_COMMAND,
                                           self.write_output("b-d.txt", "d\n"), 0,
                                           "2026-09-25T11:00:02+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "green", CHAIN_COMMAND,
                                           self.write_output("b-g.txt", "g\n"), 0,
                                           "2026-09-25T11:00:03+08:00").returncode, 0)
        self.assertEqual(self.require_status().returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "red", CHAIN_COMMAND,
                                           self.write_output("b-r2.txt", "r2\n"), 1,
                                           "2026-09-25T11:00:04+08:00").returncode, 0)
        proc = self.require_status()
        self.assertNotEqual(proc.returncode, 0)
        self.assertFalse(payload(proc).get("evidence_complete"))

    def test_wb10_queries_never_create_data(self):
        # 未初始化目录：明确失败，且不偷偷建库
        proc = self.require_status()
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("工作台尚未初始化", payload(proc).get("error", ""))
        self.assertFalse((self.rt / "workbench.db").exists())

        # 初始化后查不存在的任务：报缺失，不改写其他任务
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        missing = self.require_status(task_id="CASE-WB-L01-MISSING")
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("required_task_missing", payload(missing).get("error", ""))
        data = payload(self.status())
        task_ids = {t["task_id"] for p in data.get("projects", []) for t in p.get("tasks", [])}
        self.assertEqual(task_ids, {"T-1"})

    def test_wb11_recovery_after_supplement(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        red_out = self.write_output("r.txt", "red output\n")
        self.assertEqual(self.add_evidence("T-1", "red", CHAIN_COMMAND, red_out, 1,
                                           "2026-09-25T10:00:01+08:00").returncode, 0)
        self.assertNotEqual(self.require_status().returncode, 0)
        # 补齐 Diff 与绿
        self.assertEqual(self.add_evidence("T-1", "diff", CHAIN_COMMAND,
                                           self.write_output("d.txt", "d\n"), 0,
                                           "2026-09-25T10:00:02+08:00").returncode, 0)
        self.assertEqual(self.add_evidence("T-1", "green", CHAIN_COMMAND,
                                           self.write_output("g.txt", "g\n"), 0,
                                           "2026-09-25T10:00:03+08:00").returncode, 0)
        self.assertEqual(self.require_status().returncode, 0)
        # 旧失败仍可查，未被删除来恢复
        evidence = self.task_evidence(self.status())
        reds = [item for item in evidence if item["phase"] == "red"]
        self.assertEqual(len(reds), 1)
        self.assertEqual(reds[0]["returncode"], 1)

    def test_wb12_isolated_runtime_and_no_flowerp(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        data = payload(self.status())
        self.assertFalse(data.get("flowerp_connected"))
        self.assertTrue((self.rt / "workbench.db").exists())
        # 运行数据只落指定目录，不外溢仓库根
        siblings = {p.name for p in (self.tmp / "rt").iterdir()}
        self.assertEqual(siblings, {"L01-workbench"})

    # ---- C6 记录质量 -----------------------------------------------------

    def test_evidence_add_rejects_naive_time_and_empty_output(self):
        self.assertEqual(self.init_workbench().returncode, 0)
        self.assertEqual(self.add_project().returncode, 0)
        self.assertEqual(self.create_task().returncode, 0)
        naive = self.add_evidence("T-1", "observation", "echo x",
                                  self.write_output("n.txt", "x\n"), 0,
                                  "2026-09-25T10:00:00")  # 缺时区
        self.assertNotEqual(naive.returncode, 0)
        empty = self.write_output("empty.txt", "")
        missing_out = self.add_evidence("T-1", "observation", "echo y", empty, 0,
                                        "2026-09-25T10:00:01+08:00")
        self.assertNotEqual(missing_out.returncode, 0)
        # 失败后原任务仍无被污染的记录
        self.assertEqual(self.task_evidence(self.status()), [])


if __name__ == "__main__":
    unittest.main()
