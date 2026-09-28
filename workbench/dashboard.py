"""工作台看板——验收人的观察窗（支线 S02 合同，docs/lessons/支线-工作台看板.md §2）。

定位（讲义 §1）：验收人现在看账全靠 sqlite3 命令，本模块把"看账"升级为本地只读
面板：起一个 server，浏览器四屏——任务与证据链（含全局锚定状态）、受控执行与
复核记录、记忆资产、工作台身份与完整性判定。看板不改任何账，只让账可读。

与上游的边界（讲义 §1.2–1.3，D1–D6 裁决落档 §5）：
- 上游 serve-workbench 是可写操作台（POST 建项目/任务/交付）；本看板是**观察窗**，
  零 POST、零操作页、零验收按钮——验收动作不在面板，签收永远在 CLI 具名
  （acceptance: pending_human_review 恒待人签，与 bootstrap 同一词面）。
- 不建 REST/API 抽象（不与 L13 Task API 撞车）：直读账本内部表，渲染函数纯函数
  可测；若 L13 落地 API，看板可迁移为 API 视图——演进路径如实记录，不预设。
- 服务端渲染单页 HTML（stdlib，可打印）；``?format=json`` 返回同一数据 dict。

只读三层保障（D2，测试 tests/test_side_dashboard.py C1 钉住）：
1. sqlite 以 ``mode=ro`` URI 打开（_connect_readonly，绝不建库建目录建表）；
2. 路由仅 GET——POST/PUT/DELETE/PATCH 一律 405 + JSON 错误体（Allow: GET）；
3. 起停服务前后账本文件 sha256 不变（证据账留痕）。

缺表降级（D4）：老账本缺 V0/记忆表时对应屏如实降级为空（复用 bootstrap
_has_table 口径：读路径按空处理，不偷偷建表），其余屏照常，不裸崩。

错误词面对齐 bootstrap 未初始化族（工作台尚未初始化，请先运行 workbench-init）；
启动时账本不可用即快速失败（JSON 错误契约，退出码 1），不起服务。

存储只经 workbench.bootstrap 的单一来源（读侧复用 _load_projects/_chain_error/
_task_state/_digest_problems/_has_table）；不建第二套账本，不写任何账面记录。
"""

from __future__ import annotations

import argparse
import html
import json
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .bootstrap import (
    FLOWERP_CONNECTED,
    LIMITATIONS,
    UNINITIALIZED,
    _chain_error,
    _digest_problems,
    _emit,
    _fail,
    _has_table,
    _load_projects,
    _task_state,
)


def _connect_readonly(runtime_dir: Path) -> sqlite3.Connection | None:
    """mode=ro 打开账本；文件缺失/非本工作台账本一律 None（调用方快速失败）。

    与 bootstrap._connect(create=False) 的差异：URI 只读语义在 sqlite 层拒绝
    任何写（第三层保障的底层），且同样不建库建目录、不裸崩于坏文件。
    """
    db = runtime_dir / "workbench.db"
    if not db.exists():
        return None
    try:
        conn = sqlite3.connect(db.resolve().as_uri() + "?mode=ro", uri=True)
        conn.row_factory = sqlite3.Row
    except sqlite3.Error:
        return None
    table = conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'workbench'"
    ).fetchone()
    if table is None:
        conn.close()
        return None
    return conn


# ---- 数据装配（直读内部表，渲染前投影：不把 output_text 等大字段搬进看板）----


def _memory_view(conn: sqlite3.Connection) -> dict:
    """记忆资产屏（S01 五表）；老账本缺表如实降级 available=False，不裸崩。"""
    if not _has_table(conn, "learning_assets"):
        return {"available": False, "assets": [], "events_count": 0}
    assets = []
    for row in conn.execute(
            "SELECT * FROM learning_assets ORDER BY family, version"):
        try:
            payload = json.loads(row["payload"])
            title = (payload or {}).get("title", "")
        except ValueError:
            title = ""
        assets.append({
            "asset_id": row["asset_id"], "family": row["family"],
            "version": row["version"], "kind": row["kind"],
            "state": row["state"], "approved_by": row["approved_by"],
            "title": title, "sha256": row["sha256"],
            "created_at": row["created_at"],
        })
    events_count = 0
    if _has_table(conn, "learning_events"):
        events_count = conn.execute("SELECT COUNT(*) FROM learning_events").fetchone()[0]
    return {"available": True, "assets": assets, "events_count": events_count}


