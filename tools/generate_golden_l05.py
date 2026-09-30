"""L05 对照基准（golden）生成器：用冻结 Python 的 evals/l05 两工具采出规范化场景输出（L05 讲义附录 A3）。

与 l01–l04 不同：本套场景面不是 workbench CLI，而是 L05 两工具（缺陷基线构造器
build_defect_baseline.py + 收货场景驱动 receiving_scenario_check.py）的 stdout——
生成侧驱动冻结 Python 工具，重放侧驱动 Java 对应物（workbench.evals.l05.* 两 main，
不进 REGISTRY），场景带 main 字段指明重放主类（共享件 JD5 扩展：GoldenReplay/Cli
可选 main，旧四套无该字段 → 行为逐字节不变）。工具输出对外契约逐字同形即重走目标达成。

7 场景（单会话按序实跑，s01 构造产物即 s05 被测树——顺序叙事）：
- s01 构造器成功（clone+门面 b1 补丁+自检 JSON：defect_live/on_hand 10/events 1）
- s02 构造器对已存在基线拒绝（防覆盖）
- s03 驱动默认 20/8/8 pass（C1/C2 主干，预期独立计算）
- s04 驱动 11/3/3 pass（C6 迁移）
- s05 驱动对 b1 树 fail（replay 步 28≠36 + requirement 逐字——C3 定位）
- s06 驱动对盲区树 fail（replay-ledger 步——C7 盲区，树性质反向锁）
- s07 驱动 --target 无 flowerp/ 拒绝（rc 2，参数校验面）

盲区树（ws/blind）：Python 时代为 ad-hoc 手术无冻结脚本 → 生成器按讲义附录 A JD4
补丁规格**内联手术**（重放分支改为删旧 event 行、以 -rewritten 新键重插同内容、
库存与返回值不变——客户 eval 对此绿（只看库存与重放旗标），驱动红（快照含流水））；
重放侧由 Java 构造器 --defect blind 物化同一规格，两侧树的等价性由 s06 驱动 fail
输出对照反向锁定。锚点拒绝不入 golden：冻结构造器 clone 源硬编码 vendors/flowERP
无注入缝，构造"锚点命中 2 次"须改 vendors（铁律 1 禁止）——守卫由 Java 合同测试
经 --source 变异源 discharge（附录 A3 修正注①）。

掩码（l05 特有，逐字写入 manifest mask_note）：本套零新掩码——工具 stdout 不含
机器生成时间戳（无 l04 _now() 同秒漂移面）与机器绝对路径（构造器 baseline 字段以
固定相对路径调用规避，驱动输出不含路径）；created_at/recorded_at/workbench_id
三件套声明保留与四套统一（防御性，本套输出无这些键）。

不入 golden（语言绑定或非确定，附录 A3/JD6）：客户 eval 运行输出（客户真理非
我方表面：pwd 行绝对路径与耗时 ms 不可复现，证据链以语义+rc 承载）；工具 stderr
错误词面（SystemExit 中文消息 vs Java 异常词面，stdout 空 + rc 对照）；Python
traceback 形状。

setup 块（与被测实现无关的环境物化，双侧同规格）：ws/clean（git clone
vendors/flowERP）+ ws/no-flowerp（普通目录）；ws/b1 由 s01 场景自身构造、ws/blind
由生成器内联手术（重放侧：测试调 Java 构造器）——均不进共享 setup。

确定性证据：生成器幂等（先清场再采），连续两跑 golden 目录 SHA-256 必须一致
（首跑即留指纹——L03 首跑漏留教训）；双跑比对结果记入 docs/replication/evidence/L05-java.md §G。
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
WORK_ROOT = REPO_ROOT / ".runtime" / "golden-l05"
RAW_DIR = WORK_ROOT / "raw"
GOLDEN_DIR = REPO_ROOT / "src" / "test" / "resources" / "golden" / "l05"

BUILDER = "evals/l05/build_defect_baseline.py"
DRIVER = "evals/l05/receiving_scenario_check.py"
JAVA_BUILDER_MAIN = "workbench.evals.l05.BuildDefectBaseline"
JAVA_DRIVER_MAIN = "workbench.evals.l05.ReceivingScenarioCheck"

B1 = ".runtime/golden-l05/ws/b1"
CLEAN = ".runtime/golden-l05/ws/clean"
BLIND = ".runtime/golden-l05/ws/blind"
NO_FLOWERP = ".runtime/golden-l05/ws/no-flowerp"

# 只命中门面重放分支（service.py receive_stock）：与冻结构造器的 ANCHOR 同一文本。
ANCHOR = '            if exists:\n                row = conn.execute('
# 盲区补丁（讲义附录 A JD4 规格）：删旧 event 行、以 -rewritten 新键重插同内容，
# 库存与返回值不变——客户 eval 绿（不读流水）、驱动红（快照含流水）。
BLIND_PATCH = (
    '            if exists:\n'
    '                conn.execute("DELETE FROM inventory_events WHERE event_key=?", (event_key,))\n'
    '                conn.execute(\n'
    '                    "INSERT INTO inventory_events(event_key,sku,quantity,reserved_delta,event_type,reference)'
    ' VALUES(?,?,?,?,?,?)",\n'
    '                    (event_key + "-rewritten", sku, quantity, 0, "receive", reference),\n'
    '                )\n'
    '                row = conn.execute('
)

MASKS = {
    "created_at": "<TS>",
    "recorded_at": "<TS>",
    "workbench_id": "<WORKBENCH_ID>",
}

# setup 规格（生成器与 Java 重放按同一规格执行）：干净 clone + 非客户树目录。
SETUP_STEPS: list[dict] = [
    {"run": ["git", "clone", "--quiet", "--no-hardlinks", "vendors/flowERP", CLEAN]},
    {"write_file": "golden-l05/ws/no-flowerp/placeholder.txt", "content": "not a flowERP tree\n"},
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


def _build_blind() -> None:
    """盲区树内联手术（JD4 规格；锚点守卫与冻结构造器同纪律）。"""
    blind = REPO_ROOT / BLIND
    if blind.exists():
        raise SystemExit(f"盲区树已存在，不覆盖：{blind}（重建请先手动删除）")
    subprocess.run(["git", "clone", "--quiet", "--no-hardlinks",
                    str(REPO_ROOT / "vendors" / "flowERP"), str(blind)], check=True)
    service_file = blind / "flowerp" / "service.py"
    source = service_file.read_text(encoding="utf-8")
    if source.count(ANCHOR) != 1:
        raise SystemExit(f"锚点命中 {source.count(ANCHOR)} 次（应为 1），上游形状变了，停止构造")
    service_file.write_text(source.replace(ANCHOR, BLIND_PATCH), encoding="utf-8")


def _run_tool(script: str, *argv: str) -> tuple[int, str, str]:
    proc = subprocess.run(
        [sys.executable, "-X", "utf8", script, *argv],
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
        return stdout  # 非 JSON 逐字保留（防御性；空 stdout 同此）


def main() -> int:
    # 清场：golden 与运行库/夹具一律重采，不做增量
    shutil.rmtree(WORK_ROOT, ignore_errors=True)
    shutil.rmtree(GOLDEN_DIR, ignore_errors=True)
    RAW_DIR.mkdir(parents=True)
    GOLDEN_DIR.mkdir(parents=True)

    records: list[dict] = []

    def capture(scenario_id: str, expected_rc: int, main: str, script: str,
                *argv: str, note: str = "") -> None:
        returncode, stdout, stderr = _run_tool(script, *argv)
        (RAW_DIR / f"{scenario_id}.stdout.txt").write_text(stdout, encoding="utf-8")
        (RAW_DIR / f"{scenario_id}.stderr.txt").write_text(stderr, encoding="utf-8")
        status = "ok" if returncode == expected_rc else "MISMATCH"
        records.append({"id": scenario_id, "main": main, "argv": list(argv),
                        "exit_code": returncode, "expected_exit_code": expected_rc,
                        "stdout_file": f"{scenario_id}.stdout.txt",
                        **({"note": note} if note else {})})
        print(f"[{status}] {scenario_id} rc={returncode}（预期 {expected_rc}）")
        if status != "ok":
            raise SystemExit(f"{scenario_id} 退出码偏离预期，中止生成（旧文件已保留供排查）")
        (GOLDEN_DIR / f"{scenario_id}.stdout.txt").write_text(_normalize(stdout), encoding="utf-8")

    # ---- 夹具（setup 规格 + 盲区树内联手术）----------------------------------
    _run_setup()
    _build_blind()

    # ---- 场景（s01–s07；s01 构造产物即 s05 被测树——顺序叙事）----------------
    capture("s01_build_b1_ok", 0, JAVA_BUILDER_MAIN, BUILDER,
            "--baseline", B1,
            note="C3：clone+门面 b1 补丁+自检 JSON（defect_live/on_hand 10/events 1）；"
                 "重放侧 Java 构造产物即 s05 被测树（双侧树等价的第一重锁）")
    capture("s02_build_existing_rejected", 1, JAVA_BUILDER_MAIN, BUILDER,
            "--baseline", B1,
            note="基线已存在不覆盖（s01 产物在场；stdout 空，stderr 错误词面不入 golden）")
    capture("s03_scenario_default_pass", 0, JAVA_DRIVER_MAIN, DRIVER,
            "--target", CLEAN,
            note="C1/C2：20/8/8 主干（on_hand [20,28,28,36]、流水 [1,2,2,3]、0/负数拒绝；预期独立计算）")
    capture("s04_scenario_migration_pass", 0, JAVA_DRIVER_MAIN, DRIVER,
            "--target", CLEAN, "--opening", "11", "--receipt", "3", "--new", "3",
            note="C6 迁移：11→14→14→17（排除写死 28）")
    capture("s05_scenario_defect_baseline_fail", 1, JAVA_DRIVER_MAIN, DRIVER,
            "--target", B1,
            note="C3 定位（observing 数值级）：replay 步 expected 28 / actual 36 + requirement 逐字")
    capture("s06_scenario_blind_fail", 1, JAVA_DRIVER_MAIN, DRIVER,
            "--target", BLIND,
            note="C7 盲区：replay-ledger 步（重放改变了账面）——树性质反向锁（客户 eval 对该树绿，见证据链）")
    capture("s07_scenario_target_rejected", 2, JAVA_DRIVER_MAIN, DRIVER,
            "--target", NO_FLOWERP,
            note="参数校验：--target 下没有 flowerp/（argparse error，stdout 空）")

    # ---- manifest（确定性内容，不含墙钟）-----------------------------------
    manifest = {
        "generated_from": "frozen Python evals/l05 工具 @ master（见 evidence/L05-java.md §G 记录的 commit）",
        "generator": "tools/generate_golden_l05.py",
        "normalization": {
            "mask_fields": MASKS,
            "mask_note": "l05 特有：本套场景面（两工具 stdout）不含机器生成时间戳与机器绝对路径——构造器 baseline "
                         "字段以固定相对路径调用规避、驱动输出不含路径——故在 l01–l03 三件套之外零扩展；"
                         "created_at/recorded_at/workbench_id 声明保留与四套统一（防御性，本套输出无这些键）",
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
