"""Workbench V0.1：任务账、命令证据账与完整性检查（L01 合同，docs/lessons/L01-工作台自举.md §2）。

结构判断（上游辅导资料 §4 的四职责，本讲的最小分工）：
- 命令入口：``_cmd_*`` 接收参数、输出 JSON、返回退出码，不含业务判断；
- 任务与记录逻辑：快照摘要、同命令红—绿链判定（``_chain_error``，与上游
  vendors/CodexFDE workbench/bootstrap.py ``_chain`` 语义一致：任一同命令红 +
  其间成功 Diff + 全局最新 red/diff/green 记录须为成功绿）；
- 存储：sqlite（``<runtime-dir>/workbench.db``），读命令绝不建库建目录，
  并始终复核存储内容与 SHA-256 摘要（spec/output_digest_mismatch）；
- 测试：tests/test_l01_workbench_bootstrap.py（WB-01..WB-12 + 复查修复轮，经真实 CLI 子进程）。

冻结接口：``workbench-evidence-add`` 参数形状与 vendors/CodexFDE 的
import_evidence.py 调用一致；错误词面对齐上游（required_task_missing /
same_command_red_diff_green_missing / 工作台尚未初始化）。
L01 不接入 FlowERP：flowerp_connected 恒为 False（L04 换挡点改判）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path

WORKBENCH_VERSION = "V0.1"
DEFAULT_WORKBENCH_NAME = "个人 AI 研发工作台"
FLOWERP_CONNECTED = False  # 冻结边界：L01 不接入 FlowERP，恒为 False
PHASES = ("red", "diff", "green", "observation")
UNINITIALIZED = "工作台尚未初始化，请先运行 workbench-init"
MISSING_CHAIN = "same_command_red_diff_green_missing"
MISSING_TASK = "required_task_missing"
LIMITATIONS = (
    "用户导入的观察记录，未认证命令执行者和时间",
    "有效红灯原因、Diff 写集与 Spec 签署仍须人工核验；完整性不等于验收完成",
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS workbench (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  workbench_id TEXT NOT NULL,
  name TEXT NOT NULL,
  owner TEXT NOT NULL,
  version TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS projects (
  project_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  path TEXT NOT NULL DEFAULT '.',
  purpose TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS tasks (
  task_id TEXT PRIMARY KEY,
  project_id TEXT NOT NULL,
  request TEXT NOT NULL,
  requirement_id TEXT NOT NULL,
  actor TEXT NOT NULL DEFAULT '',
  requirement_snapshot TEXT NOT NULL DEFAULT '',
  requirement_summary TEXT NOT NULL DEFAULT '',
  problem_snapshot TEXT,
  problem_summary TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS evidence (
  record_id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  phase TEXT NOT NULL,
  command_text TEXT NOT NULL,
  output_text TEXT NOT NULL,
  output_sha256 TEXT NOT NULL,
  returncode INTEGER NOT NULL,
  observed_at TEXT NOT NULL,
  recorded_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS evidence_by_task ON evidence (task_id);
"""


class LedgerUnavailable(RuntimeError):
    """运行库不可用（目录不可创建、文件非 sqlite 账本等基础设施失败）。"""


# ---- 存储 ---------------------------------------------------------------


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _connect(runtime_dir: Path, *, create: bool) -> sqlite3.Connection | None:
    """create=False 时绝不建库建目录（status 不偷偷初始化），且校验账本可用。"""
    db = runtime_dir / "workbench.db"
    if create:
        try:
            runtime_dir.mkdir(parents=True, exist_ok=True)
            conn = sqlite3.connect(db)
            conn.row_factory = sqlite3.Row
            conn.executescript(_SCHEMA)
            return conn
        except (OSError, sqlite3.Error) as error:
            raise LedgerUnavailable(f"运行库不可用：{db}（{error}）") from error
    if not db.exists():
        return None
    try:
        conn = sqlite3.connect(db)
        conn.row_factory = sqlite3.Row
        table = conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'workbench'"
        ).fetchone()
    except sqlite3.Error:
        return None  # 空文件/非账本文件按"尚未初始化"处理，不裸崩
    if table is None:
        conn.close()
        return None
    return conn


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _parse_aware(value: str) -> datetime | None:
    if isinstance(value, str) and value.endswith("Z"):  # 3.10 的 fromisoformat 不认 'Z'
        value = value[:-1] + "+00:00"
    try:
        moment = datetime.fromisoformat(value)
    except (TypeError, ValueError):
        return None
    if moment.tzinfo is None or moment.utcoffset() is None:
        return None
    return moment


# ---- 任务与记录逻辑 -------------------------------------------------------


