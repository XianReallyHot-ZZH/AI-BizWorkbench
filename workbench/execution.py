"""Workbench V0 受控执行（L04 合同，lesson-04-submission/WB-L04-BOOTSTRAP.md）。

把 L03 能读的合同变成能组织的一次执行：绑定候选工作区、限制写集、调用执行器、
收回 invocation / change_manifest / diff / 双流输出（逐项 SHA-256）、运行最小
Eval、停在 review 等人审。执行记录与上游 07-real-codex-executor.json 同形
（ADR-0002：执行器合同形状不变，适配器从 Codex CLI 换成 ``claude -p``）。

接口（调用方必须知道的全部事实）：
- ``workbench-task-run <task-id>``：``--workspace`` 必须是存在且含 .git 的候选目录
  （错误候选拒于进程前）；``--mode verify`` 只在绑定工作目录实跑 Eval、绝不调用
  执行器进程；``--mode code`` 必须声明 ``--write-scope``（相对候选的路径）与
  ``--executor-command``（argv 列表，提示词经 stdin 送达，cwd=候选）。超时、
  执行器失败、越界都在 Eval 前停止；越界不自动回滚（停止推进 ≠ 还原）。
  Eval 通过只推进到 review，``acceptance`` 仍待人签。
- ``workbench-task-review <task-id>``：任务处于 review 态才可复核；复核人必须具名
  且不得是最近一次执行者（执行者不能批准自己）；approve/reject 记录只追加。
- ``workbench-task-show <task-id>``：摘要——任务状态、执行记录（修改文件、
  验证命令、退出码）、复核记录，供独立复验人从同一账本读取。
- 校验类拒绝（缺写集/坏候选/review 态重跑/自批/前置未接受）不写任何记录；
  执行类失败（失败/超时/越界/eval 失败）如实落账，旧记录保留不覆盖。

存储只经 workbench.bootstrap 的单一入口（_connect/_ensure_v0_schema/_sha256），
不建第二套账本；写集检查的局限如实声明：事前约束靠候选工作区 + argv 注入，
事后以候选 git 状态实测比对——提示词约定不等于事前全部防住（讲义 D4）。
"""

from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .bootstrap import (
    EXECUTION_COMPLETE_STATUSES,
    FLOWERP_CONNECTED,
    MISSING_TASK,
    UNINITIALIZED,
    _connect,
    _emit,
    _ensure_v0_schema,
    _executions_for,
    _fail,
    _latest_execution,
    _now,
    _require_workbench,
    _reviews_for,
    _sha256,
    _task_state,
)


@dataclass(frozen=True)
class _ProcessOutcome:
    returncode: int | None
    timed_out: bool
    stdout_text: str
    stderr_text: str


# ---- 进程运行器（执行器与 Eval 共用；输出一律按 UTF-8 解码留证）--------------


def _run_process(command: list[str], *, cwd: Path, timeout_s: int,
                 stdin_text: str | None = None) -> _ProcessOutcome:
    try:
        proc = subprocess.run(
            command, cwd=str(cwd),
            input=stdin_text.encode("utf-8") if stdin_text is not None else None,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout_s,
        )
    except subprocess.TimeoutExpired as expired:
        return _ProcessOutcome(
            returncode=None, timed_out=True,
            stdout_text=(expired.stdout or b"").decode("utf-8", errors="replace"),
            stderr_text=(expired.stderr or b"").decode("utf-8", errors="replace"),
        )
    return _ProcessOutcome(
        returncode=proc.returncode, timed_out=False,
        stdout_text=proc.stdout.decode("utf-8", errors="replace"),
        stderr_text=proc.stderr.decode("utf-8", errors="replace"),
    )


# ---- 写集检查与改动采集（事后以候选 git 状态实测为准）------------------------


def _normalize_scope(entries: list[str]) -> list[str]:
    scope: list[str] = []
    for entry in entries:
        path = Path(entry.strip())
        if not path.parts:  # 空条目忽略
            continue
        if path.is_absolute() or ".." in path.parts:
            continue  # 绝对路径与越出候选的条目不构成有效写集，落写集校验拒绝
        scope.append(path.as_posix().lstrip("./") or ".")
    return list(dict.fromkeys(scope))


def _in_scope(path: str, scope: list[str]) -> bool:
    return any(path == item or path.startswith(item.rstrip("/") + "/") for item in scope)


