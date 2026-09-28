"""Workbench 记忆系统最小闭环（支线 S01 合同，docs/lessons/支线-记忆系统最小闭环.md）。

把已验收任务的经验提炼为带证据引用的记忆条目，经具名审核后按项目召回、
逐项采用并复验，失效不删除只状态迁移（CONTEXT.md「记忆系统」词条）。
口径对齐上游 CodexFDE ``workbench/learning.py``（调查笔记
docs/research/upstream-memory-system.md；pin 7f67533）：五表 assets/events/
recalls/bindings/runs；记忆链自有五相位 precheck/implement/eval/review/outcome
（bootstrap.PHASES 四相位不动）；链 = sha256 快照链而非红绿链——资产内容、
召回包、采用快照每次读取重算复核，来源执行记录的摘要列与快照比对。

接口（调用方必须知道的全部事实）：
- ``workbench-learn-create``：来源准入只收已验收任务（``_task_state``=accepted
  且具名复核在场）；来源快照（任务事实 + 复核 + 执行摘要列引用）整体封存并记
  sha256。family+version 构成版本链，--supersedes 声明替代意图（不强制）。
- ``workbench-learn-govern``：approve/publish/revoke 三决定。操作人必须具名
  人工（拒绝 ai/codex/system/claude/待确认/待定/tbd/pending/agent:*）且不得是
  提炼者；publish 前必须先 approve（跳过审批被拒）；publish 同事务把同 family
  旧 active 版本原子置 superseded（事件带 replacement）。
- ``workbench-learn-recall``：项目过滤 + 仅 active + 关键词 applies 命中
  /excludes 排除 + 同源事项不得独立复用 + 字符预算（默认 12000，超限进
  excluded）+ conflict_key 冲突显式分组（不自动合并）；检索不到如实为空；
  召回包整体落库（RECALL-*）。
- ``workbench-learn-bind``：采用决定必须与本轮召回 matches 逐项对应（adopt
  布尔 + 理由）；采用的资产全文快照封进 BIND-*（plan 与 task 双唯一，一次交付
  最多一条）；零采用的决定集也落账（决定即证据）。
- ``workbench-learn-run``：复验链相位证据（precheck/implement/eval）。不收自报
  哈希——调用方只给账本记录引用（evidence 或 executions），模块自己取账现算
  sha256；引用记录的任务必须与绑定任务一致；每相位只记一次。
- ``workbench-learn-finish``：review + outcome 相位。outcome=passed 强制三相位
  齐全且全部 passed，且复核人具名（缺失报 reuse_acceptance_missing，上游逐字
  口径"复用验证缺少具名验收"）；失败如实落账，memory 类失败不自动撤回资产。
- ``workbench-learn-show``：资产/召回包/绑定检查面，每次读取重算校验。

如实声明（与上游的差距，不冒充对齐）：
- workflow 类资产、feedback/evolution 前置账、HTTP/前端事项工作区：待建设。
- 采用快照不回写任务账事件（上游有任务事件机制，本仓库任务账暂无事件表）；
  跨账互证经绑定 payload 的 task_id 与任务账外键关系人工可追。
- 记忆资产只存引用 + 摘要 + 冻结快照，原始证据本体留在任务账与执行记录。

存储只经 workbench.bootstrap 的单一入口（_connect/_ensure_v0_schema/_sha256），
五表 DDL 单一来源在 bootstrap._LEARNING_SCHEMA（init 建库与旧账迁移共用）；
不建第二套账本。
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import uuid
from pathlib import Path

from .bootstrap import (
    FLOWERP_CONNECTED,
    UNINITIALIZED,
    _connect,
    _emit,
    _ensure_v0_schema,
    _fail,
    _has_table,
    _latest_execution,
    _latest_review,
    _now,
    _require_workbench,
    _sha256,
    _task_state,
)

# 记忆复用链五相位（上游 learning.py 同形）：前三相位经 workbench-learn-run
# 逐相位落账，review/outcome 由 workbench-learn-finish 一次写入。
RUN_PHASES = ("precheck", "implement", "eval", "review", "outcome")
RECORDABLE_RUN_PHASES = ("precheck", "implement", "eval")
DEFAULT_RECALL_BUDGET = 12000
# 具名门槛：拒绝 AI/系统/占位名义（上游 learning.py human() 同词面）。
HUMAN_FORBIDDEN = {"ai", "codex", "system", "claude", "待确认", "待定", "tbd", "pending"}


class LearningError(RuntimeError):
    """合同拒绝（词面进 error 字段，rc=1；不写任何记录）。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


