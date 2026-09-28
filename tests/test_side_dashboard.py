"""支线合同测试：工作台看板——验收人的观察窗（Spec = docs/lessons/支线-工作台看板.md §2，定稿 2026-09-28）。

映射表（验收项 → 测试名 → 实际操作 → 比较什么）：
C1     → test_read_only_get_only_and_ledger_bytes_unchanged → 起停服务 + GET 四屏 + POST/PUT/DELETE → 账本文件 sha256 前后不变；写方法一律 405
C2     → test_data_fidelity_matches_sqlite_truth → 种子账后取 ?format=json → 任务数/相位/acceptance/evidence_complete 与 sqlite 直查逐一一致
C3     → test_missing_runtime_dir_fails_with_json_contract → runtime-dir 不存在 → JSON 错误契约（工作台尚未初始化词面），不起服务
C3     → test_non_ledger_file_fails_with_json_contract → 非本工作台账本文件 → 同一 JSON 错误契约
C3     → test_old_ledger_missing_tables_degrade_to_empty → 老账本缺 V0/记忆表 → 对应屏降级为空，HTTP 200 不裸崩
C4     → test_screen_tasks_chain_shows_phases_and_anchor → 红绿链任务锚定完整 + 断链任务如实报 missing 词面
C4     → test_screen_executions_and_reviews → 执行记录与具名复核入屏；任务状态 accepted
C4     → test_screen_memory_assets → S01 记忆五表真数据入屏（candidate 资产 + 事件）
C5     → test_stdlib_only_imports → dashboard.py 顶层 imports ⊆ 标准库白名单 + workbench 内部模块
C6     → test_binds_loopback_and_explicit_port → --help 有 --port 无 --host；源码绑 127.0.0.1 且无 0.0.0.0

被测缝（实现须与此一致，D1–D5 裁决）：
- workbench-dashboard --runtime-dir DIR --port N（REGISTRY 注册缝；--port 显式必填，无 --host，恒绑 127.0.0.1）
- GET /            → 服务端渲染单页 HTML（四屏：任务与证据链／执行与复核／记忆资产／身份与判定）
- GET /?format=json → 同一数据 dict 的 JSON（零 POST、零写端点、零验收按钮）
- 写方法（POST/PUT/DELETE/PATCH）→ 405 + JSON 错误体；未知路径 → 404 + JSON 错误体
- 只读三层：sqlite mode=ro URI + 仅 GET 路由 + 起停前后账本 sha256 自检（本文件 C1 用例）

口径：全部用例经真实 CLI 子进程（sys.executable -X utf8 -m workbench.cli）驱动，
服务用真实 HTTP（http.client）访问 127.0.0.1；不绕开命令入口直调内部函数；
每个用例独立的临时运行目录。种子组装复用 test_side_memory 先例（替身执行器 +
L04 执行/复核机制 + S01 记忆七命令），不引入第三方便利。
"""

from __future__ import annotations

import ast
import datetime
import hashlib
import http.client
import json
import shlex
import socket
import sqlite3
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

PROMPT_TEXT = "按 Spec 最小改动，只允许写集内文件。\n"
OWNER_A = "tester-A"
REVIEWER_B = "reviewer-B"
CHAIN_CMD = "python -m tests.fake_probe"
# C5：dashboard.py 顶层 imports 白名单（标准库）+ 相对导入（workbench 内部）。
STDLIB_WHITELIST = {
    "__future__", "argparse", "datetime", "html", "http", "json",
    "pathlib", "sqlite3", "sys", "urllib",
}


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


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def http_request(method: str, port: int, target: str) -> tuple[int, str]:
    """真实 HTTP 访问看板；4xx/5xx 也返回 (status, body)，不抛异常。"""
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    try:
        conn.request(method, target)
        response = conn.getresponse()
        return response.status, response.read().decode("utf-8", errors="replace")
    finally:
        conn.close()