def _chain_error(records: list[dict]) -> str | None:
    """同命令红—绿链判定：返回 None 表示完整（语义对齐上游 ``_chain``）。

    完整 = 任一与绿同命令的红(rc≠0)，其间夹一条成功 Diff（时间严格介于红绿之间），
    且全局最新的 red/diff/green 记录就是这条成功绿（observation 不参与；
    绿后任何相——跨命令亦然——再出新 red/diff/green 即旧绿灯失权）。
    """
    checks: list[tuple[datetime, dict]] = []
    for record in records:
        moment = _parse_aware(record["observed_at"])
        if moment is None:
            return MISSING_CHAIN
        if record["phase"] in ("red", "diff", "green"):
            checks.append((moment, record))
    if not checks:
        return MISSING_CHAIN
    latest_time, green = max(checks, key=lambda item: (item[0], item[1]["record_id"]))
    if green["phase"] != "green" or green["returncode"] != 0:
        return MISSING_CHAIN
    for _, red in checks:
        if red["phase"] != "red" or red["returncode"] == 0:
            continue
        if red["command_text"] != green["command_text"]:
            continue
        red_time = _parse_aware(red["observed_at"])
        if any(rec["phase"] == "diff" and rec["returncode"] == 0
               and red_time < _parse_aware(rec["observed_at"]) < latest_time
               for _, rec in checks):
            return None
    return MISSING_CHAIN


def _read_snapshot(path: str) -> str | None:
    try:
        return Path(path).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError, ValueError):
        return None


def _digest_problems(projects: list[dict]) -> list[str]:
    """复核存储内容与 SHA-256 摘要（对齐上游 spec/output_digest_mismatch）。"""
    problems: list[str] = []
    for project in projects:
        for task in project["tasks"]:
            if (task["requirement_snapshot"] and task["requirement_summary"]
                    and _sha256(task["requirement_snapshot"]) != task["requirement_summary"]):
                problems.append(f"spec_digest_mismatch: 任务 {task['task_id']} 的需求快照与摘要不一致")
            if task["problem_snapshot"] and task["problem_summary"] \
                    and _sha256(task["problem_snapshot"]) != task["problem_summary"]:
                problems.append(f"problem_digest_mismatch: 任务 {task['task_id']} 的问题快照与摘要不一致")
            for record in task["evidence"]:
                if _sha256(record["output_text"]) != record["output_sha256"]:
                    problems.append(
                        f"output_digest_mismatch: 任务 {task['task_id']} 记录 "
                        f"{record['record_id']} 的输出与摘要不一致")
    return problems


# ---- 命令入口 -------------------------------------------------------------


def _emit(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def _fail(error: str, **extra) -> int:
    _emit({"ok": False, "error": error, "flowerp_connected": FLOWERP_CONNECTED, **extra})
    return 1


def _require_workbench(conn: sqlite3.Connection):
    return conn.execute("SELECT * FROM workbench WHERE id = 1").fetchone()


def _cmd_init(args) -> int:
    conn = _connect(Path(args.runtime_dir), create=True)
    try:
        existing = _require_workbench(conn)
        if existing is not None:
            if existing["owner"] != args.owner:  # 上游同形：同 owner 幂等，异 owner 拒绝
                return _fail(f"工作台已存在（所有者 {existing['owner']}），不能被另一个所有者覆盖")
            _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
                   "workbench": {"workbench_id": existing["workbench_id"],
                                 "name": existing["name"], "owner": existing["owner"],
                                 "version": existing["version"]}})
            return 0
        workbench_id = uuid.uuid4().hex
        conn.execute(
            "INSERT INTO workbench (workbench_id, name, owner, version, created_at) VALUES (?,?,?,?,?)",
            (workbench_id, args.name, args.owner, WORKBENCH_VERSION, _now()),
        )
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "workbench": {"workbench_id": workbench_id, "name": args.name,
                             "owner": args.owner, "version": WORKBENCH_VERSION}})
        return 0
    finally:
        conn.close()


def _cmd_project_add(args) -> int:
    conn = _connect(Path(args.runtime_dir), create=False)
    if conn is None or _require_workbench(conn) is None:
        return _fail(UNINITIALIZED)
    try:
        if conn.execute("SELECT 1 FROM projects WHERE project_id = ?", (args.project_id,)).fetchone():
            return _fail(f"项目编号已存在：{args.project_id}，不覆盖旧项目")
        conn.execute(
            "INSERT INTO projects (project_id, name, path, purpose, created_at) VALUES (?,?,?,?,?)",
            (args.project_id, args.name, args.path, args.purpose, _now()),
        )
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "project": {"project_id": args.project_id, "name": args.name,
                           "path": args.path, "purpose": args.purpose}})
        return 0
    finally:
        conn.close()