# ---- 基础 ---------------------------------------------------------------


def _dumps(data) -> str:
    """规范序列化：哈希只对规范形计算，保证跨进程复核一致。"""
    return json.dumps(data, ensure_ascii=False, sort_keys=True)


def _is_human(actor: str) -> bool:
    name = (actor or "").strip().lower()
    if not name or name in HUMAN_FORBIDDEN or name.startswith("agent:"):
        return False
    return True


def _require_human(actor: str) -> str:
    name = (actor or "").strip()
    if not _is_human(name):
        raise LearningError("human_actor_required", f"操作人必须具名人工：{actor!r}")
    return name


def _new_id(prefix: str, conn: sqlite3.Connection, table: str, column: str) -> str:
    for _ in range(8):
        candidate = f"{prefix}-{uuid.uuid4().hex[:8]}"
        hit = conn.execute(f"SELECT 1 FROM {table} WHERE {column} = ?", (candidate,)).fetchone()
        if hit is None:
            return candidate
    raise LearningError("id_generation_failed", "标识生成反复冲突，请重试")


def _open_ledger(runtime_dir: str) -> sqlite3.Connection:
    conn = _connect(Path(runtime_dir), create=False)
    if conn is None or _require_workbench(conn) is None:
        raise LearningError("uninitialized", UNINITIALIZED)
    return conn


def _check_task(conn: sqlite3.Connection, task_id: str) -> sqlite3.Row:
    row = conn.execute("SELECT * FROM tasks WHERE task_id = ?", (task_id,)).fetchone()
    if row is None:
        raise LearningError("required_task_missing", f"任务不存在：{task_id}")
    return row


# ---- 完整性复核（读路径恒重算）---------------------------------------------


def _check_payload_integrity(row: sqlite3.Row, what: str) -> dict:
    if _sha256(row["payload"]) != row["sha256"]:
        raise LearningError("memory_content_checksum_failed", f"{what}内容校验失败，快照被改动")
    return json.loads(row["payload"])


def _check_source(conn: sqlite3.Connection, source: dict) -> None:
    """来源引用链复核：快照冻结的摘要列与现账逐一比对，漂移即暴露。"""
    snapshot = source.get("snapshot", {})
    for ref in snapshot.get("execution_refs", []):
        row = None
        if _has_table(conn, "executions"):
            row = conn.execute(
                "SELECT stdout_sha256, stderr_sha256, diff_sha256 FROM executions "
                "WHERE execution_id = ?", (ref["execution_id"],)).fetchone()
        if (row is None or row["stdout_sha256"] != ref["stdout_sha256"]
                or row["stderr_sha256"] != ref["stderr_sha256"]
                or row["diff_sha256"] != ref["diff_sha256"]):
            raise LearningError(
                "source_task_report_changed",
                f"来源任务报告内容已变化：task={source.get('task_id')}")
    review_ref = snapshot.get("review")
    if review_ref and _has_table(conn, "reviews"):
        row = conn.execute(
            "SELECT reviewer, decision FROM reviews WHERE review_id = ?",
            (review_ref["review_id"],)).fetchone()
        if (row is None or row["reviewer"] != review_ref["reviewer"]
                or row["decision"] != review_ref["decision"]):
            raise LearningError(
                "source_task_report_changed",
                f"来源任务报告内容已变化：task={source.get('task_id')}")


def _read_asset(conn: sqlite3.Connection, asset_id: str) -> tuple[sqlite3.Row, dict]:
    row = conn.execute(
        "SELECT * FROM learning_assets WHERE asset_id = ?", (asset_id,)).fetchone()
    if row is None:
        raise LearningError("asset_not_found", f"记忆条目不存在：{asset_id}")
    payload = _check_payload_integrity(row, f"记忆条目 {asset_id} ")
    _check_source(conn, payload.get("source", {}))
    return row, payload