def _task_view(conn: sqlite3.Connection, task: dict) -> dict:
    """任务行投影：证据/执行记录只留账面事实与摘要列，正文快照不进看板。"""
    chain_error = _chain_error(task["evidence"])
    return {
        "task_id": task["task_id"],
        "request": task["request"],
        "state": _task_state(conn, task["task_id"]),
        "phases": [record["phase"] for record in task["evidence"]],
        "chain": {"anchored": chain_error is None, "error": chain_error},
        "evidence": [{
            "record_id": record["record_id"], "phase": record["phase"],
            "command_text": record["command_text"],
            "returncode": record["returncode"],
            "observed_at": record["observed_at"],
            "output_sha256": record["output_sha256"],
        } for record in task["evidence"]],
        "executions": [{
            "execution_id": execution["execution_id"],
            "mode": execution["mode"], "workspace": execution["workspace"],
            "status": execution["status"],
            "returncode": execution["returncode"],
            "eval_returncode": execution["eval_returncode"],
            "timed_out": execution["timed_out"],
            "eval_timed_out": execution["eval_timed_out"],
            "actor": execution["actor"], "observed_at": execution["observed_at"],
            "diff_sha256": execution["diff_sha256"],
            "eval_output_sha256": execution["eval_output_sha256"],
        } for execution in task["executions"]],
        "reviews": task["reviews"],
    }


def collect_data(conn: sqlite3.Connection) -> dict:
    """看板数据装配（纯读）：?format=json 与 HTML 渲染共用同一份。"""
    row = conn.execute("SELECT * FROM workbench WHERE id = 1").fetchone()
    projects = _load_projects(conn)
    digest_errors = _digest_problems(projects)
    all_anchored = True
    project_views = []
    for project in projects:
        task_views = [_task_view(conn, task) for task in project["tasks"]]
        all_anchored = all_anchored and all(
            task["chain"]["anchored"] for task in task_views)
        project_views.append({
            "project_id": project["project_id"], "name": project["name"],
            "path": project["path"], "purpose": project["purpose"],
            "created_at": project["created_at"], "tasks": task_views,
        })
    task_count = sum(len(p["tasks"]) for p in project_views)
    return {
        "ok": True,
        "flowerp_connected": FLOWERP_CONNECTED,
        "kind": "workbench-dashboard",
        "workbench": {
            "workbench_id": row["workbench_id"], "name": row["name"],
            "owner": row["owner"], "version": row["version"],
            "created_at": row["created_at"],
        },
        # 信用内核词面：验收动作不进面板，恒待人签（无按钮，只显示待人工具名）。
        "acceptance": "pending_human_review",
        "evidence_complete": bool(task_count) and all_anchored and not digest_errors,
        "errors": digest_errors,
        "limitations": list(LIMITATIONS),
        "projects": project_views,
        "memory": _memory_view(conn),
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }


# ---- 服务端渲染（纯函数；零 JS、零表单、零按钮——观察窗没有验收动作）--------


def _esc(value) -> str:
    return html.escape(str(value))


def _table(headers: list[str], rows: list[list[str]]) -> str:
    head = "".join(f"<th>{_esc(h)}</th>" for h in headers)
    if not rows:
        body = f'<tr><td colspan="{len(headers)}" class="empty">（此屏无记录）</td></tr>'
    else:
        body = "".join(
            "<tr>" + "".join(f"<td>{cell}</td>" for cell in row) + "</tr>" for row in rows)
    return f"<table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"


def _chain_badge(chain: dict) -> str:
    if chain["anchored"]:
        return '<span class="ok">锚定完整</span>'
    return f'<span class="bad">失锚：{_esc(chain["error"])}</span>'


def _render_tasks_screen(data: dict) -> str:
    parts = []
    for project in data["projects"]:
        rows = []
        for task in project["tasks"]:
            rows.append([
                _esc(task["task_id"]), _esc(task["request"]),
                _esc(task["state"]), _chain_badge(task["chain"]),
                _esc("、".join(task["phases"]) or "—"),
                _esc(len(task["evidence"])),
            ])
        parts.append(f"<h3>{_esc(project['project_id'])}（{_esc(project['name'])}）</h3>"
                     + _table(["任务", "请求", "状态", "红绿链锚定", "相位", "记录数"], rows))
    if not parts:
        parts.append("<p>（无项目）</p>")
    return "".join(parts)