def _git(workspace: Path, *argv: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(workspace), *argv],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def _workspace_error(workspace: Path) -> str | None:
    if not workspace.is_dir():
        return f"workspace_invalid: 工作目录不存在：{workspace}"
    if not (workspace / ".git").exists():
        return f"workspace_invalid: 工作目录不是 git 候选（缺 .git）：{workspace}"
    return None


def _collect_changes(workspace: Path, scope: list[str]) -> tuple[list[str], list[dict], list[str], str]:
    """执行后的实测改动：porcelain 列改动、逐文件前后 SHA-256、Diff 正文、越界清单。"""
    raw = _git(workspace, "status", "--porcelain", "-z").stdout.decode("utf-8", errors="replace")
    entries = [entry for entry in raw.split("\0") if entry]
    changed: list[str] = []
    index = 0
    while index < len(entries):
        entry = entries[index]
        changed.append(Path(entry[3:]).as_posix())
        if index + 1 < len(entries) and (entry[0] in "RC" or entry[1] in "RC"):
            changed.append(Path(entries[index + 1]).as_posix())  # 重命名的原路径也算改动
            index += 1
        index += 1
    changed = sorted(dict.fromkeys(changed))
    # 意图添加仅写索引标记（不改文件内容），让未跟踪文件进入 diff；候选是 scratch 副本。
    _git(workspace, "add", "-N", ".")
    diff_text = _git(workspace, "diff", "HEAD").stdout.decode("utf-8", errors="replace")
    manifest: list[dict] = []
    for path in changed:
        head = _git(workspace, "show", f"HEAD:{path}")
        before = _sha256(head.stdout.decode("utf-8", errors="replace")) if head.returncode == 0 else None
        file = workspace / path
        after = _sha256(file.read_bytes().decode("utf-8", errors="replace")) if file.is_file() else None
        change = "added" if before is None else ("deleted" if after is None else "modified")
        manifest.append({"path": path, "change": change,
                         "before_sha256": before, "after_sha256": after})
    out_of_scope = [path for path in changed if not _in_scope(path, scope)]
    return changed, manifest, out_of_scope, diff_text


# ---- 命令入口 ---------------------------------------------------------------