def _row_events(conn: sqlite3.Connection, asset_id: str) -> list[dict]:
    if not _has_table(conn, "learning_events"):
        return []
    rows = conn.execute(
        "SELECT * FROM learning_events WHERE asset_id = ? ORDER BY event_id",
        (asset_id,)).fetchall()
    return [{"actor": r["actor"], "action": r["action"], "note": r["note"],
             "evidence": json.loads(r["evidence"]), "at": r["at"]} for r in rows]


def _append_event(conn: sqlite3.Connection, asset_id: str, actor: str, action: str,
                  note: str = "", evidence: dict | None = None) -> None:
    conn.execute(
        "INSERT INTO learning_events (asset_id, actor, action, note, evidence, at) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (asset_id, actor, action, note, _dumps(evidence or {}), _now()))


# ---- 来源准入与快照 ---------------------------------------------------------


def _admission_snapshot(conn: sqlite3.Connection, task_id: str, project_id: str) -> dict:
    task = _check_task(conn, task_id)
    if task["project_id"] != project_id:
        raise LearningError(
            "project_mismatch",
            f"任务 {task_id} 属于项目 {task['project_id']}，与 --project-id {project_id} 不符")
    if _task_state(conn, task_id) != "accepted":
        raise LearningError(
            "source_task_not_accepted",
            f"来源任务未具名接受（state={_task_state(conn, task_id)}）：{task_id}")
    execution = _latest_execution(conn, task_id)
    review = _latest_review(conn, task_id)
    if execution is None or review is None:
        raise LearningError(
            "source_task_not_accepted", f"来源任务缺少执行或复核记录：{task_id}")
    snapshot = {
        "task_id": task_id,
        "project_id": task["project_id"],
        "request": task["request"],
        "requirement_id": task["requirement_id"],
        "review": {"review_id": review["review_id"], "reviewer": review["reviewer"],
                   "decision": review["decision"], "reviewed_at": review["reviewed_at"]},
        "execution_refs": [{"execution_id": execution["execution_id"],
                            "stdout_sha256": execution["stdout_sha256"],
                            "stderr_sha256": execution["stderr_sha256"],
                            "diff_sha256": execution["diff_sha256"]}],
    }
    return {"task_id": task_id, "snapshot": snapshot,
            "sha256": _sha256(_dumps(snapshot))}


# ---- 七命令 ----------------------------------------------------------------


def _cmd_learn_create(args) -> int:
    conn = _open_ledger(args.runtime_dir)
    try:
        _ensure_v0_schema(conn)
        source = _admission_snapshot(conn, args.task_id, args.project_id)
        version = conn.execute(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM learning_assets WHERE family = ?",
            (args.family,)).fetchone()[0]
        if args.supersedes:
            target = conn.execute(
                "SELECT family FROM learning_assets WHERE asset_id = ?",
                (args.supersedes,)).fetchone()
            if target is None or target["family"] != args.family:
                raise LearningError(
                    "supersedes_target_invalid",
                    f"被替代对象不存在或不属于同 family：{args.supersedes}")
        asset_id = _new_id("ASSET", conn, "learning_assets", "asset_id")
        payload = {
            "id": asset_id, "family": args.family, "version": version,
            "project_id": args.project_id, "kind": "memory", "state": "candidate",
            "title": args.title, "content": args.content,
            "applies": list(args.applies or []), "excludes": list(args.excludes or []),
            "boundary": args.boundary or "", "conflict_key": args.conflict_key or "",
            "supersedes": args.supersedes or "", "source": source,
            "created_by": (args.actor or "").strip(), "created_at": _now(),
        }
        conn.execute(
            "INSERT INTO learning_assets (asset_id, family, version, project_id, kind, "
            "state, approved_by, payload, sha256, created_at) "
            "VALUES (?, ?, ?, ?, 'memory', 'candidate', '', ?, ?, ?)",
            (asset_id, args.family, version, args.project_id,
             _dumps(payload), _sha256(_dumps(payload)), payload["created_at"]))
        _append_event(conn, asset_id, payload["created_by"], "candidate",
                      note=f"从任务 {args.task_id} 提炼",
                      evidence={"source_sha256": source["sha256"]})
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "asset": {"asset_id": asset_id, "family": args.family,
                         "version": version, "state": "candidate",
                         "supersedes": payload["supersedes"],
                         "source": {"task_id": source["task_id"],
                                    "sha256": source["sha256"]}}})
        return 0
    except LearningError as error:
        return _fail(str(error))