def _cmd_task_create(args) -> int:
    conn = _connect(Path(args.runtime_dir), create=False)
    if conn is None or _require_workbench(conn) is None:
        return _fail(UNINITIALIZED)
    try:
        if not conn.execute("SELECT 1 FROM projects WHERE project_id = ?", (args.project_id,)).fetchone():
            return _fail(f"项目不存在：{args.project_id}，任务必须有已登记项目")
        if conn.execute("SELECT 1 FROM tasks WHERE task_id = ?", (args.task_id,)).fetchone():
            return _fail(f"任务编号已存在：{args.task_id}，原任务与记录保持不变")
        request = args.request if args.request is not None else args.title
        requirement_id = args.requirement_id or args.task_id  # 上游同形：缺省回退任务编号
        requirement_snapshot = ""
        requirement_summary = ""
        if args.spec_file:
            requirement_snapshot = _read_snapshot(args.spec_file)
            if requirement_snapshot is None:
                return _fail(f"需求文件不可读取：{args.spec_file}，任务未创建")
            requirement_summary = _sha256(requirement_snapshot)
        problem_snapshot = None
        problem_summary = None
        if args.problem_file:
            problem_snapshot = _read_snapshot(args.problem_file)
            if problem_snapshot is None:
                return _fail(f"问题文件不可读取：{args.problem_file}，任务未创建")
            problem_summary = _sha256(problem_snapshot)
        conn.execute(
            "INSERT INTO tasks (task_id, project_id, request, requirement_id, actor,"
            " requirement_snapshot, requirement_summary, problem_snapshot, problem_summary, created_at)"
            " VALUES (?,?,?,?,?,?,?,?,?,?)",
            (args.task_id, args.project_id, request, requirement_id, args.actor,
             requirement_snapshot, requirement_summary, problem_snapshot, problem_summary, _now()),
        )
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "task": {"task_id": args.task_id, "project_id": args.project_id,
                        "request": request, "requirement_id": requirement_id,
                        "requirement_summary": requirement_summary}})
        return 0
    finally:
        conn.close()


def _cmd_evidence_add(args) -> int:
    conn = _connect(Path(args.runtime_dir), create=False)
    if conn is None or _require_workbench(conn) is None:
        return _fail(UNINITIALIZED)
    try:
        if not conn.execute("SELECT 1 FROM tasks WHERE task_id = ?", (args.task_id,)).fetchone():
            return _fail(f"{MISSING_TASK}: 任务不存在：{args.task_id}，记录未追加")
        if _parse_aware(args.observed_at) is None:
            return _fail(f"观察时间缺少时区或无法解析：{args.observed_at}，记录未追加")
        try:
            raw_output = Path(args.output_file).read_bytes()
        except OSError:
            return _fail(f"输出不可读取：{args.output_file}，记录未追加")
        if not raw_output:
            return _fail(f"输出为空：{args.output_file}，记录未追加")
        output_text = raw_output.decode("utf-8", errors="replace")
        output_sha256 = _sha256(output_text)  # 与 status 复核同一口径（对存储文本哈希）
        cursor = conn.execute(
            "INSERT INTO evidence (task_id, phase, command_text, output_text, output_sha256,"
            " returncode, observed_at, recorded_at) VALUES (?,?,?,?,?,?,?,?)",
            (args.task_id, args.phase, args.command_text, output_text,
             output_sha256, args.returncode, args.observed_at, _now()),
        )
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "record": {"record_id": cursor.lastrowid, "task_id": args.task_id,
                          "phase": args.phase, "output_sha256": output_sha256,
                          "returncode": args.returncode, "observed_at": args.observed_at}})
        return 0
    finally:
        conn.close()


def _load_projects(conn: sqlite3.Connection) -> list[dict]:
    projects: list[dict] = []
    for project in conn.execute("SELECT * FROM projects ORDER BY created_at, project_id"):
        tasks = []
        for task in conn.execute(
            "SELECT * FROM tasks WHERE project_id = ? ORDER BY created_at, task_id",
            (project["project_id"],),
        ):
            evidence = [dict(record) for record in conn.execute(
                "SELECT * FROM evidence WHERE task_id = ? ORDER BY record_id",
                (task["task_id"],),
            )]
            tasks.append({
                "task_id": task["task_id"], "project_id": task["project_id"],
                "request": task["request"], "requirement_id": task["requirement_id"],
                "actor": task["actor"],
                "requirement_snapshot": task["requirement_snapshot"],
                "requirement_summary": task["requirement_summary"],
                "problem_snapshot": task["problem_snapshot"],
                "problem_summary": task["problem_summary"],
                "created_at": task["created_at"],
                "evidence": evidence,
            })
        projects.append({
            "project_id": project["project_id"], "name": project["name"],
            "path": project["path"], "purpose": project["purpose"],
            "created_at": project["created_at"], "tasks": tasks,
        })
    return projects


