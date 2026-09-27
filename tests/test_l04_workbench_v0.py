"""L04 合同测试：WB-L04-BOOTSTRAP V0 行为面 → 用例映射（Spec = lesson-04-submission/WB-L04-BOOTSTRAP.md）。

映射表（验收项 → 测试名 → 实际操作 → 比较什么）：
C4事前 → test_task_run_code_mode_requires_write_scope → code 模式缺 --write-scope → 拒绝启动，无执行记录
C5     → test_verify_mode_never_launches_executor → 仅复验模式不传执行器命令 → 只跑 Eval，executor_command 为空，绿后停 review
C3/C7  → test_code_run_records_manifest_diff_and_waits_for_review → 替身执行器写集内改动+Eval 过 → change_manifest/Diff/stdout 摘要在场，task_state=review，Eval 在绑定工作目录实跑
C4事后 → test_out_of_scope_stops_before_eval → 替身越界写且退出码 0 → status=out_of_scope，Eval 未运行，越界留证
C6     → test_executor_failure_preserves_output_and_diff → 替身退出码 1 → status=failed，stderr 与 Diff 保留，Eval 未运行
C6     → test_timeout_preserves_partial_output → 替身沉睡超预算 → timed_out=true，部分输出保留，不写成成功
C4事前 → test_invalid_workspace_rejected_before_process → 工作目录不存在 → workspace_invalid，执行器未被调用
C8     → test_review_state_rejects_rerun_and_keeps_state → review 态再次 run → task_in_review，原记录与状态不毁
C8     → test_review_named_decision_self_review_rejected → 执行者自批被拒；他人 approve/reject 落账；无执行记录不能复核
C9     → test_prerequisite_binding_gates_task_creation → 前置任务缺失/未接受 → 拒绝不静默链接；已接受才放行
内核   → test_status_digest_checks_cover_executions → 篡改执行输出 → status 复核报 execution_digest_mismatch，acceptance 恒待人审
C7     → test_task_show_lists_summary_fields → task-show → 修改文件、验证命令、状态、复核记录可查
C8     → test_accepted_task_rerun_invalidates_old_acceptance → 接受后再跑 → 回到 review，旧接受不再覆盖当前版本
复查轮 → test_task_run_requires_explicit_timeout_declaration → code 模式缺 --execution-timeout → execution_timeout_required，拒绝启动（建设合同：三者缺一）
复查轮 → test_workspace_without_start_commit_rejected → 候选无起点提交 → workspace_invalid（能力信封：起点版本）
复查轮 → test_eval_timeout_is_distinguishable_from_eval_failure → eval 沉睡超预算 → status=eval_failed 且 eval_timed_out=true（失败不冒充成功）
复查轮 → test_status_digest_checks_cover_executions（扩展）→ 篡改 change_manifest → execution_digest_mismatch（manifest 自身有摘要）

替身执行器边界：本文件全部用测试控制的脚本进程充当执行器，证明的是 V0 机制
（写集检查、记录保真、状态门），不证明真实 claude -p 行为——后者只在 B 段实跑
出现（讲义 D7，证明边界须写入 A-evidence）。

所有用例经真实 CLI 子进程（sys.executable -X utf8 -m workbench.cli）驱动，
不绕开命令入口直调内部函数；执行记录经 REGISTRY 缝第二批命令产出（§3.D 实现）。
"""

from __future__ import annotations

import json
import shlex
import sqlite3
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

PROMPT_TEXT = "按 Spec 最小改动，只允许写集内文件。\n"
EVAL_OK = "eval: OK\n"


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


def git(workspace: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(workspace), *args],
                          capture_output=True, text=True)


