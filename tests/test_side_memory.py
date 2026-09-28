"""支线合同测试：记忆系统最小闭环（Spec = docs/lessons/支线-记忆系统最小闭环.md §5，定稿 2026-09-28）。

映射表（验收项 → 测试名 → 实际操作 → 比较什么）：
链走通 → test_memory_full_chain_walkthrough → 提炼→审核→发布→召回→绑定→三相位→outcome 回写 → 全链 id/状态/相位可追
C1     → test_cross_project_isolation_recalls_only_own_project → P-1 资产在 P-2 召回 → 不出现（项目隔离按过滤语义）
C2     → test_revoked_memory_not_recalled_history_kept → revoke 后召回 → 不注入；历史与证据全保留
C2/C6  → test_superseded_memory_not_recalled_old_snapshot_intact → 发布 v2 → v1 置 superseded 不进召回；v1 快照与事件不被改写
C3     → test_publish_requires_approval_candidate_not_recalled → 未 approve 直接 publish → approve_required；candidate 不进召回；outcome 缺具名验收 → reuse_acceptance_missing
C4     → test_reuse_failure_recorded_and_memory_stays_active → eval 相位 failed 时 finish passed → phase_not_passed；outcome failed 如实落账；memory 类不自动撤回，召回仍可见
C5/C10 → test_read_path_recomputes_sha256_tamper_blocked → 篡改 payload / 篡改来源证据 → 读取即报 memory_content_checksum_failed / source_task_report_changed
C7     → test_recall_no_match_is_honest_empty → 关键词不命中 → ok:true、matches:[]，召回包仍落库
C8     → test_govern_and_finish_reject_non_human_actors → ai/待定/agent:x 名义 → human_actor_required
C9     → test_invalid_transitions_rejected → 重复 approve / 重复 revoke → invalid_transition
C11    → test_binding_unique_per_task_and_plan → 同 task 再绑 / 同 plan 再绑 → binding_exists
C12    → test_self_source_recall_and_self_govern_rejected → 来源任务自召回 → excluded(self_source)；提炼者自审 → self_govern_rejected
C13    → test_bind_decisions_must_match_recall → 决定集与召回 matches 不逐项对应 → decision_mismatch
复查轮C1补全 → test_govern_rejects_cross_project → 跨项目治理 → project_mismatch（上游显式拒绝口径）
复查轮防漂移 → test_binding_detects_adopted_version_drift → 采用版本内容被改（含摘要列同改）→ learn-run 前暴露 binding_version_changed

CLI 合同（本文件的被测缝，实现须与此一致）：
- workbench-learn-create  --runtime-dir --project-id --task-id --family --title --content
                          [--applies K]... [--excludes K]... [--boundary S] [--conflict-key S]
                          [--supersedes ASSET-ID] --actor A
- workbench-learn-govern  --runtime-dir --asset-id --decision approve|publish|revoke --actor H [--note S]
- workbench-learn-recall  --runtime-dir --project-id --task-id [--keyword K]... [--budget N]
- workbench-learn-bind    --runtime-dir --recall-id R --task-id T --plan-id PL --decisions JSON --actor H
- workbench-learn-run     --runtime-dir --binding-id B --phase precheck|implement|eval
                          --result passed|failed --evidence-table evidence|executions
                          --evidence-record-id N [--summary S]
- workbench-learn-finish  --runtime-dir --binding-id B --outcome passed|failed --reviewer H [--note S]
- workbench-learn-show    --runtime-dir (--asset-id ID | --recall-id R | --binding-id B)

错误词面：human_actor_required / approve_required / invalid_transition / self_govern_rejected /
source_task_not_accepted / required_task_missing / decision_mismatch / asset_not_active /
binding_exists / recall_task_mismatch / phase_already_recorded / evidence_record_missing /
evidence_task_mismatch / missing_phase / phase_not_passed / reuse_acceptance_missing /
memory_content_checksum_failed / source_task_report_changed / show_target_required /
project_mismatch / binding_version_changed

口径：全部用例经真实 CLI 子进程（sys.executable -X utf8 -m workbench.cli）驱动，
不绕开命令入口直调内部函数；每个用例独立的临时运行目录。来源任务准入复用
L04 执行/复核机制（accepted 状态派生），替身执行器边界同 test_l04_workbench_v0.py。
"""

from __future__ import annotations

