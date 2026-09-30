"""L06 对照基准（golden）生成器：用冻结 Python 的 evals 统一运行器面采出规范化场景输出（L06 讲义附录 A3）。

与 l05 同形：场景面是我方工具 stdout，生成侧驱动冻结 Python 工具，重放侧驱动 Java 对应物
（workbench.evals.l06.* 三 main，统一运行器由 workbench.evals 根 EvalHarness/ReportContract
承载，不进 REGISTRY），场景带 main 字段指明重放主类。本套三处增量（附录 A3/JD3/JD4）：
- 场景面首次含**统一运行器全量报告**（s07/s08：checks 的 stdout 即报告全文；报告文件不入
  golden——文件面/x 模式由 Java 合同测试锁，GoldenReplay 无文件对照缝）；
- 掩码集 l01–l05 以来首例扩展 2 键：generated_at / duration_ms（报告机器时间字段）；
- 场景可选 env 字段（s07/s08 注入 L06_EVAL_TARGET——Checks 读环境变量是冻结 Python 语义，
  argv 面不含 --target）；重放侧共享件 GoldenReplay/Cli 扩展对应（JD4，行为零变化）。

9 场景（单会话按序实跑，s01 构造产物即 s05/s08 被测树——顺序叙事）：
- s01 构造器成功（clone + CSV available→on_hand 补丁 + 自检 JSON：defect_live/query 5/csv 8）
- s02 构造器对已存在基线拒绝（防覆盖，stdout 空）
- s03 驱动默认 8/3 pass（[8,3,5]、拒 6、状态不变）
- s04 驱动 13/4 pass（[13,4,9]、拒 10——C1 迁移，排除写死 5）
- s05 驱动对缺陷树 fail（AC-AVAILABLE：query [8,3,5] / csv [8,3,8] + requirement 逐字）
- s06 驱动 --target 无 flowerp/ 拒绝（rc 2，参数校验面）
- s07 checks 绿报告（clean 树：四 blocking 绿 + 教学告警，decision pass）
- s08 checks 红报告（缺陷树：l06_stock_consistency 红（evidence=rc=1: {AC-AVAILABLE …}）
  + decision block——可信红面字节锁）
- s09 checks 缺 env 拒绝（stdout 空；rc 1 与「主类缺失」偶然同形——l05 修正注③同款口径，
  红点由其余场景与合同测试承载）

掩码（逐字写入 manifest mask_note）：本套扩展 generated_at→<TS>、duration_ms→<MS>
（s07/s08 报告机器时间字段；其余场景无这些键，键级掩码无扰）；created_at/recorded_at/
workbench_id 三件套声明保留与五套统一（防御性，本套输出无这些键）。

不入 golden（附录 A3/JD6）：客户 eval 直跑输出（客户真理非我方表面，盲区探针走证据链
observation）；工具 stderr 错误词面（SystemExit 中文消息 vs Java 异常词面，stdout 空 +
rc 对照）；Python traceback 形状；报告文件（stdout 即报告字节，文件面由合同测试锁）；
假绿变体（探针走证据链，变体永不入 git）。

setup 块（双侧同规格）：ws/clean（git clone vendors/flowERP）+ ws/no-flowerp（普通目录）；
缺陷树 ws/b1 由 s01 场景自身构造（生成侧 = 冻结 Python 构造器，重放侧 = Java 构造器——
双侧树等价的第一重锁），无 l05 盲区树式的预物化夹具，重放测试清场后直接 replay() 即可。

确定性证据：生成器幂等（先清场再采），连续两跑 golden 目录 SHA-256 必须一致（首跑即留
指纹——L03 教训）；双跑比对结果记入 docs/replication/evidence/L06-java.md §G。
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
WORK_ROOT = REPO_ROOT / ".runtime" / "golden-l06"
RAW_DIR = WORK_ROOT / "raw"
GOLDEN_DIR = REPO_ROOT / "src" / "test" / "resources" / "golden" / "l06"

BUILDER = "evals/l06/build_defect_baseline.py"
DRIVER = "evals/l06/stock_consistency_check.py"
CHECKS_MODULE = "evals.l06.checks"  # checks 经 -m 运行（其 main 内 from evals.harness import run）
JAVA_BUILDER_MAIN = "workbench.evals.l06.BuildDefectBaseline"
JAVA_DRIVER_MAIN = "workbench.evals.l06.StockConsistencyCheck"
JAVA_CHECKS_MAIN = "workbench.evals.l06.Checks"

B1 = ".runtime/golden-l06/ws/b1"
CLEAN = ".runtime/golden-l06/ws/clean"
NO_FLOWERP = ".runtime/golden-l06/ws/no-flowerp"

# 只命中 export_inventory 的 CSV 模板 available 字段（与冻结构造器 ANCHOR 同一文本）。
ANCHOR = "{row['available']}"

MASKS = {
    "created_at": "<TS>",
    "recorded_at": "<TS>",
    "workbench_id": "<WORKBENCH_ID>",
    "generated_at": "<TS>",
    "duration_ms": "<MS>",
}

# setup 规格（生成器与 Java 重放按同一规格执行）：干净 clone + 非客户树目录。
SETUP_STEPS: list[dict] = [
    {"run": ["git", "clone", "--quiet", "--no-hardlinks", "vendors/flowERP", CLEAN]},
    {"write_file": "golden-l06/ws/no-flowerp/placeholder.txt", "content": "not a flowERP tree\n"},
]


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


def _preflight() -> None:
    """锚点逐字校验（l05 A0 同款）：生成器 ANCHOR vs 冻结构造器声明 vs vendors service.py 唯一命中。"""
    frozen = (REPO_ROOT / BUILDER).read_text(encoding="utf-8")
    declared = f'ANCHOR = "{ANCHOR}"'
    if declared not in frozen:
        raise SystemExit(f"冻结构造器未按字面声明锚点，形状变了：{declared!r}")
    service = (REPO_ROOT / "vendors" / "flowERP" / "flowerp" / "service.py").read_text(encoding="utf-8")
    hits = service.count(ANCHOR)
    if hits != 1:
        raise SystemExit(f"锚点命中 {hits} 次（应为 1），上游形状变了，停止生成")
    print(f"anchor_identical: True；anchor_hits_in_service: {hits}")


def _run(command: list[str], env: dict[str, str] | None = None) -> tuple[int, str, str]:
    merged = None if env is None else {**os.environ, **env}
    proc = subprocess.run(command, cwd=str(REPO_ROOT), capture_output=True, env=merged)
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
        return stdout  # 非 JSON 逐字保留（防御性；空 stdout 同此）


def main() -> int:
    # 清场：golden 与运行库/夹具一律重采，不做增量
    shutil.rmtree(WORK_ROOT, ignore_errors=True)
    shutil.rmtree(GOLDEN_DIR, ignore_errors=True)
    RAW_DIR.mkdir(parents=True)
    GOLDEN_DIR.mkdir(parents=True)

    records: list[dict] = []

    def capture(scenario_id: str, expected_rc: int, main: str, argv: list[str], *,
                script: str | None = None, module: str | None = None,
                env: dict[str, str] | None = None, note: str = "") -> None:
        if (script is None) == (module is None):
            raise SystemExit("script / module 必须二选一")
        command = ([sys.executable, "-X", "utf8", str(REPO_ROOT / script)] if script
                   else [sys.executable, "-X", "utf8", "-m", module])
        command += list(argv)
        returncode, stdout, stderr = _run(command, env=env)
        (RAW_DIR / f"{scenario_id}.stdout.txt").write_text(stdout, encoding="utf-8")
        (RAW_DIR / f"{scenario_id}.stderr.txt").write_text(stderr, encoding="utf-8")
        status = "ok" if returncode == expected_rc else "MISMATCH"
        record = {"id": scenario_id, "main": main, "argv": list(argv),
                  "exit_code": returncode, "expected_exit_code": expected_rc,
                  "stdout_file": f"{scenario_id}.stdout.txt"}
        if env:
            record["env"] = env
        if note:
            record["note"] = note
        records.append(record)
        print(f"[{status}] {scenario_id} rc={returncode}（预期 {expected_rc}）")
        if status != "ok":
            raise SystemExit(f"{scenario_id} 退出码偏离预期，中止生成（旧文件已保留供排查）")
        (GOLDEN_DIR / f"{scenario_id}.stdout.txt").write_text(_normalize(stdout), encoding="utf-8")

    # ---- 夹具（setup 规格）与锚点预检 --------------------------------------
    _preflight()
    _run_setup()

    # ---- 场景（s01–s09；s01 构造产物即 s05/s08 被测树——顺序叙事）----------
    capture("s01_build_defect_ok", 0, JAVA_BUILDER_MAIN, ["--baseline", B1],
            script=BUILDER,
            note="C7：clone+CSV available→on_hand 补丁+自检 JSON（defect_live/query_available 5/"
                 "csv_available 8）；重放侧 Java 构造产物即 s05/s08 被测树（双侧树等价的第一重锁）")
    capture("s02_build_existing_rejected", 1, JAVA_BUILDER_MAIN, ["--baseline", B1],
            script=BUILDER,
            note="基线已存在不覆盖（s01 产物在场；stdout 空，stderr 错误词面不入 golden）")
    capture("s03_stock_default_pass", 0, JAVA_DRIVER_MAIN, ["--target", CLEAN],
            script=DRIVER,
            note="C1/C2：8/3 主干（query/csv 同 [8,3,5]、拒 6、四表状态不变；预期独立计算）")
    capture("s04_stock_migration_pass", 0, JAVA_DRIVER_MAIN,
            ["--target", CLEAN, "--opening", "13", "--reserved", "4"],
            script=DRIVER,
            note="C1 迁移：[13,4,9]、拒 10（排除写死 5）")
    capture("s05_stock_defect_fail", 1, JAVA_DRIVER_MAIN, ["--target", B1],
            script=DRIVER,
            note="C1 定位（数值级）：AC-AVAILABLE expected [8,3,5] / query [8,3,5] / csv [8,3,8]"
                 " + requirement 逐字——与 Python 时代红 66 同形")
    capture("s06_stock_target_rejected", 2, JAVA_DRIVER_MAIN, ["--target", NO_FLOWERP],
            script=DRIVER,
            note="参数校验：--target 下没有 flowerp/（argparse error，stdout 空）")
    capture("s07_checks_report_pass", 0, JAVA_CHECKS_MAIN, ["--no-report"],
            module=CHECKS_MODULE, env={"L06_EVAL_TARGET": CLEAN},
            note="C3/C4 统一运行器绿面：五项全量报告（四 blocking 绿 + 教学告警 WARN，"
                 "decision pass）——报告 stdout 字节锁；掩码 generated_at/duration_ms")
    capture("s08_checks_report_block", 1, JAVA_CHECKS_MAIN, ["--no-report"],
            module=CHECKS_MODULE, env={"L06_EVAL_TARGET": B1},
            note="C4 可信红面：l06_stock_consistency 红（evidence=rc=1: {AC-AVAILABLE …}、"
                 "error.type AssertionError）+ decision block——红报告 stdout 字节锁")
    capture("s09_checks_env_missing", 1, JAVA_CHECKS_MAIN, ["--no-report"],
            module=CHECKS_MODULE,
            note="L06_EVAL_TARGET 必设面（stdout 空；rc 1 与「主类缺失」偶然同形——l05 修正注③"
                 "同款口径，红点由其余场景与合同测试承载）")

    # ---- manifest（确定性内容，不含墙钟）-----------------------------------
    manifest = {
        "generated_from": "frozen Python evals 统一运行器面 @ master（见 evidence/L06-java.md §G 记录的 commit）",
        "generator": "tools/generate_golden_l06.py",
        "normalization": {
            "mask_fields": MASKS,
            "mask_note": "l06 特有：本套场景面首次含统一运行器全量报告（s07/s08 checks 的 stdout 即报告全文）——"
                         "generated_at/duration_ms 为机器时间字段，掩码扩展 2 键（l01–l05 首例扩展，机制 l04 起已按 "
                         "manifest 声明数据驱动）；其余场景无这些键，键级掩码无扰；created_at/recorded_at/workbench_id "
                         "声明保留与五套统一（防御性，本套输出无这些键）",
            "canonical_json": "json.dumps(sort_keys=True, ensure_ascii=False, indent=2) + 换行",
            "non_json": "逐字保留",
        },
        "setup": SETUP_STEPS,
        "inputs": {},
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