def _cmd_learn_govern(args) -> int:
    conn = _open_ledger(args.runtime_dir)
    try:
        _ensure_v0_schema(conn)
        actor = _require_human(args.actor)
        row, payload = _read_asset(conn, args.asset_id)
        if payload.get("created_by", "").strip() == actor:
            raise LearningError(
                "self_govern_rejected", f"提炼者不得自审：{actor}")
        asset_id = row["asset_id"]
        if args.decision == "approve":
            if row["state"] != "candidate" or row["approved_by"]:
                raise LearningError(
                    "invalid_transition",
                    f"approve 仅限未审核的 candidate：当前 state={row['state']}")
            conn.execute(
                "UPDATE learning_assets SET approved_by = ? WHERE asset_id = ?",
                (actor, asset_id))
            _append_event(conn, asset_id, actor, "approve", note=args.note or "")
        elif args.decision == "publish":
            if row["state"] != "candidate":
                raise LearningError(
                    "invalid_transition",
                    f"publish 仅限 candidate：当前 state={row['state']}")
            if not row["approved_by"]:
                raise LearningError(
                    "approve_required", "发布前必须先经具名审核（approve）")
            # 同 family 旧 active 版本同事务原子置 superseded（历史快照不改写）
            olds = conn.execute(
                "SELECT asset_id FROM learning_assets WHERE family = ? AND state = 'active' "
                "AND asset_id != ?", (row["family"], asset_id)).fetchall()
            for old in olds:
                conn.execute(
                    "UPDATE learning_assets SET state = 'superseded' WHERE asset_id = ?",
                    (old["asset_id"],))
                _append_event(conn, old["asset_id"], actor, "superseded",
                              note=f"被 {asset_id} 替代",
                              evidence={"replacement": asset_id})
            conn.execute(
                "UPDATE learning_assets SET state = 'active' WHERE asset_id = ?",
                (asset_id,))
            _append_event(conn, asset_id, actor, "publish",
                          note=args.note or "",
                          evidence={"superseded": [o["asset_id"] for o in olds]})
        elif args.decision == "revoke":
            if row["state"] not in ("candidate", "active"):
                raise LearningError(
                    "invalid_transition",
                    f"该版本已停用（state={row['state']}），不能重复撤回")
            conn.execute(
                "UPDATE learning_assets SET state = 'revoked' WHERE asset_id = ?",
                (asset_id,))
            _append_event(conn, asset_id, actor, "revoke", note=args.note or "")
        else:
            raise LearningError("invalid_transition", f"未知治理决定：{args.decision}")
        state = conn.execute(
            "SELECT state FROM learning_assets WHERE asset_id = ?", (asset_id,)).fetchone()
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "asset": {"asset_id": asset_id, "family": row["family"],
                         "version": row["version"], "state": state["state"]}})
        return 0
    except LearningError as error:
        return _fail(str(error))