def _cmd_task_run(args) -> int:
    conn = _connect(Path(args.runtime_dir), create=False)
    if conn is None or _require_workbench(conn) is None:
        return _fail(UNINITIALIZED)
    try:
        if not conn.execute("SELECT 1 FROM tasks WHERE task_id = ?", (args.task_id,)).fetchone():
            return _fail(f"{MISSING_TASK}: 任务不存在：{args.task_id}，记录未追加")
        workspace = Path(args.workspace)
        invalid = _workspace_error(workspace)
        if invalid:
            return _fail(invalid)
        scope = _normalize_scope(args.write_scope or [])
        executor_command = list(args.executor_command) if args.executor_command else None
        prompt_text = ""
        if args.mode == "code":
            if not scope:
                return _fail("write_scope_required: code 模式必须声明允许写集，拒绝启动")
            if executor_command is None:
                return _fail("executor_command_required: code 模式必须给出执行器命令，拒绝启动")
        elif executor_command is not None:
            return _fail("verify_rejects_executor_command: 仅复验模式不调用执行器进程，拒绝执行器命令")
        if args.executor_prompt_file:
            try:
                prompt_text = Path(args.executor_prompt_file).read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                return _fail(f"executor_prompt_unreadable: 提示词文件不可读取：{args.executor_prompt_file}，执行未启动")
        if not (args.actor or "").strip():
            return _fail("required_actor_missing: 执行发起人必须具名，执行未启动")
        state = _task_state(conn, args.task_id)
        if state == "review":
            return _fail(f"task_in_review: 任务处于人工复核态，拒绝再次运行：{args.task_id}")

        _ensure_v0_schema(conn)
        record: dict = {
            "task_id": args.task_id, "mode": args.mode,
            "workspace": str(workspace.resolve()), "write_scope": scope,
            "executor_command": executor_command, "executor_prompt": prompt_text,
            "eval_command": list(args.eval_command),
        }
        if args.mode == "verify":
            eval_rc, eval_text = _run_eval(args, workspace)
            record.update(returncode=None, timed_out=False, stdout_text="", stderr_text="",
                          changed_files=[], out_of_scope_files=[], change_manifest=[],
                          diff_text="", eval_returncode=eval_rc, eval_output_text=eval_text,
                          status="verify_completed" if eval_rc == 0 else "eval_failed")
        else:
            outcome = _run_process(executor_command, cwd=workspace,
                                   timeout_s=args.execution_timeout, stdin_text=prompt_text)
            changed, manifest, out_of_scope, diff_text = _collect_changes(workspace, scope)
            record.update(returncode=outcome.returncode, timed_out=outcome.timed_out,
                          stdout_text=outcome.stdout_text, stderr_text=outcome.stderr_text,
                          changed_files=changed, out_of_scope_files=out_of_scope,
                          change_manifest=manifest, diff_text=diff_text)
            if outcome.timed_out:
                record["status"] = "timeout"       # 超时：Eval 前停止，输出与 Diff 已留证
            elif outcome.returncode != 0:
                record["status"] = "failed"        # 执行器失败：不冒充成功
            elif out_of_scope:
                record["status"] = "out_of_scope"  # 越界：Eval 前停止，不自动回滚
            else:
                eval_rc, eval_text = _run_eval(args, workspace)
                record.update(eval_returncode=eval_rc, eval_output_text=eval_text,
                              status="completed" if eval_rc == 0 else "eval_failed")

        record["stdout_sha256"] = _sha256(record["stdout_text"])
        record["stderr_sha256"] = _sha256(record["stderr_text"])
        record["diff_sha256"] = _sha256(record["diff_text"])
        record["eval_output_sha256"] = _sha256(record.get("eval_output_text", ""))
        execution_id = _insert_execution(conn, record, actor=args.actor)
        ok = record["status"] in EXECUTION_COMPLETE_STATUSES
        payload = dict(record)
        payload["execution_id"] = execution_id
        payload["actor"] = args.actor.strip()
        payload["observed_at"] = _now()
        _emit({"ok": ok, "flowerp_connected": FLOWERP_CONNECTED,
               "execution": payload, "task_state": _task_state(conn, args.task_id)})
        return 0 if ok else 1
    finally:
        conn.close()


def _run_eval(args, workspace: Path) -> tuple[int | None, str]:
    outcome = _run_process(list(args.eval_command), cwd=workspace,
                           timeout_s=args.execution_timeout)
    return outcome.returncode, outcome.stdout_text + outcome.stderr_text


def _insert_execution(conn, record: dict, *, actor: str) -> int:
    def dumps(value):
        return json.dumps(value, ensure_ascii=False) if value is not None else None

    cursor = conn.execute(
        "INSERT INTO executions (task_id, mode, workspace, write_scope, executor_command,"
        " executor_prompt, returncode, timed_out, stdout_text, stderr_text, stdout_sha256,"
        " stderr_sha256, changed_files, out_of_scope_files, change_manifest, diff_text,"
        " diff_sha256, eval_command, eval_returncode, eval_output_text, eval_output_sha256,"
        " status, actor, observed_at, recorded_at)"
        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (record["task_id"], record["mode"], record["workspace"], dumps(record["write_scope"]),
         dumps(record["executor_command"]), record["executor_prompt"], record["returncode"],
         int(record["timed_out"]), record["stdout_text"], record["stderr_text"],
         record["stdout_sha256"], record["stderr_sha256"],
         dumps(record["changed_files"]), dumps(record["out_of_scope_files"]),
         dumps(record["change_manifest"]), record["diff_text"], record["diff_sha256"],
         dumps(record["eval_command"]), record.get("eval_returncode"),
         record.get("eval_output_text", ""), record["eval_output_sha256"],
         record["status"], actor.strip(), _now(), _now()),
    )
    conn.commit()
    return cursor.lastrowid