def _cmd_status(args) -> int:
    conn = _connect(Path(args.runtime_dir), create=False)
    if conn is None or _require_workbench(conn) is None:
        return _fail(UNINITIALIZED)
    try:
        workbench = dict(_require_workbench(conn))
        projects = _load_projects(conn)
        errors: list[str] = _digest_problems(projects)

        if args.require_project and not any(p["project_id"] == args.require_project for p in projects):
            return _fail(f"required_project_missing: 项目不存在：{args.require_project}",
                         evidence_complete=False, errors=errors)
        scope = [p for p in projects
                 if not args.require_project or p["project_id"] == args.require_project]
        selected = [t for p in scope for t in p["tasks"]
                    if not args.require_task or t["task_id"] == args.require_task]
        if args.require_task and not selected:
            return _fail(f"{MISSING_TASK}: 任务不存在：{args.require_task}",
                         evidence_complete=False, errors=errors)

        complete = bool(selected) and all(
            _chain_error(task["evidence"]) is None for task in selected)
        if args.require_red_green_evidence:
            if not args.require_task:
                return _fail("缺少 --require-task，无法对具体任务检查证据完整性",
                             evidence_complete=complete and not errors, errors=errors)
            if not complete:
                errors.append(f"{MISSING_CHAIN}: 缺少同命令的红—Diff—绿链")

        evidence_complete = complete and not errors
        _emit({"ok": not errors, "flowerp_connected": FLOWERP_CONNECTED,
               "workbench": {"workbench_id": workbench["workbench_id"],
                             "name": workbench["name"], "owner": workbench["owner"],
                             "version": workbench["version"]},
               "projects": projects, "errors": errors,
               "evidence_complete": evidence_complete,
               "acceptance": "pending_human_review",
               "limitations": list(LIMITATIONS)})
        return 0 if not errors else 1
    finally:
        conn.close()


# ---- 注册表接缝 ------------------------------------------------------------


def _register_init(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-init", help="初始化工作台身份与本地账本")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.add_argument("--name", default=DEFAULT_WORKBENCH_NAME, help="工作台名称（可选）")
    parser.add_argument("--owner", required=True, help="所有者具名")
    parser.set_defaults(handler=_cmd_init)


def _register_project_add(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-project-add", help="登记项目")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.add_argument("--project-id", required=True, help="项目编号")
    parser.add_argument("--name", required=True, help="项目名称")
    parser.add_argument("--path", default=".", help="项目代码路径")
    parser.add_argument("--purpose", default="", help="项目用途")
    parser.set_defaults(handler=_cmd_project_add)


def _register_task_create(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-task-create", help="创建任务并保存需求/问题快照")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.add_argument("--project-id", required=True, help="所属已登记项目")
    parser.add_argument("--task-id", required=True, help="任务编号")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--request", help="任务请求原文")
    group.add_argument("--title", help="任务标题")
    parser.add_argument("--requirement-id", default=None,
                        help="需求编号（缺省回退任务编号，上游同形）")
    parser.add_argument("--actor", default="", help="创建人具名")
    parser.add_argument("--spec-file", default="", help="需求文件（保存创建时快照）")
    parser.add_argument("--problem-file", default="", help="原始问题文件（可选快照）")
    parser.set_defaults(handler=_cmd_task_create)


def _register_evidence_add(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-evidence-add", help="追加命令运行记录")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.add_argument("--task-id", required=True, help="任务编号")
    parser.add_argument("--phase", required=True, choices=PHASES, help="记录阶段")
    parser.add_argument("--command-text", required=True, help="原始命令文本")
    parser.add_argument("--output-file", required=True, help="输出文件路径（保存快照与摘要）")
    parser.add_argument("--returncode", required=True, type=int, help="真实退出码")
    parser.add_argument("--observed-at", required=True, help="带时区的观察时间")
    parser.set_defaults(handler=_cmd_evidence_add)


def _register_status(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-status", help="查询项目、任务与运行记录完整性")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.add_argument("--require-project", default="", help="要求存在的项目编号")
    parser.add_argument("--require-task", default="", help="要求存在的任务编号")
    parser.add_argument("--require-red-green-evidence", action="store_true",
                        help="要求同命令红—Diff—绿链完整")
    parser.set_defaults(handler=_cmd_status)


def register_commands(registry: dict) -> None:
    """把 L01 五个合同命令挂到 cli.REGISTRY（幂等，保持 cli.py 的接缝形状）。"""
    registry.update({
        "workbench-init": _register_init,
        "workbench-project-add": _register_project_add,
        "workbench-task-create": _register_task_create,
        "workbench-evidence-add": _register_evidence_add,
        "workbench-status": _register_status,
    })