def _cmd_learn_recall(args) -> int:
    conn = _open_ledger(args.runtime_dir)
    try:
        task = _check_task(conn, args.task_id)
        if task["project_id"] != args.project_id:
            raise LearningError(
                "project_mismatch",
                f"任务 {args.task_id} 属于项目 {task['project_id']}，"
                f"与 --project-id {args.project_id} 不符")
        budget = args.budget if args.budget is not None else DEFAULT_RECALL_BUDGET
        keywords = [k.strip().lower() for k in (args.keyword or []) if k.strip()]
        rows = conn.execute(
            "SELECT * FROM learning_assets WHERE project_id = ? AND state = 'active' "
            "ORDER BY family, version DESC", (args.project_id,)).fetchall()
        matches: list[dict] = []
        excluded: list[dict] = []
        candidates: list[tuple[int, sqlite3.Row, dict, str, int]] = []
        for row in rows:
            payload = _check_payload_integrity(row, f"记忆条目 {row['asset_id']} ")
            _check_source(conn, payload.get("source", {}))
            if payload.get("source", {}).get("task_id") == args.task_id:
                excluded.append({"asset_id": row["asset_id"],
                                 "reason": "self_source:同源事项不得作为独立复用"})
                continue
            applies = [a.strip().lower() for a in payload.get("applies", []) if a.strip()]
            excludes = [e.strip().lower() for e in payload.get("excludes", []) if e.strip()]
            ex_hits = [k for k in keywords if k in excludes]
            if ex_hits:
                excluded.append({"asset_id": row["asset_id"],
                                 "reason": "excludes:" + ",".join(ex_hits)})
                continue
            if keywords and applies:
                hits = [k for k in keywords if k in applies]
                if not hits:
                    continue  # 关键词不命中：检索面之外，如实不出现在任何分组
                score, reason = len(hits), "applies:" + ",".join(hits)
            else:
                score, reason = 0, "applies:*"  # 无关键词或无适用词：通用条目
            candidates.append((score, row, payload, reason,
                               len(payload.get("content", ""))))
        candidates.sort(key=lambda item: (-item[0], item[1]["family"], -item[1]["version"]))
        budget_used = 0
        conflict_groups: dict[str, list[str]] = {}
        for score, row, payload, reason, content_len in candidates:
            if budget_used + content_len > budget:
                excluded.append({"asset_id": row["asset_id"],
                                 "reason": "budget_exceeded:超出上下文字符预算"})
                continue
            budget_used += content_len
            matches.append({"asset_id": row["asset_id"], "family": row["family"],
                            "version": row["version"], "title": payload.get("title", ""),
                            "reason": reason,
                            "conflict_key": payload.get("conflict_key", "")})
            if payload.get("conflict_key"):
                conflict_groups.setdefault(payload["conflict_key"], []).append(row["asset_id"])
        conflicts = [{"conflict_key": key, "asset_ids": ids,
                      "note": "请逐项判断，不自动合并"}
                     for key, ids in conflict_groups.items() if len(ids) > 1]
        recall_id = _new_id("RECALL", conn, "learning_recalls", "recall_id")
        payload = {"recall_id": recall_id, "project_id": args.project_id,
                   "task_id": args.task_id, "query": list(args.keyword or []),
                   "matches": matches, "excluded": excluded, "conflicts": conflicts,
                   "budget": {"limit": budget, "used": budget_used},
                   "recalled_at": _now()}
        conn.execute(
            "INSERT INTO learning_recalls (recall_id, project_id, task_id, payload, "
            "sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            (recall_id, args.project_id, args.task_id, _dumps(payload),
             _sha256(_dumps(payload)), payload["recalled_at"]))
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED, "recall": payload})
        return 0
    except LearningError as error:
        return _fail(str(error))