def _cmd_task_review(args) -> int:
    conn = _connect(Path(args.runtime_dir), create=False)
    if conn is None or _require_workbench(conn) is None:
        return _fail(UNINITIALIZED)
    try:
        if not conn.execute("SELECT 1 FROM tasks WHERE task_id = ?", (args.task_id,)).fetchone():
            return _fail(f"{MISSING_TASK}: 任务不存在：{args.task_id}，记录未追加")
        reviewer = args.reviewer.strip()
        if not reviewer:
            return _fail("required_reviewer_missing: 复核人必须具名，记录未追加")
        latest = _latest_execution(conn, args.task_id)
        if latest is None:
            return _fail(f"no_execution_to_review: 任务尚无执行记录，不能复核：{args.task_id}")
        if reviewer == latest["actor"]:
            return _fail(f"self_review_rejected: 复核人与最近一次执行者是同一人（{reviewer}），"
                         "执行者不能批准自己，记录未追加")
        if _task_state(conn, args.task_id) != "review":
            return _fail(f"task_not_in_review: 任务不处于人工复核态，不能复核：{args.task_id}")
        _ensure_v0_schema(conn)
        cursor = conn.execute(
            "INSERT INTO reviews (task_id, execution_id, reviewer, decision, note, reviewed_at)"
            " VALUES (?,?,?,?,?,?)",
            (args.task_id, latest["execution_id"], reviewer, args.decision,
             args.note, _now()),
        )
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "review": {"review_id": cursor.lastrowid, "task_id": args.task_id,
                          "execution_id": latest["execution_id"], "reviewer": reviewer,
                          "decision": args.decision, "note": args.note},
               "task_state": _task_state(conn, args.task_id)})
        return 0
    finally:
        conn.close()


def _cmd_task_show(args) -> int:
    conn = _connect(Path(args.runtime_dir), create=False)
    if conn is None or _require_workbench(conn) is None:
        return _fail(UNINITIALIZED)
    try:
        task = conn.execute("SELECT * FROM tasks WHERE task_id = ?", (args.task_id,)).fetchone()
        if task is None:
            return _fail(f"{MISSING_TASK}: 任务不存在：{args.task_id}")
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "task": {"task_id": task["task_id"], "request": task["request"],
                        "requirement_id": task["requirement_id"], "actor": task["actor"],
                        "prerequisite_task_id": task["prerequisite_task_id"]
                        if "prerequisite_task_id" in task.keys() else "",
                        "state": _task_state(conn, args.task_id),
                        "executions": _executions_for(conn, args.task_id),
                        "reviews": _reviews_for(conn, args.task_id)}})
        return 0
    finally:
        conn.close()


# ---- 注册表接缝（REGISTRY 第二批）-------------------------------------------


def _register_task_run(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-task-run", help="受控执行：绑定候选、限制写集、收回证据、停在 review")
    parser.add_argument("task_id", help="任务编号")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.add_argument("--workspace", required=True, help="本次绑定的候选工作目录（git 仓库）")
    parser.add_argument("--mode", choices=("verify", "code"), default="verify",
                        help="verify=仅复验（不调用执行器）；code=受控执行")
    parser.add_argument("--write-scope", nargs="+", default=[], metavar="PATH",
                        help="允许写集（相对候选的路径，code 模式必填）")
    parser.add_argument("--executor-command", nargs="+", default=None, metavar="ARG",
                        help="执行器命令 argv（提示词经 stdin 送达；code 模式必填）")
    parser.add_argument("--executor-prompt-file", default="", help="执行器提示词文件（UTF-8）")
    parser.add_argument("--eval-command", nargs="+", required=True, metavar="ARG",
                        help="最小 Eval 命令 argv（在绑定候选目录实跑）")
    parser.add_argument("--execution-timeout", type=int, default=900, help="超时秒数（默认 900）")
    parser.add_argument("--actor", required=True, help="执行发起人具名")
    parser.set_defaults(handler=_cmd_task_run)


def _register_task_review(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-task-review", help="具名人工复核：approve/reject，执行者不能自批")
    parser.add_argument("task_id", help="任务编号")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.add_argument("--reviewer", required=True, help="复核人具名")
    parser.add_argument("--decision", required=True, choices=("approve", "reject"), help="复核决定")
    parser.add_argument("--note", default="", help="复核说明")
    parser.set_defaults(handler=_cmd_task_review)


def _register_task_show(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-task-show", help="任务摘要：状态、执行记录与复核记录")
    parser.add_argument("task_id", help="任务编号")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.set_defaults(handler=_cmd_task_show)


def register_commands(registry: dict) -> None:
    """REGISTRY 缝第二批注册（与 bootstrap.register_commands 同形）。"""
    registry.update({
        "workbench-task-run": _register_task_run,
        "workbench-task-review": _register_task_review,
        "workbench-task-show": _register_task_show,
    })