import hashlib
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
OWNER_A = "tester-A"
REVIEWER_B = "reviewer-B"


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


class SideMemoryContractTest(unittest.TestCase):
    """每个用例独立的临时运行目录与临时 git 候选工作区。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.rt = self.tmp / "rt" / "S01-memory"

    # ---- 组装辅助（来源任务走 L04 执行/复核机制到 accepted）--------------

    def make_workspace(self, name: str = "candidate") -> Path:
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

    def init_workbench(self) -> None:
        self.assertEqual(run_cli("workbench-init", "--runtime-dir", str(self.rt),
                                 "--owner", OWNER_A).returncode, 0)
        self.assertEqual(run_cli("workbench-project-add", "--runtime-dir", str(self.rt),
                                 "--project-id", "P-1", "--name", "测试项目").returncode, 0)
        self.assertEqual(run_cli("workbench-project-add", "--runtime-dir", str(self.rt),
                                 "--project-id", "P-2", "--name", "另一项目").returncode, 0)

    def run_task(self, task_id: str, project_id: str = "P-1") -> None:
        """创建任务并以替身执行器跑一次 code 执行（停 review，不复核）。"""
        ws = self.make_workspace(f"ws-{task_id}")
        create = run_cli("workbench-task-create", "--runtime-dir", str(self.rt),
                         "--project-id", project_id, "--task-id", task_id,
                         "--requirement-id", task_id, "--request", "来源任务", "--actor", OWNER_A)
        self.assertEqual(create.returncode, 0, create.stdout + create.stderr)
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

    def accept_task(self, task_id: str, project_id: str = "P-1") -> None:
        """run_task + 具名复核，到达 accepted（记忆来源准入的前提）。"""
        self.run_task(task_id, project_id)
        review = run_cli("workbench-task-review", task_id, "--runtime-dir", str(self.rt),
                         "--reviewer", REVIEWER_B, "--decision", "approve", "--note", "核对")
        self.assertEqual(review.returncode, 0, review.stdout + review.stderr)

    def plain_task(self, task_id: str, project_id: str = "P-1") -> None:
        """只建任务不执行（作为召回/绑定的事项载体）。"""
        create = run_cli("workbench-task-create", "--runtime-dir", str(self.rt),
                         "--project-id", project_id, "--task-id", task_id,
                         "--requirement-id", task_id, "--request", "复用事项", "--actor", OWNER_A)
        self.assertEqual(create.returncode, 0, create.stdout + create.stderr)

    # ---- 学习账辅助 ------------------------------------------------------

    def create_asset(self, task_id: str = "T-SRC", family: str = "claude-p-lessons",
                     title: str = "claude -p 无头会话硬教训", project_id: str = "P-1",
                     actor: str = "distiller-A", **extra) -> subprocess.CompletedProcess:
        args = ["workbench-learn-create", "--runtime-dir", str(self.rt),
                "--project-id", project_id, "--task-id", task_id,
                "--family", family, "--title", title,
                "--content", "argparse 旗标一律单字符串；跨 cwd 一律绝对路径；spawn OSError 必须落账",
                "--applies", "无头会话", "--applies", "执行器", "--actor", actor]
        for key, value in extra.items():
            args += [f"--{key.replace('_', '-')}", value]
        return run_cli(*args)

    def govern(self, asset_id: str, decision: str, actor: str = REVIEWER_B,
               note: str = "按边界核对", project_id: str = "P-1") -> subprocess.CompletedProcess:
        return run_cli("workbench-learn-govern", "--runtime-dir", str(self.rt),
                       "--project-id", project_id,
                       "--asset-id", asset_id, "--decision", decision,
                       "--actor", actor, "--note", note)

    def recall(self, task_id: str, project_id: str = "P-1",
               *keywords: str) -> subprocess.CompletedProcess:
        args = ["workbench-learn-recall", "--runtime-dir", str(self.rt),
                "--project-id", project_id, "--task-id", task_id]
        for keyword in keywords:
            args += ["--keyword", keyword]
        return run_cli(*args)

    def bind(self, recall_id: str, task_id: str, decisions: list[dict],
             plan_id: str = "PLAN-1", actor: str = REVIEWER_B) -> subprocess.CompletedProcess:
        return run_cli("workbench-learn-bind", "--runtime-dir", str(self.rt),
                       "--recall-id", recall_id, "--task-id", task_id,
                       "--plan-id", plan_id, "--decisions", json.dumps(decisions, ensure_ascii=False),
                       "--actor", actor)

    def bind_adopt_all(self, recall_id: str, task_id: str, matches: list[dict],
                       plan_id: str = "PLAN-1") -> subprocess.CompletedProcess:
        decisions = [{"asset_id": m["asset_id"], "adopt": True,
                      "reason": "复查轮直接适用"} for m in matches]
        return self.bind(recall_id, task_id, decisions, plan_id=plan_id)

    def exec_id(self, task_id: str) -> int:
        db = self.rt / "workbench.db"
        conn = sqlite3.connect(db)
        try:
            row = conn.execute(
                "SELECT MIN(execution_id) FROM executions WHERE task_id = ?", (task_id,)
            ).fetchone()
            return int(row[0])
        finally:
            conn.close()

    def learn_run(self, binding_id: str, phase: str, result: str,
                  task_id: str) -> subprocess.CompletedProcess:
        """相位证据引用绑定任务自己的执行记录，模块侧现算摘要。"""
        return run_cli("workbench-learn-run", "--runtime-dir", str(self.rt),
                       "--binding-id", binding_id, "--phase", phase, "--result", result,
                       "--evidence-table", "executions",
                       "--evidence-record-id", str(self.exec_id(task_id)),
                       "--summary", f"{phase} 相位结果")

    def learn_finish(self, binding_id: str, outcome: str,
                     reviewer: str = REVIEWER_B) -> subprocess.CompletedProcess:
        return run_cli("workbench-learn-finish", "--runtime-dir", str(self.rt),
                       "--binding-id", binding_id, "--outcome", outcome,
                       "--reviewer", reviewer, "--note", "按复验链核对")

    def show(self, *, asset_id: str | None = None, recall_id: str | None = None,
             binding_id: str | None = None) -> subprocess.CompletedProcess:
        args = ["workbench-learn-show", "--runtime-dir", str(self.rt)]
        if asset_id:
            args += ["--asset-id", asset_id]
        if recall_id:
            args += ["--recall-id", recall_id]
        if binding_id:
            args += ["--binding-id", binding_id]
        return run_cli(*args)

    def published_asset(self, task_id: str = "T-SRC", family: str = "claude-p-lessons") -> dict:
        """直达 active 的资产：create → approve → publish。"""
        proc = self.create_asset(task_id=task_id, family=family)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        asset_id = payload(proc)["asset"]["asset_id"]
        self.assertEqual(self.govern(asset_id, "approve").returncode, 0)
        pub = self.govern(asset_id, "publish")
        self.assertEqual(pub.returncode, 0, pub.stdout + pub.stderr)
        return payload(pub)["asset"]

    # ---- 用例 -----------------------------------------------------------

    def test_memory_full_chain_walkthrough(self) -> None:
        """链走通：提炼→审核→发布→召回→逐项采用→三相位→outcome 回写。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.run_task("T-DLV")
        created = self.create_asset()
        self.assertEqual(created.returncode, 0, created.stdout + created.stderr)
        asset = payload(created)["asset"]
        self.assertEqual(asset["state"], "candidate")
        self.assertEqual(asset["version"], 1)
        self.assertTrue(asset["source"]["sha256"])  # 来源快照封存带摘要

        self.assertEqual(self.govern(asset["asset_id"], "approve").returncode, 0)
        published = self.govern(asset["asset_id"], "publish")
        self.assertEqual(published.returncode, 0, published.stdout + published.stderr)
        self.assertEqual(payload(published)["asset"]["state"], "active")

        rec = self.recall("T-DLV", "P-1", "无头会话")
        self.assertEqual(rec.returncode, 0, rec.stdout + rec.stderr)
        body = payload(rec)["recall"]
        self.assertEqual(len(body["matches"]), 1)
        self.assertEqual(body["matches"][0]["asset_id"], asset["asset_id"])
        self.assertTrue(body["recall_id"].startswith("RECALL-"))
        self.assertIn("budget", body)

        bound = self.bind_adopt_all(body["recall_id"], "T-DLV", body["matches"])
        self.assertEqual(bound.returncode, 0, bound.stdout + bound.stderr)
        binding = payload(bound)["binding"]
        self.assertTrue(binding["binding_id"].startswith("BIND-"))
        self.assertEqual(binding["assets"][0]["asset_id"], asset["asset_id"])
        self.assertTrue(binding["assets"][0]["sha256"])  # 采用快照封存带摘要

        for phase in ("precheck", "implement", "eval"):
            ran = self.learn_run(binding["binding_id"], phase, "passed", "T-DLV")
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertTrue(payload(ran)["run"]["evidence"]["sha256"])  # 引用账本记录现算摘要

        finished = self.learn_finish(binding["binding_id"], "passed")
        self.assertEqual(finished.returncode, 0, finished.stdout + finished.stderr)
        self.assertEqual(payload(finished)["finish"]["phases"],
                         ["precheck", "implement", "eval", "review", "outcome"])

    def test_cross_project_isolation_recalls_only_own_project(self) -> None:
        """C1：P-1 的资产在 P-2 的召回里不可见（隔离按过滤语义）。"""
        self.init_workbench()
        self.accept_task("T-SRC", project_id="P-1")
        self.plain_task("T-P2", project_id="P-2")
        asset = self.published_asset(task_id="T-SRC")
        rec = self.recall("T-P2", "P-2", "无头会话")
        self.assertEqual(rec.returncode, 0, rec.stdout + rec.stderr)
        body = payload(rec)["recall"]
        self.assertEqual(body["matches"], [])
        flat = json.dumps(body, ensure_ascii=False)
        self.assertNotIn(asset["asset_id"], flat)  # 整包任何字段都不出现

    def test_revoked_memory_not_recalled_history_kept(self) -> None:
        """C2：撤回后不注入；条目与事件历史全保留（失效不可抹）。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.plain_task("T-DLV")
        asset = self.published_asset()
        revoked = self.govern(asset["asset_id"], "revoke")
        self.assertEqual(revoked.returncode, 0, revoked.stdout + revoked.stderr)
        self.assertEqual(payload(revoked)["asset"]["state"], "revoked")
        rec = self.recall("T-DLV", "P-1", "无头会话")
        self.assertEqual(payload(rec)["recall"]["matches"], [])
        shown = self.show(asset_id=asset["asset_id"])
        self.assertEqual(shown.returncode, 0, shown.stdout + shown.stderr)
        events = payload(shown)["asset"]["events"]
        actions = [e["action"] for e in events]
        self.assertEqual(actions, ["candidate", "approve", "publish", "revoke"])  # 全链事件在

    def test_superseded_memory_not_recalled_old_snapshot_intact(self) -> None:
        """C2/C6：发布 v2 → v1 原子 superseded；v1 快照与事件不被改写。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.plain_task("T-DLV")
        v1 = self.published_asset()
        created = self.create_asset(family=v1["family"], title="教训 v2",
                                    supersedes=v1["asset_id"])
        self.assertEqual(created.returncode, 0, created.stdout + created.stderr)
        v2 = payload(created)["asset"]
        self.assertEqual(v2["version"], 2)
        self.assertEqual(v2["supersedes"], v1["asset_id"])
        self.assertEqual(self.govern(v2["asset_id"], "approve").returncode, 0)
        self.assertEqual(self.govern(v2["asset_id"], "publish").returncode, 0)
        rec = self.recall("T-DLV", "P-1", "无头会话")
        matches = payload(rec)["recall"]["matches"]
        self.assertEqual([m["asset_id"] for m in matches], [v2["asset_id"]])  # 只注入 v2
        shown_v1 = self.show(asset_id=v1["asset_id"])
        self.assertEqual(shown_v1.returncode, 0, shown_v1.stdout + shown_v1.stderr)
        v1_body = payload(shown_v1)["asset"]
        self.assertEqual(v1_body["state"], "superseded")
        self.assertEqual(v1_body["payload"]["content"],
                         "argparse 旗标一律单字符串；跨 cwd 一律绝对路径；spawn OSError 必须落账")  # 旧快照原样
        superseded_events = [e for e in v1_body["events"] if e["action"] == "superseded"]
        self.assertEqual(superseded_events[-1]["evidence"].get("replacement"), v2["asset_id"])

    def test_publish_requires_approval_candidate_not_recalled(self) -> None:
        """C3：跳过审批被拒；candidate 不进召回；outcome 缺具名验收被拒。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.run_task("T-DLV")
        created = self.create_asset()
        self.assertEqual(created.returncode, 0, created.stdout + created.stderr)
        asset_id = payload(created)["asset"]["asset_id"]
        skipped = self.govern(asset_id, "publish")
        self.assertEqual(skipped.returncode, 1, skipped.stdout + skipped.stderr)
        self.assertIn("approve_required", skipped.stdout)
        rec = self.recall("T-DLV", "P-1", "无头会话")
        self.assertEqual(payload(rec)["recall"]["matches"], [])  # candidate 不默认注入

        self.assertEqual(self.govern(asset_id, "approve").returncode, 0)
        published = self.govern(asset_id, "publish")
        self.assertEqual(published.returncode, 0, published.stdout + published.stderr)
        rec2 = self.recall("T-DLV", "P-1", "无头会话")
        matches = payload(rec2)["recall"]["matches"]
        bound = self.bind_adopt_all(payload(rec2)["recall"]["recall_id"], "T-DLV", matches)
        self.assertEqual(bound.returncode, 0, bound.stdout + bound.stderr)
        binding_id = payload(bound)["binding"]["binding_id"]
        for phase in ("precheck", "implement", "eval"):
            self.assertEqual(self.learn_run(binding_id, phase, "passed", "T-DLV").returncode, 0)
        missing = self.learn_finish(binding_id, "passed", reviewer="")
        self.assertEqual(missing.returncode, 1, missing.stdout + missing.stderr)
        self.assertIn("reuse_acceptance_missing", missing.stdout)  # 复用验证缺少具名验收

    def test_reuse_failure_recorded_and_memory_stays_active(self) -> None:
        """C4：复用失败不冒充通过；memory 类失败只记 outcome，不自动撤回。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.run_task("T-DLV")
        asset = self.published_asset()
        rec = self.recall("T-DLV", "P-1", "无头会话")
        matches = payload(rec)["recall"]["matches"]
        bound = self.bind_adopt_all(payload(rec)["recall"]["recall_id"], "T-DLV", matches)
        binding_id = payload(bound)["binding"]["binding_id"]
        self.assertEqual(self.learn_run(binding_id, "precheck", "passed", "T-DLV").returncode, 0)
        self.assertEqual(self.learn_run(binding_id, "implement", "passed", "T-DLV").returncode, 0)
        self.assertEqual(self.learn_run(binding_id, "eval", "failed", "T-DLV").returncode, 0)
        masked = self.learn_finish(binding_id, "passed")
        self.assertEqual(masked.returncode, 1, masked.stdout + masked.stderr)
        self.assertIn("phase_not_passed", masked.stdout)  # 历史成功不掩盖本轮失败
        failed = self.learn_finish(binding_id, "failed")
        self.assertEqual(failed.returncode, 0, failed.stdout + failed.stderr)
        self.assertEqual(payload(failed)["finish"]["outcome"], "failed")
        shown = self.show(asset_id=asset["asset_id"])
        self.assertEqual(payload(shown)["asset"]["state"], "active")  # memory 类不自动撤回
        rec2 = self.recall("T-DLV", "P-1", "无头会话")
        self.assertEqual(len(payload(rec2)["recall"]["matches"]), 1)  # 召回仍可见

    def test_govern_rejects_cross_project(self) -> None:
        """复查轮（C1 补全）：跨项目治理被拒；本项目治理放行。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        created = self.create_asset()
        self.assertEqual(created.returncode, 0, created.stdout + created.stderr)
        asset_id = payload(created)["asset"]["asset_id"]
        foreign = self.govern(asset_id, "approve", project_id="P-2")
        self.assertEqual(foreign.returncode, 1, foreign.stdout + foreign.stderr)
        self.assertIn("project_mismatch", foreign.stdout)
        own = self.govern(asset_id, "approve", project_id="P-1")
        self.assertEqual(own.returncode, 0, own.stdout + own.stderr)

    def test_binding_detects_adopted_version_drift(self) -> None:
        """复查轮：采用版本内容漂移（正文+摘要列一致改）在复验前暴露。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.run_task("T-DLV")
        asset = self.published_asset()
        rec = self.recall("T-DLV", "P-1", "无头会话")
        matches = payload(rec)["recall"]["matches"]
        bound = self.bind_adopt_all(payload(rec)["recall"]["recall_id"], "T-DLV", matches)
        binding_id = payload(bound)["binding"]["binding_id"]
        drifted = '{"family": "claude-p-lessons", "version": 1, "被": "篡改成新内容"}'
        forged = hashlib.sha256(drifted.encode("utf-8")).hexdigest()
        db = self.rt / "workbench.db"
        conn = sqlite3.connect(db)
        conn.execute(
            "UPDATE learning_assets SET payload = ?, sha256 = ? WHERE asset_id = ?",
            (drifted, forged, asset["asset_id"]))
        conn.commit()
        conn.close()
        ran = self.learn_run(binding_id, "precheck", "passed", "T-DLV")
        self.assertEqual(ran.returncode, 1, ran.stdout + ran.stderr)
        self.assertIn("binding_version_changed", ran.stdout)

    def test_read_path_recomputes_sha256_tamper_blocked(self) -> None:
        """C5/C10：读取即重算摘要复核；账本被手改必须暴露，不静默。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        asset_a = self.published_asset(family="lessons-a")
        asset_b = self.published_asset(family="lessons-b")
        db = self.rt / "workbench.db"

        # 载荷被手改 → 读取即校验失败（快照防漂移）
        conn = sqlite3.connect(db)
        conn.execute(
            "UPDATE learning_assets SET payload = '{\"被\": \"篡改\"}' WHERE asset_id = ?",
            (asset_a["asset_id"],))
        conn.commit()
        conn.close()
        tampered = self.show(asset_id=asset_a["asset_id"])
        self.assertEqual(tampered.returncode, 1, tampered.stdout + tampered.stderr)
        self.assertIn("memory_content_checksum_failed", tampered.stdout)

        # 来源执行记录被一致性手改（正文与摘要列同改）→ 来源引用链复核暴露
        forged = hashlib.sha256("被篡改的来源输出".encode("utf-8")).hexdigest()
        conn = sqlite3.connect(db)
        conn.execute(
            "UPDATE executions SET stdout_text = '被篡改的来源输出', stdout_sha256 = ? "
            "WHERE task_id = 'T-SRC'", (forged,))
        conn.commit()
        conn.close()
        source = self.show(asset_id=asset_b["asset_id"])
        self.assertEqual(source.returncode, 1, source.stdout + source.stderr)
        self.assertIn("source_task_report_changed", source.stdout)

    def test_recall_no_match_is_honest_empty(self) -> None:
        """C7：检索不到如实为空，不编造召回；空召回包仍落库可追。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.plain_task("T-DLV")
        self.published_asset()
        rec = self.recall("T-DLV", "P-1", "向量检索")
        self.assertEqual(rec.returncode, 0, rec.stdout + rec.stderr)
        body = payload(rec)["recall"]
        self.assertEqual(body["matches"], [])
        self.assertEqual(body["excluded"], [])
        self.assertTrue(body["recall_id"].startswith("RECALL-"))  # 空包也留痕

    def test_govern_and_finish_reject_non_human_actors(self) -> None:
        """C8：具名门槛——AI/待定/agent 名义一律拒绝。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        created = self.create_asset()
        self.assertEqual(created.returncode, 0, created.stdout + created.stderr)
        asset_id = payload(created)["asset"]["asset_id"]
        for actor in ("ai", "待定", "agent:codex", "pending"):
            bad = self.govern(asset_id, "approve", actor=actor)
            self.assertEqual(bad.returncode, 1, bad.stdout + bad.stderr)
            self.assertIn("human_actor_required", bad.stdout)
        # 资产仍未被审核（被拒操作不留痕），非人复核人同样过不了 finish 的门槛
        self.run_task("T-DLV")
        self.assertEqual(self.govern(asset_id, "approve").returncode, 0)
        self.assertEqual(self.govern(asset_id, "publish").returncode, 0)
        rec = self.recall("T-DLV", "P-1", "无头会话")
        matches = payload(rec)["recall"]["matches"]
        bound = self.bind_adopt_all(payload(rec)["recall"]["recall_id"], "T-DLV", matches)
        binding_id = payload(bound)["binding"]["binding_id"]
        for phase in ("precheck", "implement", "eval"):
            self.assertEqual(self.learn_run(binding_id, phase, "passed", "T-DLV").returncode, 0)
        robot = self.learn_finish(binding_id, "passed", reviewer="agent:claude")
        self.assertEqual(robot.returncode, 1, robot.stdout + robot.stderr)
        self.assertIn("human_actor_required", robot.stdout)

    def test_invalid_transitions_rejected(self) -> None:
        """C9：非法状态迁移一律拒绝，账面不被破坏。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        created = self.create_asset()
        self.assertEqual(created.returncode, 0, created.stdout + created.stderr)
        asset_id = payload(created)["asset"]["asset_id"]
        self.assertEqual(self.govern(asset_id, "approve").returncode, 0)
        again = self.govern(asset_id, "approve")
        self.assertEqual(again.returncode, 1, again.stdout + again.stderr)
        self.assertIn("invalid_transition", again.stdout)
        self.assertEqual(self.govern(asset_id, "publish").returncode, 0)
        self.assertEqual(self.govern(asset_id, "revoke").returncode, 0)
        twice = self.govern(asset_id, "revoke")
        self.assertEqual(twice.returncode, 1, twice.stdout + twice.stderr)
        self.assertIn("invalid_transition", twice.stdout)

    def test_binding_unique_per_task_and_plan(self) -> None:
        """C11：一次交付最多一条采用快照（task 与 plan 双唯一）。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.plain_task("T-DLV")
        self.plain_task("T-DLV2")
        self.published_asset()
        rec = self.recall("T-DLV", "P-1", "无头会话")
        body = payload(rec)["recall"]
        first = self.bind_adopt_all(body["recall_id"], "T-DLV", body["matches"])
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        same_task = self.bind_adopt_all(body["recall_id"], "T-DLV", body["matches"],
                                        plan_id="PLAN-2")
        self.assertEqual(same_task.returncode, 1, same_task.stdout + same_task.stderr)
        self.assertIn("binding_exists", same_task.stdout)
        rec2 = self.recall("T-DLV2", "P-1", "无头会话")
        body2 = payload(rec2)["recall"]
        same_plan = self.bind_adopt_all(body2["recall_id"], "T-DLV2", body2["matches"])
        self.assertEqual(same_plan.returncode, 1, same_plan.stdout + same_plan.stderr)
        self.assertIn("binding_exists", same_plan.stdout)

    def test_self_source_recall_and_self_govern_rejected(self) -> None:
        """C12：同源事项不得独立复用；提炼者不得自审。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        created = self.create_asset(actor=OWNER_A)  # 提炼者 = OWNER_A
        self.assertEqual(created.returncode, 0, created.stdout + created.stderr)
        asset_id = payload(created)["asset"]["asset_id"]
        self_publish = self.govern(asset_id, "approve", actor=OWNER_A)
        self.assertEqual(self_publish.returncode, 1, self_publish.stdout + self_publish.stderr)
        self.assertIn("self_govern_rejected", self_publish.stdout)
        self.assertEqual(self.govern(asset_id, "approve").returncode, 0)  # 独立具名审核放行
        self.assertEqual(self.govern(asset_id, "publish").returncode, 0)
        selfrec = self.recall("T-SRC", "P-1", "无头会话")  # 来源任务自召回
        self.assertEqual(selfrec.returncode, 0, selfrec.stdout + selfrec.stderr)
        body = payload(selfrec)["recall"]
        self.assertEqual(body["matches"], [])
        self.assertTrue(any("self_source" in e["reason"] for e in body["excluded"]))

    def test_bind_decisions_must_match_recall(self) -> None:
        """C13：采用决定必须与本轮召回 matches 逐项对应。"""
        self.init_workbench()
        self.accept_task("T-SRC")
        self.plain_task("T-DLV")
        self.published_asset()
        rec = self.recall("T-DLV", "P-1", "无头会话")
        body = payload(rec)["recall"]
        asset_id = body["matches"][0]["asset_id"]
        extra = self.bind(body["recall_id"], "T-DLV",
                          [{"asset_id": "ASSET-ghost", "adopt": True, "reason": "幻影"}])
        self.assertEqual(extra.returncode, 1, extra.stdout + extra.stderr)
        self.assertIn("decision_mismatch", extra.stdout)
        partial = self.bind(body["recall_id"], "T-DLV", [])  # 有 matches 却零决定
        self.assertEqual(partial.returncode, 1, partial.stdout + partial.stderr)
        self.assertIn("decision_mismatch", partial.stdout)
        ok = self.bind(body["recall_id"], "T-DLV",
                       [{"asset_id": asset_id, "adopt": False, "reason": "本轮不适用"}])
        self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)  # 逐项拒绝也是完整决定
        self.assertEqual(payload(ok)["binding"]["assets"], [])  # 无采用即无快照


if __name__ == "__main__":
    unittest.main()