def _cmd_learn_bind(args) -> int:
    conn = _open_ledger(args.runtime_dir)
    try:
        _ensure_v0_schema(conn)
        actor = _require_human(args.actor)
        row = conn.execute(
            "SELECT * FROM learning_recalls WHERE recall_id = ?",
            (args.recall_id,)).fetchone()
        if row is None:
            raise LearningError("recall_not_found", f"召回包不存在：{args.recall_id}")
        rp = _check_payload_integrity(row, f"召回包 {args.recall_id} ")
        _check_task(conn, args.task_id)
        if args.task_id != rp.get("task_id"):
            raise LearningError(
                "recall_task_mismatch",
                f"绑定任务 {args.task_id} 与召回包事项 {rp.get('task_id')} 不一致")
        try:
            decisions = json.loads(args.decisions)
        except json.JSONDecodeError as error:
            raise LearningError("decision_mismatch", f"决定集不是合法 JSON：{error}")
        match_ids = [m["asset_id"] for m in rp.get("matches", [])]
        dec_ids = [d.get("asset_id") for d in decisions] if isinstance(decisions, list) else []
        shaped = all(isinstance(d, dict) and isinstance(d.get("adopt"), bool)
                     and isinstance(d.get("reason"), str) for d in decisions) \
            if isinstance(decisions, list) else False
        if (not shaped or len(decisions) != len(match_ids)
                or set(dec_ids) != set(match_ids) or len(set(dec_ids)) != len(dec_ids)):
            raise LearningError(
                "decision_mismatch",
                "采用决定必须与本轮召回 matches 逐项对应（adopt 布尔 + 理由，不重不漏）")
        dup = conn.execute(
            "SELECT 1 FROM learning_bindings WHERE task_id = ? OR plan_id = ?",
            (args.task_id, args.plan_id)).fetchone()
        if dup is not None:
            raise LearningError(
                "binding_exists", "一次交付最多绑定一条（task 与 plan 双唯一）")
        assets = []
        for decision in decisions:
            if not decision["adopt"]:
                continue
            arow, apayload = _read_asset(conn, decision["asset_id"])
            if apayload.get("project_id") != rp.get("project_id"):
                raise LearningError(
                    "project_mismatch",
                    f"跨项目采用被拒：{arow['asset_id']} 属于 {apayload.get('project_id')}")
            if arow["state"] != "active":
                raise LearningError(
                    "asset_not_active",
                    f"记忆条目非 active 不得采用：{arow['asset_id']} state={arow['state']}")
            assets.append({"asset_id": arow["asset_id"], "family": arow["family"],
                           "version": arow["version"], "snapshot": apayload,
                           "sha256": arow["sha256"]})
        binding_id = _new_id("BIND", conn, "learning_bindings", "binding_id")
        payload = {"id": binding_id, "recall_id": args.recall_id, "plan_id": args.plan_id,
                   "task_id": args.task_id, "decisions": decisions, "assets": assets,
                   "bound_by": actor, "bound_at": _now()}
        conn.execute(
            "INSERT INTO learning_bindings (binding_id, recall_id, plan_id, task_id, "
            "payload, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (binding_id, args.recall_id, args.plan_id, args.task_id, _dumps(payload),
             _sha256(_dumps(payload)), payload["bound_at"]))
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "binding": {"binding_id": binding_id, "recall_id": args.recall_id,
                           "plan_id": args.plan_id, "task_id": args.task_id,
                           "decisions": decisions,
                           "assets": [{k: a[k] for k in ("asset_id", "family", "version",
                                                         "sha256")} for a in assets]}})
        return 0
    except LearningError as error:
        return _fail(str(error))


def _evidence_text(conn: sqlite3.Connection, table: str, record_id: int) -> tuple[str, str]:
    """取账本记录的权威文本并返回 (task_id, 文本)；模块自己现算摘要，不收自报哈希。"""
    if table == "evidence":
        row = conn.execute(
            "SELECT task_id, output_text FROM evidence WHERE record_id = ?",
            (record_id,)).fetchone()
        if row is None:
            raise LearningError("evidence_record_missing", f"证据记录不存在：{record_id}")
        return row["task_id"], row["output_text"]
    row = conn.execute(
        "SELECT task_id, stdout_text, stderr_text FROM executions WHERE execution_id = ?",
        (record_id,)).fetchone()
    if row is None:
        raise LearningError("evidence_record_missing", f"执行记录不存在：{record_id}")
    return row["task_id"], row["stdout_text"] + "\n" + row["stderr_text"]


def _cmd_learn_run(args) -> int:
    conn = _open_ledger(args.runtime_dir)
    try:
        _ensure_v0_schema(conn)
        row = conn.execute(
            "SELECT * FROM learning_bindings WHERE binding_id = ?",
            (args.binding_id,)).fetchone()
        if row is None:
            raise LearningError("binding_not_found", f"采用快照不存在：{args.binding_id}")
        payload = _check_payload_integrity(row, f"采用快照 {args.binding_id} ")
        task_id, text = _evidence_text(conn, args.evidence_table, args.evidence_record_id)
        if task_id != payload.get("task_id"):
            raise LearningError(
                "evidence_task_mismatch",
                f"证据记录属于任务 {task_id}，与绑定任务 {payload.get('task_id')} 不符")
        dup = conn.execute(
            "SELECT 1 FROM learning_runs WHERE binding_id = ? AND phase = ?",
            (args.binding_id, args.phase)).fetchone()
        if dup is not None:
            raise LearningError(
                "phase_already_recorded", f"相位已记录，只记一次：{args.phase}")
        run_payload = {"result": args.result, "summary": args.summary or "",
                       "evidence": {"table": args.evidence_table,
                                    "record_id": args.evidence_record_id,
                                    "sha256": _sha256(text)}}
        at = _now()
        conn.execute(
            "INSERT INTO learning_runs (binding_id, phase, payload, at) VALUES (?, ?, ?, ?)",
            (args.binding_id, args.phase, _dumps(run_payload), at))
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "run": {"binding_id": args.binding_id, "phase": args.phase,
                       "result": args.result, "evidence": run_payload["evidence"],
                       "at": at}})
        return 0
    except LearningError as error:
        return _fail(str(error))