class DashboardContractTest(unittest.TestCase):
    """每个用例独立的临时运行目录与临时 git 候选工作区。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.rt = self.tmp / "rt" / "S02-dashboard"

    # ---- 服务起停辅助 ----------------------------------------------------

    def start_dashboard(self, port: int | None = None) -> subprocess.Popen:
        port = port if port is not None else free_port()
        proc = subprocess.Popen(
            [sys.executable, "-X", "utf8", "-m", "workbench.cli", "workbench-dashboard",
             "--runtime-dir", str(self.rt), "--port", str(port)],
            cwd=str(REPO_ROOT), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            with socket.socket() as probe:
                probe.settimeout(0.5)
                if probe.connect_ex(("127.0.0.1", port)) == 0:
                    self.addCleanup(self.stop_dashboard, proc)
                    return proc
            if proc.poll() is not None:
                break
            time.sleep(0.05)
        out, err = proc.communicate(timeout=10)
        self.fail(f"看板服务未就绪（rc={proc.returncode}）\nstdout={out}\nstderr={err}")

    @staticmethod
    def stop_dashboard(proc: subprocess.Popen) -> None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)

    def get_json(self, port: int) -> dict:
        status, body = http_request("GET", port, "/?format=json")
        self.assertEqual(status, 200, body)
        return json.loads(body)

    # ---- 种子组装辅助（同 test_side_memory 先例）--------------------------

    def init_workbench(self) -> None:
        self.assertEqual(run_cli("workbench-init", "--runtime-dir", str(self.rt),
                                 "--owner", OWNER_A).returncode, 0)
        self.assertEqual(run_cli("workbench-project-add", "--runtime-dir", str(self.rt),
                                 "--project-id", "P-1", "--name", "测试项目").returncode, 0)

    def plain_task(self, task_id: str) -> None:
        create = run_cli("workbench-task-create", "--runtime-dir", str(self.rt),
                         "--project-id", "P-1", "--task-id", task_id,
                         "--requirement-id", task_id, "--request", "种子任务", "--actor", OWNER_A)
        self.assertEqual(create.returncode, 0, create.stdout + create.stderr)

    def chain_task(self, task_id: str, *, complete: bool = True) -> None:
        """种子一条证据链：complete=True 为红—Diff—绿全链，False 只留红（断链）。"""
        self.plain_task(task_id)
        base = datetime.datetime.now(datetime.timezone.utc)
        legs = (("red", 1), ("diff", 0), ("green", 0)) if complete else (("red", 1),)
        for index, (phase, returncode) in enumerate(legs):
            output = self.tmp / f"{task_id}-{phase}.txt"
            output.write_text(f"{task_id} {phase} stub output\n", encoding="utf-8")
            observed = (base + datetime.timedelta(seconds=index)).isoformat()
            added = run_cli("workbench-evidence-add", "--runtime-dir", str(self.rt),
                            "--task-id", task_id, "--phase", phase,
                            "--command-text", CHAIN_CMD, "--output-file", str(output),
                            "--returncode", str(returncode), "--observed-at", observed)
            self.assertEqual(added.returncode, 0, added.stdout + added.stderr)

    def make_workspace(self, name: str) -> Path:
        ws = self.tmp / name
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
            sys.stdin.read()
            pathlib.Path(sys.argv[1] + "/src/done.txt").write_text("delivered\\n", encoding="utf-8")
        """)

    def eval_probe(self) -> Path:
        return self.write_stub("eval_probe.py", """
            import sys
            sys.stdout.write("eval: OK\\n")
        """)

    def run_task(self, task_id: str) -> None:
        """创建任务并以替身执行器跑一次 code 执行（停 review，不复核）。"""
        self.plain_task(task_id)
        ws = self.make_workspace(f"ws-{task_id}")
        prompt_file = self.tmp / f"prompt-{task_id}.txt"
        prompt_file.write_text(PROMPT_TEXT, encoding="utf-8")
        run = run_cli("workbench-task-run", task_id, "--runtime-dir", str(self.rt),
                      "--workspace", str(ws), "--mode", "code", "--write-scope", "src",
                      "--executor-command",
                      shlex.join([sys.executable, str(self.in_scope_stub()), str(ws)]),
                      "--executor-prompt-file", str(prompt_file),
                      "--eval-command",
                      shlex.join([sys.executable, str(self.eval_probe())]),
                      "--execution-timeout", "900", "--actor", OWNER_A)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def accept_task(self, task_id: str) -> None:
        """run_task + 具名复核，到达 accepted。"""
        self.run_task(task_id)
        review = run_cli("workbench-task-review", task_id, "--runtime-dir", str(self.rt),
                         "--reviewer", REVIEWER_B, "--decision", "approve", "--note", "核对")
        self.assertEqual(review.returncode, 0, review.stdout + review.stderr)

    def create_asset(self, task_id: str = "T-SRC") -> subprocess.CompletedProcess:
        return run_cli("workbench-learn-create", "--runtime-dir", str(self.rt),
                       "--project-id", "P-1", "--task-id", task_id,
                       "--family", "claude-p-lessons", "--title", "无头会话硬教训",
                       "--content", "跨 cwd 一律绝对路径；spawn OSError 必须落账",
                       "--applies", "无头会话", "--actor", "distiller-A")

    def ledger_sha256(self) -> str:
        return hashlib.sha256((self.rt / "workbench.db").read_bytes()).hexdigest()

    # ---- 用例 ------------------------------------------------------------

    def test_read_only_get_only_and_ledger_bytes_unchanged(self) -> None:
        """C1：三层只读——起停服务一次，账本字节不动；写方法一律 405 JSON。"""
        self.init_workbench()
        self.chain_task("T-CHAIN")
        before = self.ledger_sha256()
        port = free_port()
        self.start_dashboard(port)
        status, html = http_request("GET", port, "/")
        self.assertEqual(status, 200, html)
        status, body = http_request("GET", port, "/?format=json")
        self.assertEqual(status, 200, body)
        self.assertEqual(json.loads(body)["acceptance"], "pending_human_review")
        for method in ("POST", "PUT", "DELETE"):
            status, body = http_request(method, port, "/")
            self.assertEqual(status, 405, f"{method} 应被拒（405），实得 {status}")
            self.assertEqual(json.loads(body)["ok"], False, body)
        status, _ = http_request("GET", port, "/no-such-screen")
        self.assertEqual(status, 404)
        self.assertEqual(self.ledger_sha256(), before, "起停服务前后账本文件字节必须不变")

    def test_data_fidelity_matches_sqlite_truth(self) -> None:
        """C2：面板数据与 sqlite 直查逐一一致（任务数、相位、acceptance、evidence_complete）。"""
        self.init_workbench()
        self.chain_task("T-CHAIN")
        self.chain_task("T-BROKEN", complete=False)
        port = free_port()
        self.start_dashboard(port)
        data = self.get_json(port)

        conn = sqlite3.connect(self.rt / "workbench.db")
        try:
            db_tasks = conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0]
            db_phases = dict(conn.execute(
                "SELECT task_id, GROUP_CONCAT(phase, ',') FROM evidence GROUP BY task_id"
            ).fetchall())
        finally:
            conn.close()
        tasks = [t for p in data["projects"] for t in p["tasks"]]
        self.assertEqual(len(tasks), db_tasks)
        by_id = {t["task_id"]: t for t in tasks}
        self.assertEqual({t["task_id"] for t in tasks}, set(db_phases))
        for task_id, phases in db_phases.items():
            self.assertEqual(by_id[task_id]["phases"], phases.split(","))
        self.assertEqual(data["acceptance"], "pending_human_review")
        self.assertIs(data["evidence_complete"], True)  # T-BROKEN 断链但 T-CHAIN 全链 → 全账判定
        self.assertEqual(data["workbench"]["owner"], OWNER_A)

    def test_missing_runtime_dir_fails_with_json_contract(self) -> None:
        """C3：runtime-dir 不存在 → JSON 错误契约（词面对齐未初始化族），不起服务。"""
        proc = run_cli("workbench-dashboard", "--runtime-dir", str(self.tmp / "absent"),
                       "--port", str(free_port()))
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        body = payload(proc)
        self.assertIs(body["ok"], False)
        self.assertIn("工作台尚未初始化", body["error"])

    def test_non_ledger_file_fails_with_json_contract(self) -> None:
        """C3：非本工作台账本（无 workbench 表）→ 同一 JSON 错误契约。"""
        self.rt.mkdir(parents=True)
        conn = sqlite3.connect(self.rt / "workbench.db")
        conn.execute("CREATE TABLE other (x INTEGER)")
        conn.commit()
        conn.close()
        proc = run_cli("workbench-dashboard", "--runtime-dir", str(self.rt),
                       "--port", str(free_port()))
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        body = payload(proc)
        self.assertIs(body["ok"], False)
        self.assertIn("工作台尚未初始化", body["error"])

    def test_old_ledger_missing_tables_degrade_to_empty(self) -> None:
        """C3：老账本缺 V0/记忆表 → 对应屏降级为空，200 不裸崩，其余屏照常。"""
        self.init_workbench()
        self.chain_task("T-CHAIN")
        conn = sqlite3.connect(self.rt / "workbench.db")
        for table in ("learning_runs", "learning_bindings", "learning_recalls",
                      "learning_events", "learning_assets", "reviews", "executions"):
            conn.execute(f"DROP TABLE IF EXISTS {table}")
        conn.commit()
        conn.close()
        port = free_port()
        self.start_dashboard(port)
        data = self.get_json(port)
        self.assertIs(data["memory"]["available"], False)  # 缺表屏如实降级
        self.assertEqual(data["memory"]["assets"], [])
        tasks = [t for p in data["projects"] for t in p["tasks"]]
        self.assertEqual(tasks[0]["executions"], [])
        status, html = http_request("GET", port, "/")
        self.assertEqual(status, 200, html)
        self.assertNotIn("内部错误", html)

    def test_screen_tasks_chain_shows_phases_and_anchor(self) -> None:
        """C4·任务与证据链：全链任务锚定完整；断链任务如实报 missing 词面。"""
        self.init_workbench()
        self.chain_task("T-CHAIN")
        self.chain_task("T-BROKEN", complete=False)
        port = free_port()
        self.start_dashboard(port)
        data = self.get_json(port)
        tasks = {t["task_id"]: t for p in data["projects"] for t in p["tasks"]}
        self.assertIs(tasks["T-CHAIN"]["chain"]["anchored"], True)
        self.assertIsNone(tasks["T-CHAIN"]["chain"]["error"])
        self.assertIs(tasks["T-BROKEN"]["chain"]["anchored"], False)
        self.assertIn("same_command_red_diff_green_missing",
                      tasks["T-BROKEN"]["chain"]["error"])
        self.assertEqual(tasks["T-CHAIN"]["state"], "no_execution")

    def test_screen_executions_and_reviews(self) -> None:
        """C4·执行与复核：执行记录 + 具名复核入屏，状态派生 accepted。"""
        self.init_workbench()
        self.plain_task("T-DLV")
        self.accept_task("T-DLV")
        port = free_port()
        self.start_dashboard(port)
        data = self.get_json(port)
        task = {t["task_id"]: t for p in data["projects"] for t in p["tasks"]}["T-DLV"]
        self.assertEqual(len(task["executions"]), 1)
        self.assertEqual(task["executions"][0]["returncode"], 0)
        self.assertEqual(len(task["reviews"]), 1)
        self.assertEqual(task["reviews"][0]["decision"], "approve")
        self.assertEqual(task["reviews"][0]["reviewer"], REVIEWER_B)
        self.assertEqual(task["state"], "accepted")

    def test_screen_memory_assets(self) -> None:
        """C4·记忆资产：S01 五表真数据入屏（candidate 资产 + 事件链）。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        created = self.create_asset()
        self.assertEqual(created.returncode, 0, created.stdout + created.stderr)
        asset_id = payload(created)["asset"]["asset_id"]
        port = free_port()
        self.start_dashboard(port)
        data = self.get_json(port)
        self.assertIs(data["memory"]["available"], True)
        self.assertEqual([a["asset_id"] for a in data["memory"]["assets"]], [asset_id])
        asset = data["memory"]["assets"][0]
        self.assertEqual(asset["state"], "candidate")
        self.assertEqual(asset["family"], "claude-p-lessons")
        self.assertGreaterEqual(data["memory"]["events_count"], 1)

    def test_stdlib_only_imports(self) -> None:
        """C5：零依赖——dashboard.py 顶层 imports 全部落在标准库白名单内。"""
        source = (REPO_ROOT / "workbench" / "dashboard.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        imported: list[str] = []
        for node in tree.body:
            if isinstance(node, ast.Import):
                imported += [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                if node.level > 0:
                    continue  # 相对导入 = workbench 内部模块，放行
                imported.append(node.module or "")
        offenders = [name for name in imported
                     if name.split(".")[0] not in STDLIB_WHITELIST]
        self.assertEqual(offenders, [], f"越出白名单的导入：{offenders}")
        self.assertTrue(imported, "白名单断言必须真实扫到 imports")

    def test_binds_loopback_and_explicit_port(self) -> None:
        """C6：绑定安全——--port 显式必填、无 --host；源码恒绑 127.0.0.1，无 0.0.0.0。"""
        help_text = run_cli("workbench-dashboard", "--help")
        self.assertEqual(help_text.returncode, 0, help_text.stderr)
        self.assertIn("--port", help_text.stdout)
        self.assertNotIn("--host", help_text.stdout)
        source = (REPO_ROOT / "workbench" / "dashboard.py").read_text(encoding="utf-8")
        self.assertIn("127.0.0.1", source)
        self.assertNotIn("0.0.0.0", source)


if __name__ == "__main__":
    unittest.main()