def _render_executions_screen(data: dict) -> str:
    parts = []
    for project in data["projects"]:
        for task in project["tasks"]:
            if not task["executions"] and not task["reviews"]:
                continue
            exec_rows = [[
                _esc(e["execution_id"]), _esc(e["mode"]), _esc(e["status"]),
                _esc(e["returncode"]), _esc(e["eval_returncode"]),
                _esc("是" if e["timed_out"] else "否"), _esc(e["actor"]),
                _esc(e["observed_at"]),
            ] for e in task["executions"]]
            review_rows = [[
                _esc(r["review_id"]), _esc(r["reviewer"]), _esc(r["decision"]),
                _esc(r["note"]), _esc(r["reviewed_at"]),
            ] for r in task["reviews"]]
            parts.append(
                f"<h3>{_esc(task['task_id'])}</h3>"
                + _table(["执行", "模式", "状态", "退出码", "eval 退出码", "超时", "执行人", "观察时刻"], exec_rows)
                + _table(["复核", "复核人", "决定", "备注", "复核时刻"], review_rows))
    if not parts:
        parts.append("<p>（暂无执行/复核记录）</p>")
    return "".join(parts)


def _render_memory_screen(memory: dict) -> str:
    if not memory["available"]:
        return ("<p class='empty'>（账本无记忆表——老账本缺表，此屏如实降级为空；"
                "不偷偷建表）</p>")
    rows = [[
        _esc(a["asset_id"]), _esc(a["family"]), _esc(a["version"]),
        _esc(a["kind"]), _esc(a["state"]), _esc(a["approved_by"] or "—"),
        _esc(a["title"]), _esc(a["sha256"][:12]), _esc(a["created_at"]),
    ] for a in memory["assets"]]
    return (_table(["条目", "family", "版本", "类型", "状态", "审核人", "标题",
                    "sha256 前 12", "创建时刻"], rows)
            + f"<p>事件总数：{_esc(memory['events_count'])}</p>")


def _render_identity_screen(data: dict) -> str:
    workbench = data["workbench"]
    errors = data["errors"]
    if errors:
        error_html = "<ul>" + "".join(f"<li class='bad'>{_esc(e)}</li>" for e in errors) + "</ul>"
    else:
        error_html = "<p class='ok'>摘要复核无问题（spec/output 摘要与正文逐一一致）</p>"
    limitation_html = "<ul>" + "".join(
        f"<li>{_esc(item)}</li>" for item in data["limitations"]) + "</ul>"
    return (
        f"<p>工作台：{_esc(workbench['name'])}（{_esc(workbench['workbench_id'][:12])}…）"
        f" · 所有者：{_esc(workbench['owner'])} · 版本：{_esc(workbench['version'])}"
        f" · 建于 {_esc(workbench['created_at'])}</p>"
        f"<p>全账判定 evidence_complete："
        f"<span class='{'ok' if data['evidence_complete'] else 'bad'}'>"
        f"{_esc(data['evidence_complete'])}</span> · "
        f"acceptance：{_esc(data['acceptance'])}（恒待人签，面板无验收动作）</p>"
        f"<h3>完整性判定</h3>{error_html}<h3>如实声明的能力边界</h3>{limitation_html}")


def _render_html(data: dict) -> str:
    banner = (
        "ok" if data["evidence_complete"] and not data["errors"] else "bad")
    return f"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>工作台看板——观察窗</title>