def _cmd_learn_finish(args) -> int:
    conn = _open_ledger(args.runtime_dir)
    try:
        _ensure_v0_schema(conn)
        row = conn.execute(
            "SELECT * FROM learning_bindings WHERE binding_id = ?",
            (args.binding_id,)).fetchone()
        if row is None:
            raise LearningError("binding_not_found", f"采用快照不存在：{args.binding_id}")
        payload = _check_payload_integrity(row, f"采用快照 {args.binding_id} ")
        reviewer = (args.reviewer or "").strip()
        if args.outcome == "passed" and not reviewer:
            raise LearningError(
                "reuse_acceptance_missing", "复用验证缺少具名验收")
        reviewer = _require_human(reviewer)
        for phase in RECORDABLE_RUN_PHASES:
            run_row = conn.execute(
                "SELECT payload FROM learning_runs WHERE binding_id = ? AND phase = ?",
                (args.binding_id, phase)).fetchone()
            if run_row is None:
                raise LearningError("missing_phase", f"复验链缺相位：{phase}")
            if args.outcome == "passed" and json.loads(run_row["payload"]).get("result") != "passed":
                raise LearningError(
                    "phase_not_passed", f"存在未通过相位，不得标记通过：{phase}")
        for phase, run_payload in (
                ("review", {"reviewer": reviewer, "note": args.note or ""}),
                ("outcome", {"outcome": args.outcome, "reviewer": reviewer,
                             "note": args.note or ""})):
            dup = conn.execute(
                "SELECT 1 FROM learning_runs WHERE binding_id = ? AND phase = ?",
                (args.binding_id, phase)).fetchone()
            if dup is not None:
                raise LearningError(
                    "phase_already_recorded", f"相位已记录，只记一次：{phase}")
            conn.execute(
                "INSERT INTO learning_runs (binding_id, phase, payload, at) "
                "VALUES (?, ?, ?, ?)",
                (args.binding_id, phase, _dumps(run_payload), _now()))
        conn.commit()
        _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
               "finish": {"binding_id": args.binding_id, "outcome": args.outcome,
                          "reviewer": reviewer, "phases": list(RUN_PHASES)}})
        return 0
    except LearningError as error:
        return _fail(str(error))


def _cmd_learn_show(args) -> int:
    conn = _open_ledger(args.runtime_dir)
    try:
        targets = [t for t in (args.asset_id, args.recall_id, args.binding_id) if t]
        if len(targets) != 1:
            raise LearningError(
                "show_target_required", "--asset-id / --recall-id / --binding-id 恰给一个")
        if args.asset_id:
            row, payload = _read_asset(conn, args.asset_id)
            _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
                   "asset": {"asset_id": row["asset_id"], "family": row["family"],
                             "version": row["version"], "state": row["state"],
                             "payload": payload,
                             "events": _row_events(conn, row["asset_id"])}})
        elif args.recall_id:
            row = conn.execute(
                "SELECT * FROM learning_recalls WHERE recall_id = ?",
                (args.recall_id,)).fetchone()
            if row is None:
                raise LearningError("recall_not_found", f"召回包不存在：{args.recall_id}")
            _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
                   "recall": _check_payload_integrity(row, f"召回包 {args.recall_id} ")})
        else:
            row = conn.execute(
                "SELECT * FROM learning_bindings WHERE binding_id = ?",
                (args.binding_id,)).fetchone()
            if row is None:
                raise LearningError("binding_not_found", f"采用快照不存在：{args.binding_id}")
            _emit({"ok": True, "flowerp_connected": FLOWERP_CONNECTED,
                   "binding": _check_payload_integrity(row, f"采用快照 {args.binding_id} ")})
        return 0
    except LearningError as error:
        return _fail(str(error))