class WorkbenchV0ContractTest(unittest.TestCase):
    """每个用例独立的临时运行目录与临时 git 候选工作区。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.rt = self.tmp / "rt" / "L04-workbench"
        self.workspace = self.make_workspace()

    # ---- 组装辅助 -------------------------------------------------------

    def make_workspace(self) -> Path:
        ws = self.tmp / "candidate"
        ws.mkdir()
        (ws / "src").mkdir()
        (ws / "src" / "keep.txt").write_text("base\n", encoding="utf-8")
        git(ws, "init", "-q")
        git(ws, "add", ".")
        git(ws, "-c", "user.name=t", "-c", "user.email=t@example.com",
            "commit", "-q", "-m", "init")
        return ws

    def write_stub(self, name: str, body: str) -> Path:
        path = self.tmp / name
        path.write_text(textwrap.dedent(body), encoding="utf-8")
        return path

    def in_scope_stub(self) -> Path:
        return self.write_stub("stub_in_scope.py", """
            import sys, pathlib
            prompt = sys.stdin.read()
            pathlib.Path(sys.argv[1]).write_text(prompt, encoding="utf-8")  # 提示词留证（工作区外）
            pathlib.Path(sys.argv[2] + "/src/allowed.txt").write_text("delivered\\n", encoding="utf-8")
        """)

    def eval_probe(self) -> Path:
        """Eval 探针：把实际工作目录写进 marker，供断言“Eval 在绑定候选实跑”。"""
        return self.write_stub("eval_probe.py", """
            import json, os, sys
            with open(sys.argv[1], "w", encoding="utf-8") as stream:
                json.dump({"cwd": os.getcwd()}, stream)
            sys.stdout.write("eval: OK\\n")
        """)

    def eval_fail_probe(self) -> Path:
        return self.write_stub("eval_fail_probe.py", """
            import os, sys
            with open(sys.argv[1], "w", encoding="utf-8") as stream:
                json.dump({"cwd": os.getcwd()}, stream)
            sys.stderr.write("eval: 业务检查失败\\n")
            raise SystemExit(3)
        """.replace("import os, sys", "import json, os, sys"))

    def init_task(self, task_id: str = "T-A", *, with_project: bool = True) -> None:
        self.assertEqual(run_cli("workbench-init", "--runtime-dir", str(self.rt),
                                 "--owner", "tester-A").returncode, 0)
        if with_project:  # 同一运行目录内第二张任务单复用已登记项目（L01：编号已存在则拒绝，不覆盖）
            self.assertEqual(run_cli("workbench-project-add", "--runtime-dir", str(self.rt),
                                     "--project-id", "P-1", "--name", "测试项目").returncode, 0)
        proc = run_cli("workbench-task-create", "--runtime-dir", str(self.rt),
                       "--project-id", "P-1", "--task-id", task_id,
                       "--requirement-id", task_id, "--request", "V0 行为验证",
                       "--actor", "tester-A")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def run_code(self, task_id: str = "T-A", stub: Path | None = None,
                 scope: tuple[str, ...] = ("src",), eval_probe: Path | None = None,
                 timeout: str | None = "900", workspace: Path | None = None,
                 marker: Path | None = None) -> subprocess.CompletedProcess:
        prompt_file = self.tmp / "prompt.txt"
        prompt_file.write_text(PROMPT_TEXT, encoding="utf-8")
        marker = marker or self.tmp / f"marker-{task_id}.json"
        stub_args = [str(stub or self.in_scope_stub()),
                     str(self.tmp / "prompt-capture.txt"), str(workspace or self.workspace)]
        args = ["workbench-task-run", task_id,
                "--runtime-dir", str(self.rt),
                "--workspace", str(workspace or self.workspace),
                "--mode", "code",
                "--write-scope", *scope,
                # shell 形命令串（shlex 解析）：选项形 token（如 -X/-p）不会被误当旗标
                "--executor-command", shlex.join([sys.executable, *stub_args]),
                "--executor-prompt-file", str(prompt_file),
                "--eval-command", shlex.join([sys.executable, str(eval_probe or self.eval_probe()), str(marker)]),
                "--actor", "tester-A"]
        if timeout is not None:  # None = 故意缺声明，验证预算门
            args += ["--execution-timeout", timeout]
        return run_cli(*args)

    def approve(self, task_id: str = "T-A", reviewer: str = "reviewer-B",
                decision: str = "approve") -> subprocess.CompletedProcess:
        return run_cli("workbench-task-review", task_id, "--runtime-dir", str(self.rt),
                       "--reviewer", reviewer, "--decision", decision,
                       "--note", "按清单核对")

    # ---- 用例 -----------------------------------------------------------

    def test_task_run_code_mode_requires_write_scope(self) -> None:
        self.init_task()
        prompt_file = self.tmp / "prompt.txt"
        prompt_file.write_text(PROMPT_TEXT, encoding="utf-8")
        proc = run_cli("workbench-task-run", "T-A", "--runtime-dir", str(self.rt),
                       "--workspace", str(self.workspace), "--mode", "code",
                       "--executor-command",
                       shlex.join([sys.executable, str(self.in_scope_stub()),
                                   str(self.tmp / "c.txt"), str(self.workspace)]),
                       "--eval-command",
                       shlex.join([sys.executable, str(self.eval_probe()),
                                   str(self.tmp / "m.json")]),
                       "--actor", "tester-A")
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        self.assertIn("write_scope_required", proc.stdout)
        show = payload(run_cli("workbench-task-show", "T-A", "--runtime-dir", str(self.rt)))
        self.assertEqual(show.get("task", {}).get("state"), "no_execution")

    def test_verify_mode_never_launches_executor(self) -> None:
        self.init_task()
        marker = self.tmp / "marker-verify.json"
        proc = run_cli("workbench-task-run", "T-A", "--runtime-dir", str(self.rt),
                       "--workspace", str(self.workspace), "--mode", "verify",
                       "--eval-command", shlex.join([sys.executable, str(self.eval_probe()), str(marker)]),
                       "--execution-timeout", "900", "--actor", "tester-A")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        record = payload(proc)["execution"]
        self.assertEqual(record["mode"], "verify")
        self.assertIsNone(record["executor_command"])
        data = json.loads(marker.read_text(encoding="utf-8"))
        self.assertEqual(data["cwd"], str(self.workspace.resolve()))
        self.assertEqual(payload(proc)["task_state"], "review")  # 仅复验的绿也停人审

    def test_code_run_records_manifest_diff_and_waits_for_review(self) -> None:
        self.init_task()
        marker = self.tmp / "marker-code.json"
        proc = self.run_code(eval_probe=self.eval_probe(), marker=marker)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        body = payload(proc)
        record = body["execution"]
        self.assertEqual(record["returncode"], 0)
        self.assertFalse(record["timed_out"])
        self.assertEqual(record["changed_files"], ["src/allowed.txt"])
        self.assertEqual(record["out_of_scope_files"], [])
        manifest = record["change_manifest"]
        self.assertEqual(manifest[0]["path"], "src/allowed.txt")
        self.assertIsNone(manifest[0]["before_sha256"])
        self.assertTrue(manifest[0]["after_sha256"])
        self.assertIn("allowed", record["diff_text"])
        self.assertTrue(record["stdout_sha256"])
        capture = self.tmp / "prompt-capture.txt"
        self.assertEqual(capture.read_text(encoding="utf-8"), PROMPT_TEXT)  # 提示词经 stdin 送达
        marker_data = json.loads(marker.read_text(encoding="utf-8"))
        self.assertEqual(marker_data["cwd"], str(self.workspace.resolve()))  # Eval 绑定候选工作目录
        self.assertEqual(body["task_state"], "review")  # eval 过也停人工复核

    def test_out_of_scope_stops_before_eval(self) -> None:
        self.init_task()
        rogue = self.write_stub("stub_out_of_scope.py", """
            import sys, pathlib
            sys.stdin.read()
            nested = pathlib.Path(sys.argv[2] + "/rogue_dir")
            nested.mkdir()
            (nested / "nested.txt").write_text("越界\\n", encoding="utf-8")
        """)
        marker = self.tmp / "marker-rogue.json"
        proc = self.run_code(stub=rogue)
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        body = payload(proc)
        self.assertEqual(body["execution"]["status"], "out_of_scope")
        self.assertIn("rogue_dir/nested.txt", body["execution"]["out_of_scope_files"])  # 新目录不折叠
        self.assertFalse(marker.exists())  # 越界在 eval 前停止
        self.assertEqual(body["task_state"], "out_of_scope")

    def test_executor_failure_preserves_output_and_diff(self) -> None:
        self.init_task()
        failing = self.write_stub("stub_fail.py", """
            import sys, pathlib
            sys.stdin.read()
            pathlib.Path(sys.argv[2] + "/src/allowed.txt").write_text("写了一半\\n", encoding="utf-8")
            sys.stderr.write("工具失败：无法继续\\n")
            raise SystemExit(4)
        """)
        marker = self.tmp / "marker-fail.json"
        proc = self.run_code(stub=failing)
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        body = payload(proc)
        record = body["execution"]
        self.assertEqual(record["status"], "failed")
        self.assertEqual(record["returncode"], 4)
        self.assertIn("工具失败", record["stderr_text"])  # 输出保留
        self.assertIn("src/allowed.txt", record["changed_files"])  # 已发生改动留证，不回滚不隐瞒
        self.assertFalse(marker.exists())
        self.assertEqual(body["task_state"], "failed")  # 失败不冒充成功

    def test_timeout_preserves_partial_output(self) -> None:
        self.init_task()
        sleeping = self.write_stub("stub_sleep.py", """
            import sys, time
            sys.stdin.read()
            sys.stdout.write("已完成前半段\\n", )
            sys.stdout.flush()
            time.sleep(30)
        """)
        marker = self.tmp / "marker-timeout.json"
        proc = self.run_code(stub=sleeping, timeout="1")
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        body = payload(proc)
        record = body["execution"]
        self.assertEqual(record["status"], "timeout")
        self.assertTrue(record["timed_out"])
        self.assertIn("前半段", record["stdout_text"])  # 部分输出保留
        self.assertFalse(marker.exists())
        self.assertEqual(body["task_state"], "timeout")

    def test_invalid_workspace_rejected_before_process(self) -> None:
        self.init_task()
        proc = self.run_code(workspace=self.tmp / "no-such-ws")
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        self.assertIn("workspace_invalid", proc.stdout)
        show = payload(run_cli("workbench-task-show", "T-A", "--runtime-dir", str(self.rt)))
        self.assertEqual(show.get("task", {}).get("state"), "no_execution")

    def test_review_state_rejects_rerun_and_keeps_state(self) -> None:
        self.init_task()
        first = self.run_code()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        again = self.run_code()
        self.assertEqual(again.returncode, 1, again.stdout + again.stderr)
        self.assertIn("task_in_review", again.stdout)
        show = payload(run_cli("workbench-task-show", "T-A", "--runtime-dir", str(self.rt)))
        self.assertEqual(show["task"]["state"], "review")  # 待审状态不被重跑摧毁
        self.assertEqual(len(show["task"]["executions"]), 1)  # 旧记录保留，无第二条执行

    def test_review_named_decision_self_review_rejected(self) -> None:
        self.init_task()
        none = self.approve()
        self.assertEqual(none.returncode, 1)
        self.assertIn("no_execution_to_review", none.stdout)
        first = self.run_code()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        self_review = self.approve(reviewer="tester-A")  # 与最近一次执行者同人
        self.assertEqual(self_review.returncode, 1, self_review.stdout + self_review.stderr)
        self.assertIn("self_review_rejected", self_review.stdout)
        ok = self.approve(reviewer="reviewer-B", decision="approve")
        self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)
        show = payload(run_cli("workbench-task-show", "T-A", "--runtime-dir", str(self.rt)))
        self.assertEqual(show["task"]["state"], "accepted")
        self.assertEqual(show["task"]["reviews"][-1]["reviewer"], "reviewer-B")
        # 打回路径：另一任务 reject 后允许返工（review 态才可复核，reject 落账）
        self.init_task("T-B", with_project=False)
        self.run_code("T-B")
        reject = self.approve("T-B", reviewer="reviewer-B", decision="reject")
        self.assertEqual(reject.returncode, 0, reject.stdout + reject.stderr)
        show_b = payload(run_cli("workbench-task-show", "T-B", "--runtime-dir", str(self.rt)))
        self.assertEqual(show_b["task"]["state"], "rejected")

    def test_prerequisite_binding_gates_task_creation(self) -> None:
        self.init_task()
        missing = run_cli("workbench-task-create", "--runtime-dir", str(self.rt),
                          "--project-id", "P-1", "--task-id", "T-B1",
                          "--request", "B", "--actor", "tester-A",
                          "--prerequisite-task", "T-GHOST")
        self.assertEqual(missing.returncode, 1)
        self.assertIn("prerequisite_task_missing", missing.stdout)
        unaccepted = run_cli("workbench-task-create", "--runtime-dir", str(self.rt),
                             "--project-id", "P-1", "--task-id", "T-B2",
                             "--request", "B", "--actor", "tester-A",
                             "--prerequisite-task", "T-A")
        self.assertEqual(unaccepted.returncode, 1)
        self.assertIn("prerequisite_not_accepted", unaccepted.stdout)
        # T-A 走完执行 + 具名接受后，前置绑定放行
        self.assertEqual(self.run_code().returncode, 0)
        self.assertEqual(self.approve().returncode, 0)
        accepted = run_cli("workbench-task-create", "--runtime-dir", str(self.rt),
                           "--project-id", "P-1", "--task-id", "T-B3",
                           "--request", "B", "--actor", "tester-A",
                           "--prerequisite-task", "T-A")
        self.assertEqual(accepted.returncode, 0, accepted.stdout + accepted.stderr)
        self.assertEqual(payload(accepted)["task"]["prerequisite_task_id"], "T-A")

    def test_status_digest_checks_cover_executions(self) -> None:
        self.init_task()
        proc = self.run_code()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        db = self.rt / "workbench.db"
        conn = sqlite3.connect(db)
        conn.execute("UPDATE executions SET stdout_text = '被篡改的输出'")  # 模拟账本被手改
        conn.execute("UPDATE executions SET change_manifest = '[]'")  # manifest 本身也有摘要（复查轮）
        conn.commit()
        conn.close()
        status = run_cli("workbench-status", "--runtime-dir", str(self.rt),
                         "--require-project", "P-1")
        self.assertEqual(status.returncode, 1)
        body = payload(status)
        self.assertTrue(any("execution_digest_mismatch" in e for e in body["errors"]))
        self.assertTrue(any("change_manifest" in e for e in body["errors"]))
        self.assertEqual(body["acceptance"], "pending_human_review")  # 恒待人签

    def test_task_show_lists_summary_fields(self) -> None:
        self.init_task()
        self.assertEqual(self.run_code().returncode, 0)
        self.assertEqual(self.approve().returncode, 0)
        show = payload(run_cli("workbench-task-show", "T-A", "--runtime-dir", str(self.rt)))
        task = show["task"]
        self.assertEqual(task["state"], "accepted")
        record = task["executions"][-1]
        self.assertEqual(record["changed_files"], ["src/allowed.txt"])  # 摘要：修改文件
        self.assertIn("eval_probe.py", " ".join(record["eval_command"]))  # 摘要：验证命令
        self.assertEqual(record["eval_returncode"], 0)
        self.assertEqual(task["reviews"][-1]["decision"], "approve")  # 摘要：复核决定

    def test_accepted_task_rerun_invalidates_old_acceptance(self) -> None:
        self.init_task()
        self.assertEqual(self.run_code().returncode, 0)
        self.assertEqual(self.approve().returncode, 0)
        rework = self.run_code()
        self.assertEqual(rework.returncode, 0, rework.stdout + rework.stderr)
        body = payload(rework)
        self.assertEqual(body["task_state"], "review")  # 新执行回到待审，旧接受失效
        show = payload(run_cli("workbench-task-show", "T-A", "--runtime-dir", str(self.rt)))
        self.assertEqual(len(show["task"]["executions"]), 2)  # 两轮执行记录全保留

    # ---- 复查轮（code-review 发现，test-first）--------------------------

    def test_task_run_requires_explicit_timeout_declaration(self) -> None:
        self.init_task()
        proc = self.run_code(timeout=None)  # 故意缺预算声明
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        self.assertIn("execution_timeout_required", proc.stdout)  # 建设合同：三者缺一拒绝启动
        show = payload(run_cli("workbench-task-show", "T-A", "--runtime-dir", str(self.rt)))
        self.assertEqual(show.get("task", {}).get("state"), "no_execution")

    def test_workspace_without_start_commit_rejected(self) -> None:
        self.init_task()
        bare = self.tmp / "candidate-no-commit"
        (bare / "src").mkdir(parents=True)
        git(bare, "init", "-q")  # 有 .git 无起点提交
        proc = self.run_code(workspace=bare)
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        self.assertIn("workspace_invalid", proc.stdout)  # 能力信封要求起点版本
        show = payload(run_cli("workbench-task-show", "T-A", "--runtime-dir", str(self.rt)))
        self.assertEqual(show.get("task", {}).get("state"), "no_execution")

    def test_eval_timeout_is_distinguishable_from_eval_failure(self) -> None:
        self.init_task()
        slow_eval = self.write_stub("eval_slow_probe.py", """
            import sys, time
            time.sleep(30)
        """)
        marker = self.tmp / "marker-slow-eval.json"
        proc = self.run_code(eval_probe=slow_eval, timeout="1")
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        record = payload(proc)["execution"]
        self.assertEqual(record["status"], "eval_failed")
        self.assertTrue(record["eval_timed_out"])  # 超时与业务失败可分辨，不冒充
        self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