<style>
body {{ font-family: -apple-system, "PingFang SC", sans-serif; margin: 2rem; color: #1a1a2e; }}
h1 {{ font-size: 1.4rem; }} h2 {{ font-size: 1.15rem; border-bottom: 2px solid #e8e8ef; padding-bottom: .3rem; margin-top: 2.2rem; }}
h3 {{ font-size: 1rem; margin-bottom: .4rem; }}
table {{ border-collapse: collapse; width: 100%; margin: .6rem 0 1.2rem; font-size: .85rem; }}
th, td {{ border: 1px solid #d8d8e2; padding: .35rem .5rem; text-align: left; vertical-align: top; }}
th {{ background: #f0f0f7; }}
td.empty, p.empty {{ color: #777; }}
.ok {{ color: #14691b; }} .bad {{ color: #a11515; }}
.banner {{ background: #f0f0f7; padding: .6rem .9rem; border-left: 4px solid #556; }}
code {{ background: #f0f0f7; padding: 0 .3rem; }}
footer {{ margin-top: 3rem; color: #666; font-size: .8rem; }}
</style>
</head>
<body>
<h1>工作台看板 <small>——验收人的观察窗（只读）</small></h1>
<p class="banner">acceptance：<code>{_esc(data["acceptance"])}</code>（验收动作不在面板——签收永远在 CLI 具名）
 · evidence_complete：<code>{_esc(data["evidence_complete"])}</code>
 · 数据源自账本只读打开（<code>mode=ro</code>，仅 GET）</p>
<section id="screen-tasks"><h2>一、任务与证据链</h2>{_render_tasks_screen(data)}</section>
<section id="screen-executions"><h2>二、执行与复核记录</h2>{_render_executions_screen(data)}</section>
<section id="screen-memory"><h2>三、记忆资产</h2>{_render_memory_screen(data["memory"])}</section>
<section id="screen-identity"><h2>四、身份与完整性判定</h2>{_render_identity_screen(data)}</section>
<footer>generated_at {_esc(data["generated_at"])} · {("全账判定完整" if banner == "ok" else "全账判定存在失锚/疑点，逐屏核对")}
 · ?format=json 返回同一数据</footer>
</body>
</html>"""


# ---- HTTP 服务（仅 GET 的观察窗）-------------------------------------------


class _DashboardHandler(BaseHTTPRequestHandler):
    """四屏单页路由：GET / 渲染 HTML，GET /?format=json 出同一数据 JSON。

    runtime_dir 由 _cmd_dashboard 注入（server.runtime_dir）；每请求现开 mode=ro
    连接重读账本——观察窗反映账本当下状态，不在服务内缓存账面。
    """

    server_version = "WorkbenchDashboard/0.1"

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/":
            self._send_json(404, {
                "ok": False, "flowerp_connected": FLOWERP_CONNECTED,
                "error": f"unknown_path: 看板只有一页 /（请求了 {parsed.path}）"})
            return
        want_json = parse_qs(parsed.query).get("format", [""])[0] == "json"
        try:
            conn = _connect_readonly(self.server.runtime_dir)
            if conn is None:  # 服务起后账本被移除的兜底，不裸崩
                self._send_json(500, {
                    "ok": False, "flowerp_connected": FLOWERP_CONNECTED,
                    "error": UNINITIALIZED})
                return
            try:
                data = collect_data(conn)
            finally:
                conn.close()
        except Exception as error:  # 与 cli.py 顶层同形状：JSON 错误契约不裸 traceback
            self._send_json(500, {
                "ok": False, "flowerp_connected": FLOWERP_CONNECTED,
                "error": f"内部错误：{type(error).__name__}: {error}"})
            return
        if want_json:
            self._send_json(200, data)
        else:
            self._send_html(200, _render_html(data))

    def _reject_write(self) -> None:
        self._send_json(405, {
            "ok": False, "flowerp_connected": FLOWERP_CONNECTED,
            "error": "method_not_allowed: 看板是观察窗，零写端点，仅 GET"},
            extra_headers=(("Allow", "GET"),))

    do_POST = _reject_write
    do_PUT = _reject_write
    do_DELETE = _reject_write
    do_PATCH = _reject_write

    def _send_json(self, status: int, payload: dict,
                   extra_headers: tuple[tuple[str, str], ...] = ()) -> None:
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        for name, value in extra_headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, status: int, text: str) -> None:
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _cmd_dashboard(args) -> int:
    runtime_dir = Path(args.runtime_dir)
    conn = _connect_readonly(runtime_dir)
    if conn is None:  # 目录不存在 / 非本工作台账本：快速失败，不起服务
        return _fail(UNINITIALIZED)
    conn.close()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), _DashboardHandler)  # 仅回环，无 --host
    server.runtime_dir = runtime_dir.resolve()
    try:
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "serving": f"http://127.0.0.1:{args.port}/",
               "runtime_dir": str(runtime_dir.resolve()),
               "note": "观察窗只读：零 POST、零写端点；Ctrl-C 停止"})
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


def _register_dashboard(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-dashboard",
                                   help="起本地只读看板（四屏观察窗，零 POST）")
    parser.add_argument("--runtime-dir", required=True, help="运行数据库目录")
    parser.add_argument("--port", required=True, type=int,
                        help="监听端口（显式必填；恒绑本机回环地址，无对外暴露面）")
    parser.set_defaults(handler=_cmd_dashboard)


def register_commands(registry: dict) -> None:
    """把 workbench-dashboard 挂到 cli.REGISTRY（同 L01/L03/L04/S01 接缝形状）。"""
    registry.update({"workbench-dashboard": _register_dashboard})