# ---- REGISTRY 缝注册（与 bootstrap/spec/execution 同形）--------------------


def _register_learn_create(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-learn-create", help="从已验收任务提炼记忆候选")
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--task-id", required=True, help="来源任务（须已具名接受）")
    parser.add_argument("--family", required=True, help="记忆 family（版本链键）")
    parser.add_argument("--title", required=True)
    parser.add_argument("--content", required=True)
    parser.add_argument("--applies", action="append", default=[], help="适用关键词，可多次")
    parser.add_argument("--excludes", action="append", default=[], help="排除关键词，可多次")
    parser.add_argument("--boundary", default="", help="适用与不适用边界说明")
    parser.add_argument("--conflict-key", default="", help="冲突主题（同键召回时显式分组）")
    parser.add_argument("--supersedes", default="", help="声明替代的旧版本 asset_id")
    parser.add_argument("--actor", required=True, help="提炼者（具名）")
    parser.set_defaults(handler=_cmd_learn_create)


def _register_learn_govern(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-learn-govern", help="记忆条目具名治理决定")
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--asset-id", required=True)
    parser.add_argument("--decision", required=True,
                        choices=("approve", "publish", "revoke"))
    parser.add_argument("--actor", required=True, help="审核人（具名人工，不得是提炼者）")
    parser.add_argument("--note", default="")
    parser.set_defaults(handler=_cmd_learn_govern)


def _register_learn_recall(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-learn-recall", help="按项目与关键词召回记忆")
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--task-id", required=True, help="召回发起事项（同源被排除）")
    parser.add_argument("--keyword", action="append", default=[], help="召回关键词，可多次")
    parser.add_argument("--budget", type=int, default=None,
                        help=f"上下文字符预算（默认 {DEFAULT_RECALL_BUDGET}）")
    parser.set_defaults(handler=_cmd_learn_recall)


def _register_learn_bind(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-learn-bind", help="逐项采用决定并封存快照")
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--recall-id", required=True)
    parser.add_argument("--task-id", required=True, help="绑定交付任务（须与召回事项一致）")
    parser.add_argument("--plan-id", required=True, help="交付计划标识（唯一）")
    parser.add_argument("--decisions", required=True,
                        help='决定集 JSON：[{"asset_id":..,"adopt":true,"reason":..}]')
    parser.add_argument("--actor", required=True, help="采用决定人（具名人工）")
    parser.set_defaults(handler=_cmd_learn_bind)


def _register_learn_run(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-learn-run", help="记录复验链相位证据")
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--binding-id", required=True)
    parser.add_argument("--phase", required=True, choices=RECORDABLE_RUN_PHASES)
    parser.add_argument("--result", required=True, choices=("passed", "failed"))
    parser.add_argument("--evidence-table", required=True, choices=("evidence", "executions"))
    parser.add_argument("--evidence-record-id", required=True, type=int)
    parser.add_argument("--summary", default="")
    parser.set_defaults(handler=_cmd_learn_run)


def _register_learn_finish(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-learn-finish", help="回写复验结果（review+outcome）")
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--binding-id", required=True)
    parser.add_argument("--outcome", required=True, choices=("passed", "failed"))
    parser.add_argument("--reviewer", required=True, help="复验验收人（具名人工）")
    parser.add_argument("--note", default="")
    parser.set_defaults(handler=_cmd_learn_finish)


def _register_learn_show(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("workbench-learn-show", help="检查记忆资产/召回包/采用快照")
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--asset-id", default="")
    parser.add_argument("--recall-id", default="")
    parser.add_argument("--binding-id", default="")
    parser.set_defaults(handler=_cmd_learn_show)


def register_commands(registry: dict) -> None:
    """REGISTRY 缝第三批注册（与 bootstrap.register_commands 同形）。"""
    registry.update({
        "workbench-learn-create": _register_learn_create,
        "workbench-learn-govern": _register_learn_govern,
        "workbench-learn-recall": _register_learn_recall,
        "workbench-learn-bind": _register_learn_bind,
        "workbench-learn-run": _register_learn_run,
        "workbench-learn-finish": _register_learn_finish,
        "workbench-learn-show": _register_learn_show,
    })
