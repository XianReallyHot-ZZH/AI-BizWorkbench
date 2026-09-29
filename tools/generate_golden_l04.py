"""L04 对照基准（golden）生成器：用冻结的 Python 工作台采出规范化场景输出（L04 讲义附录 A3）。

单一会话 ~47 个场景按序驱动真实 CLI 子进程（与合同测试同形，不绕入口直调），
原始 stdout 落 `.runtime/golden-l04/raw/`（gitignore，不入库），规范化后落
`src/test/resources/golden/l04/`（随 Git 提交，生成后永不手改；要变只能重生成并留证据）。

场景面 = 受控执行三命令（workbench-task-run / -review / -show）的行为语义
（L04 讲义附录 A3 八组）：单账本顺序叙事；拒绝类不落记录故 execution_id/review_id
序确定；执行类场景各用独立候选仓（setup 块物化的 git 仓库）避免状态串扰。
任务编号按创建序字典序命名（T-GOLDEN-L04-01..10）：冻结 `_now()` 为秒级精度，
同秒创建的任务由 task_id 字典序决胜，而同秒组组成随跑随机——字典序=创建序使
status/task-show 投影的 (created_at, task_id) 排序跨跑确定（首两跑指纹漂移的根因修复，
漂移指纹如实记录于 evidence/L04-java.md §G）。
golden l04 与 l03 同型：重放的正是本讲目标能力（V0），Java 重放测试在候选 commit 1
为红点参与者、commit 2 转绿即字节级对照达成。

掩码块（l04 特有，与 l01–l03 的口径分歧逐字写入 manifest 的 mask_note）：
- `observed_at`/`reviewed_at` → `<TS>`：本讲场景面系机器生成（`_now()`），不可复现——
  与 l01/l02 的「observed_at 是操作者合同字段，逐字保留」不同；
- `workspace` → `<WORKSPACE>`：执行记录含 `workspace.resolve()` 机器绝对路径；
- `created_at`/`recorded_at`/`workbench_id` 与三套统一。

不入 golden（语言绑定或非确定，附录 A3/JD6）：launch error 的 errno 词面、argparse
用法错（stderr + rc 2，stdout 空）、真实 `claude -p`（替身 sh 命令入 golden）。

规范化规则（canonical JSON 与 l01–l03 逐字相同）：
- JSON 输出：按 manifest normalization 块声明掩码后
  `json.dumps(sort_keys=True, ensure_ascii=False, indent=2)` + 末尾换行；
- 非 JSON 输出：逐字保留（防御性规则）。

setup 块：候选仓等非文本夹具的物化规格（write_file / run 两类步骤），生成器与
Java 重放测试按同一规格执行；提交哈希不出现在任何输出，diff index 行为内容派生。

确定性证据：生成器幂等（先清场再采），连续两跑 golden 目录 SHA-256 必须一致
（首跑即留指纹——L03 首跑漏留教训）；双跑比对结果记入 `docs/replication/evidence/L04-java.md` §G。
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
WORK_ROOT = REPO_ROOT / ".runtime" / "golden-l04"
RAW_DIR = WORK_ROOT / "raw"
INPUTS = WORK_ROOT / "inputs"
WS = WORK_ROOT / "ws"
GOLDEN_DIR = REPO_ROOT / "src" / "test" / "resources" / "golden" / "l04"

RT = ".runtime/golden-l04/rt"
DB = ".runtime/golden-l04/rt/workbench.db"
PROMPT = ".runtime/golden-l04/inputs/prompt.txt"


def ws_rel(index: int) -> str:
    return f".runtime/golden-l04/ws/c{index}"


# 执行器替身命令（单串 shell 形，经实现 shlex 解析；固定输出，无墙钟/无机器路径）
EXEC_WRITE = "sh -c 'printf v1 > delivery.csv; printf created\\n'"
EXEC_SNEAK = "sh -c 'printf x > sneaked.txt; printf v1 > delivery.csv; printf both\\n'"
EXEC_FAIL = "sh -c 'echo boom-out; echo boom-err >&2; exit 3'"
EXEC_TIMEOUT = "sh -c 'printf partial-out; printf partial-err >&2; sleep 30'"
EXEC_NOTHING = "sh -c 'exit 0'"
EVAL_OK = "sh -c 'exit 0'"
EVAL_FAIL = "sh -c 'echo eval-says-no >&2; exit 1'"
EVAL_FAIL_QUIET = "sh -c 'exit 7'"
EVAL_SLOW = "sh -c 'sleep 30'"
EVAL_BAD = "sh -c 'unbalanced"

MASKS = {
    "created_at": "<TS>",
    "recorded_at": "<TS>",
    "observed_at": "<TS>",
    "reviewed_at": "<TS>",
    "workspace": "<WORKSPACE>",
    "workbench_id": "<WORKBENCH_ID>",
}


def task_create_argv(task_id: str, *, request: str, prerequisite: str = "") -> list[str]:
    argv = ["workbench-task-create", "--runtime-dir", RT, "--project-id", "G-PROJ",
            "--task-id", task_id, "--requirement-id", "REQ-GOLDEN-L04",
            "--request", request, "--actor", "G-ACTOR"]
    if prerequisite:
        argv += ["--prerequisite-task", prerequisite]
    return argv


def task_run_argv(task_id: str, *, mode: str, workspace: str, scope: list[str] | None = None,
                  executor: str | None = None, prompt: str | None = PROMPT,
                  eval_command: str = EVAL_OK, timeout: int | None = 30,
                  actor: str = "G-EXECUTOR") -> list[str]:
    argv = ["workbench-task-run", task_id, "--runtime-dir", RT,
            "--workspace", workspace, "--mode", mode]
    if scope:
        argv += ["--write-scope", *scope]
    if executor is not None:
        argv += ["--executor-command", executor]
    if prompt is not None:
        argv += ["--executor-prompt-file", prompt]
    argv += ["--eval-command", eval_command]
    if timeout is not None:
        argv += ["--execution-timeout", str(timeout)]
    argv += ["--actor", actor]
    return argv


def task_review_argv(task_id: str, *, reviewer: str, decision: str, note: str = "") -> list[str]:
    argv = ["workbench-task-review", task_id, "--runtime-dir", RT,
            "--reviewer", reviewer, "--decision", decision]
    if note:
        argv += ["--note", note]
    return argv


# ---- setup 规格：候选仓等非文本夹具（生成器与 Java 重放按同一规格执行）------------
SETUP_STEPS: list[dict] = []
for index in range(1, 8):
    rel = ws_rel(index)
    SETUP_STEPS.append({"write_file": f"golden-l04/ws/c{index}/seed.txt", "content": "seed-v1\n"})
    SETUP_STEPS.append({"run": ["git", "init", "-q", rel]})
    SETUP_STEPS.append({"run": ["git", "-C", rel, "add", "."]})
    SETUP_STEPS.append({"run": ["git", "-C", rel, "-c", "user.name=G", "-c", "user.email=golden@example",
                                "-c", "commit.gpgsign=false", "commit", "-q", "-m", "seed"]})
SETUP_STEPS.append({"write_file": "golden-l04/ws/nogit/seed.txt", "content": "seed-v1\n"})
SETUP_STEPS.append({"run": ["git", "init", "-q", ".runtime/golden-l04/ws/nohead"]})


def _run_setup() -> None:
    for step in SETUP_STEPS:
        if "write_file" in step:
            target = REPO_ROOT / ".runtime" / step["write_file"]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(step["content"], encoding="utf-8")
        else:
            proc = subprocess.run(step["run"], cwd=str(REPO_ROOT),
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if proc.returncode != 0:
                raise SystemExit(f"setup 步骤失败 rc={proc.returncode}: {step['run']}\n"
                                 f"{proc.stderr.decode('utf-8', errors='replace')}")


def _run_cli(*argv: str) -> tuple[int, str, str]:
    proc = subprocess.run(
        [sys.executable, "-X", "utf8", "-m", "workbench.cli", *argv],
        cwd=str(REPO_ROOT), capture_output=True,
    )
    return proc.returncode, proc.stdout.decode("utf-8"), proc.stderr.decode("utf-8")


def _mask(node):
    if isinstance(node, dict):
        return {key: (MASKS[key] if key in MASKS else _mask(value))
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


def main() -> int:
    # 清场：golden 与运行库/夹具一律重采，不做增量
    shutil.rmtree(WORK_ROOT, ignore_errors=True)
    shutil.rmtree(GOLDEN_DIR, ignore_errors=True)
    INPUTS.mkdir(parents=True)
    RAW_DIR.mkdir()
    GOLDEN_DIR.mkdir(parents=True)

    records: list[dict] = []

    def capture(scenario_id: str, expected_rc: int, *argv: str,
                note: str = "", tamper_before: dict | None = None) -> None:
        if tamper_before is not None:
            conn = sqlite3.connect(REPO_ROOT / tamper_before["db"])
            try:
                conn.execute(tamper_before["sql"])
                conn.commit()
            finally:
                conn.close()
        returncode, stdout, stderr = _run_cli(*argv)
        (RAW_DIR / f"{scenario_id}.stdout.txt").write_text(stdout, encoding="utf-8")
        (RAW_DIR / f"{scenario_id}.stderr.txt").write_text(stderr, encoding="utf-8")
        status = "ok" if returncode == expected_rc else "MISMATCH"
        records.append({"id": scenario_id, "argv": list(argv), "exit_code": returncode,
                        "expected_exit_code": expected_rc, "stdout_file": f"{scenario_id}.stdout.txt",
                        **({"note": note} if note else {}),
                        **({"tamper_before": tamper_before} if tamper_before else {})})
        print(f"[{status}] {scenario_id} rc={returncode}（预期 {expected_rc}）")
        if status != "ok":
            raise SystemExit(f"{scenario_id} 退出码偏离预期，中止生成（旧文件已保留供排查）")
        (GOLDEN_DIR / f"{scenario_id}.stdout.txt").write_text(_normalize(stdout), encoding="utf-8")

    # ---- 输入固定件（执行器提示词；不可读路径 no-such-prompt.txt 故意缺席）------
    prompt_text = "按绑定合同在允许写集内交付，完成后停止推进等待人审。\n"
    (INPUTS / "prompt.txt").write_text(prompt_text, encoding="utf-8")

    # ---- 候选仓等夹具（setup 规格）------------------------------------------
    _run_setup()

    # ---- 场景（s01–s47，八组；单账本顺序叙事，拒绝类不落记录）----------------
    # 组一：状态构建
    capture("s01_init_ok", 0, "workbench-init", "--runtime-dir", RT,
            "--name", "对照基准工作台", "--owner", "G-OWNER")
    capture("s02_project_add_ok", 0, "workbench-project-add", "--runtime-dir", RT,
            "--project-id", "G-PROJ", "--name", "受控执行对照项目", "--path", ".",
            "--purpose", "golden l04 对照")
    capture("s03_task_create_a_ok", 0, *task_create_argv("T-GOLDEN-L04-01",
            request="受控执行对照基准 A 票"))
    # 组二：C9 前置绑定拒绝面
    capture("s04_prerequisite_missing_rejected", 1,
            *task_create_argv("T-GOLDEN-L04-X", request="前置不存在应拒绝",
                              prerequisite="T-NOPE"),
            note="C9：prerequisite_task_missing，任务未创建")
    capture("s05_prerequisite_not_accepted_rejected", 1,
            *task_create_argv("T-GOLDEN-L04-X", request="前置未接受应拒绝",
                              prerequisite="T-GOLDEN-L04-01"),
            note="C9：prerequisite_not_accepted（此时 T-A 尚无执行，不算已接受）")
    # 组三：code 正常执行（C3 全量记录形）→ review
    capture("s06_code_run_completed_to_review", 0,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(1),
                           scope=["delivery.csv"], executor=EXEC_WRITE),
            note="C3：invocation/write_scope/change_manifest（added，前值 null）/diff/双流 SHA；eval 绿停 review")
    # 组四：启动拒绝面（不落记录，均先于 review 态检查）
    capture("s07_verify_rejects_executor_command", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="verify", workspace=ws_rel(1),
                           executor=EXEC_NOTHING),
            note="仅复验模式不调用执行器进程")
    capture("s08_write_scope_required", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(1),
                           scope=None, executor=EXEC_WRITE),
            note="code 模式缺写集拒绝启动")
    capture("s09_executor_command_required", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(1),
                           scope=["delivery.csv"], executor=None),
            note="code 模式缺执行器命令拒绝启动")
    capture("s10_execution_timeout_required", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(1),
                           scope=["delivery.csv"], executor=EXEC_WRITE, timeout=None),
            note="预算是执行器合同组成部分：缺显式超时声明拒绝启动")
    capture("s11_eval_command_unparseable", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(1),
                           scope=["delivery.csv"], executor=EXEC_WRITE, eval_command=EVAL_BAD),
            note="shell 形命令串解析失败（不平衡引号）")
    capture("s12_executor_command_unparseable", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(1),
                           scope=["delivery.csv"], executor=EVAL_BAD),
            note="执行器命令串解析失败")
    capture("s13_required_actor_missing", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(1),
                           scope=["delivery.csv"], executor=EXEC_WRITE, actor=" "),
            note="执行发起人必须具名")
    capture("s14_required_task_missing", 1,
            *task_run_argv("T-NOPE", mode="verify", workspace=ws_rel(1)),
            note="任务不存在，记录未追加")
    capture("s15_workspace_missing_rejected", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="verify", workspace=".runtime/golden-l04/ws/ghost"),
            note="工作目录不存在，拒于进程前")
    capture("s16_workspace_without_git_rejected", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="verify", workspace=".runtime/golden-l04/ws/nogit"),
            note="非 git 候选（缺 .git），拒于进程前")
    capture("s17_workspace_without_head_rejected", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="verify", workspace=".runtime/golden-l04/ws/nohead"),
            note="候选没有起点提交（缺 HEAD）：Diff/前值摘要无从谈起")
    capture("s18_executor_prompt_unreadable", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(1),
                           scope=["delivery.csv"], executor=EXEC_WRITE,
                           prompt=".runtime/golden-l04/inputs/no-such-prompt.txt"),
            note="提示词文件不可读取，执行未启动")
    # 组五：复核门（C8 全门）
    capture("s19_task_in_review_rejects_rerun", 1,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(2),
                           scope=["delivery.csv"], executor=EXEC_WRITE),
            note="review 态拒绝再次运行且不毁待审状态（c2 未被触碰）")
    capture("s20_self_review_rejected", 1,
            *task_review_argv("T-GOLDEN-L04-01", reviewer="G-EXECUTOR", decision="approve"),
            note="执行者不能批准自己（名义级比较）")
    capture("s21_required_reviewer_missing", 1,
            *task_review_argv("T-GOLDEN-L04-01", reviewer=" ", decision="approve"),
            note="复核人必须具名")
    capture("s22_review_approve_accepts", 0,
            *task_review_argv("T-GOLDEN-L04-01", reviewer="G-REVIEWER", decision="approve",
                              note="对照基准复核：接受第一次执行"),
            note="复核按 execution_id 锚定；task_state → accepted")
    capture("s23_code_rerun_after_accept_invalidates", 0,
            *task_run_argv("T-GOLDEN-L04-01", mode="code", workspace=ws_rel(2),
                           scope=["delivery.csv"], executor=EXEC_WRITE),
            note="C8 版本锚定：接受后再执行，旧接受失效回 review")
    capture("s24_review_approve_accepts_again", 0,
            *task_review_argv("T-GOLDEN-L04-01", reviewer="G-REVIEWER", decision="approve",
                              note="对照基准复核：接受第二次执行"),
            note="approve/reject 只追加")
    capture("s25_task_create_j_ok", 0, *task_create_argv("T-GOLDEN-L04-02", request="复验对照任务"))
    capture("s26_verify_completed", 0,
            *task_run_argv("T-GOLDEN-L04-02", mode="verify", workspace=ws_rel(1),
                           eval_command=EVAL_OK, actor="G-OPERATOR"),
            note="C5 仅复验：executor_command 为 null 键形；task_state → review")
    capture("s27_review_reject", 0,
            *task_review_argv("T-GOLDEN-L04-02", reviewer="G-REVIEWER", decision="reject",
                              note="对照基准复核：退回"),
            note="reject → task_state rejected")
    capture("s28_prerequisite_accepted_ok", 0,
            *task_create_argv("T-GOLDEN-L04-03", request="B 票对照任务",
                              prerequisite="T-GOLDEN-L04-01"),
            note="C9 正面：A 已具名接受后放行，prerequisite_task_id 入账")
    # 组六：code 异常四态 + eval 可分辨性
    capture("s29_task_create_c_ok", 0, *task_create_argv("T-GOLDEN-L04-04", request="越界对照任务"))
    capture("s30_out_of_scope_stops_before_eval", 1,
            *task_run_argv("T-GOLDEN-L04-04", mode="code", workspace=ws_rel(3),
                           scope=["delivery.csv"], executor=EXEC_SNEAK),
            note="越界：eval 前停止（eval 键缺位形）、不自动回滚、越界文件留证")
    capture("s31_task_create_d_ok", 0, *task_create_argv("T-GOLDEN-L04-05", request="失败对照任务"))
    capture("s32_executor_failure_preserved", 1,
            *task_run_argv("T-GOLDEN-L04-05", mode="code", workspace=ws_rel(4),
                           scope=["delivery.csv"], executor=EXEC_FAIL),
            note="C6：执行器失败不冒充成功，stdout/stderr 原文入账（rc 3）")
    capture("s33_task_create_e_ok", 0, *task_create_argv("T-GOLDEN-L04-06", request="超时对照任务"))
    capture("s34_timeout_preserves_partial_output", 1,
            *task_run_argv("T-GOLDEN-L04-06", mode="code", workspace=ws_rel(5),
                           scope=["delivery.csv"], executor=EXEC_TIMEOUT, timeout=1),
            note="超时：Eval 前停止，部分输出与 Diff 保留")
    capture("s35_task_create_f_ok", 0, *task_create_argv("T-GOLDEN-L04-07", request="eval 失败对照任务"))
    capture("s36_eval_failure_distinct", 1,
            *task_run_argv("T-GOLDEN-L04-07", mode="code", workspace=ws_rel(6),
                           scope=["delivery.csv"], executor=EXEC_WRITE, eval_command=EVAL_FAIL),
            note="eval 业务失败如实落账（eval_timed_out 为 false）")
    capture("s37_task_create_g_ok", 0, *task_create_argv("T-GOLDEN-L04-08", request="eval 超时对照任务"))
    capture("s38_eval_timeout_distinguishable", 1,
            *task_run_argv("T-GOLDEN-L04-08", mode="code", workspace=ws_rel(7),
                           scope=["delivery.csv"], executor=EXEC_NOTHING,
                           eval_command=EVAL_SLOW, timeout=1),
            note="eval 超时与业务失败可分辨（eval_timed_out 为 true，复查轮 S-c6）")
    # 组七：verify 面补全
    capture("s39_task_create_h_ok", 0, *task_create_argv("T-GOLDEN-L04-09", request="复验失败对照任务"))
    capture("s40_verify_eval_failure", 1,
            *task_run_argv("T-GOLDEN-L04-09", mode="verify", workspace=ws_rel(1),
                           eval_command=EVAL_FAIL_QUIET, actor="G-OPERATOR"),
            note="verify 模式 eval 失败 → eval_failed（verify 形）")
    capture("s41_review_eval_failed_rejected", 1,
            *task_review_argv("T-GOLDEN-L04-07", reviewer="G-REVIEWER", decision="approve"),
            note="eval_failed 态不处于人工复核态，不能复核")
    capture("s42_task_create_i_ok", 0, *task_create_argv("T-GOLDEN-L04-10", request="无执行对照任务"))
    capture("s43_review_without_execution_rejected", 1,
            *task_review_argv("T-GOLDEN-L04-10", reviewer="G-REVIEWER", decision="approve"),
            note="任务尚无执行记录，不能复核")
    # 组八：摘要与账面
    capture("s44_task_show_full", 0, "workbench-task-show", "T-GOLDEN-L04-01", "--runtime-dir", RT,
            note="摘要：状态、两条执行记录与两条复核记录（掩码后字节锁）")
    capture("s45_task_show_missing", 1, "workbench-task-show", "T-NOPE", "--runtime-dir", RT)
    capture("s46_status_full_ledger", 0, "workbench-status", "--runtime-dir", RT,
            note="全账面投影：10 任务 + 全部执行/复核记录（掩码后字节锁）")
    capture("s47_status_after_tamper", 1, "workbench-status", "--runtime-dir", RT,
            note="篡改 change_manifest 后 status 重算 SHA-256 复核：execution_digest_mismatch（复查轮 S-c1）",
            tamper_before={"db": DB,
                           "sql": "UPDATE executions SET change_manifest = '[]'"
                                  " WHERE task_id = 'T-GOLDEN-L04-01' AND execution_id = 1"})

    # ---- manifest（确定性内容，不含墙钟）-----------------------------------
    manifest = {
        "generated_from": "frozen Python workbench @ master（见 evidence/L04-java.md §G 记录的 commit）",
        "generator": "tools/generate_golden_l04.py",
        "normalization": {
            "mask_fields": MASKS,
            "mask_note": "l04 特有：observed_at/reviewed_at 系本讲场景面机器生成（_now()），不可复现，"
                         "故掩码——与 l01/l02 的「observed_at 是操作者合同字段，逐字保留」不同；"
                         "workspace 执行记录含机器绝对路径（resolve()），掩码为 <WORKSPACE>；"
                         "created_at/recorded_at/workbench_id 与 l01/l02/l03 三套统一",
            "canonical_json": "json.dumps(sort_keys=True, ensure_ascii=False, indent=2) + 换行",
            "non_json": "逐字保留",
        },
        "setup": SETUP_STEPS,
        "inputs": {"golden-l04/inputs/prompt.txt": prompt_text},
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
