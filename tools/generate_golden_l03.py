"""L03 对照基准（golden）生成器：用冻结的 Python 工作台采出规范化场景输出（L03 讲义附录 A3）。

单一会话 11 个场景按序驱动真实 CLI 子进程（与合同测试同形，不绕入口直调），
原始 stdout 落 `.runtime/golden-l03/raw/`（gitignore，不入库），规范化后落
`src/test/resources/golden/l03/`（随 Git 提交，生成后永不手改；要变只能重生成并留证据）。

场景面 = spec 命令的结构闸门（L03 讲义附录 A3）：纯解析，无账本场景。输入固定件三源：
①冻结三工件（lesson-03-submission/ 的确认版 / missing-section / wrong-formula，生成时
读取内容冻结入 manifest——golden 直接封存本讲自己的三份输入）；②合成夹具（六类拒绝面
+ 围栏屏蔽通过面，生成器常量，镜像讲义 §3.G"手工复制并编辑"）；③冻结采购稿（C6 重验）。

golden l03 与 l01/l02 的差异：重放的正是 L03 目标能力（spec 结构闸门），Java 侧重放测试
在候选 commit 1 为红点参与者、commit 2 转绿即字节级对照达成（移植注记 A1/A2）。errno 类
场景（文件不存在 / 非 UTF-8 解码）不入 golden——词面系语言绑定，如实记录偏差，不伪装同形
（移植注记 JD6）。

规范化规则（与 golden/l01、l02 逐字相同，同时写入 manifest.json 的 normalization 块，
Java 测试侧按同一规则复现）：
- JSON 输出：字段名掩码（`created_at`/`recorded_at` → `"<TS>"`，`workbench_id` →
  `"<WORKBENCH_ID>"`）后按 `json.dumps(sort_keys=True, ensure_ascii=False, indent=2)`
  规范化 + 末尾换行；spec 输出本身无账本时间戳字段，掩码块为三套 golden 统一形状，
  由重放测试断言其在场；
- 非 JSON 输出：逐字保留（防御性规则）。

确定性证据：生成器幂等（先清场再采），连续两跑 golden 目录 SHA-256 必须一致；
双跑比对结果记入 `docs/replication/evidence/L03-java.md` §G。
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
WORK_ROOT = REPO_ROOT / ".runtime" / "golden-l03"
RAW_DIR = WORK_ROOT / "raw"
INPUTS = WORK_ROOT / "inputs"
GOLDEN_DIR = REPO_ROOT / "src" / "test" / "resources" / "golden" / "l03"

SUBMISSION = REPO_ROOT / "lesson-03-submission"

# 冻结工件：生成时读取内容冻结入 manifest（lesson-03-submission/ 随 Git 提交且永不移动）
FROZEN_INPUTS = {
    "confirmed-spec.md": SUBMISSION / "FDE_SPEC.md",
    "missing-section.md": SUBMISSION / "missing-section.md",
    "wrong-formula.md": SUBMISSION / "wrong-formula.md",
    "procurement-draft.md": SUBMISSION / "procurement-draft.md",
}

# 合成夹具：六段齐全、每段非空，只引入目标变异（空章构造沿 evidence/L03.md §3 夹具教训：
# 保留空正文意图、不新增标题）
SYNTHETIC_INPUTS = {
    "heading-in-fence.md": (
        "## 来源\n"
        "对照基准夹具：围栏屏蔽语义（检查点 0002 教学节）。\n"
        "\n"
        "## 目标\n"
        "验证围栏内的标题行不误认为章节，且围栏正文完整保留。\n"
        "\n"
        "## 非目标\n"
        "不验证业务语义。\n"
        "\n"
        "## 约束\n"
        "只做结构检查。下面的示例正文里含标题样式，但它不是章节：\n"
        "\n"
        "```text\n"
        "## 目标\n"
        "这是围栏内的示例正文，不是章节标题。\n"
        "```\n"
        "\n"
        "## 验收用例\n"
        "给定本文件，执行 spec 解析，应输出六字段且通过。\n"
        "\n"
        "## 完成定义\n"
        "围栏内标题不误认、围栏正文保留，即为本夹具的通过口径。\n"
    ),
    "empty-section.md": (
        "## 来源\n"
        "对照基准夹具：空章拒绝。\n"
        "\n"
        "## 目标\n"
        "\n"
        "## 非目标\n"
        "不验证业务语义。\n"
        "\n"
        "## 约束\n"
        "只做结构检查。\n"
        "\n"
        "## 验收用例\n"
        "给定本文件，执行 spec 解析，应拒绝并指认空章。\n"
        "\n"
        "## 完成定义\n"
        "\"目标\"正文为空应被拒绝。\n"
    ),
    "duplicate-section.md": (
        "## 来源\n"
        "对照基准夹具：重复章节拒绝。\n"
        "\n"
        "## 目标\n"
        "六段齐全的唯一目标段。\n"
        "\n"
        "## 非目标\n"
        "不验证业务语义。\n"
        "\n"
        "## 约束\n"
        "只做结构检查。\n"
        "\n"
        "## 验收用例\n"
        "给定本文件，执行 spec 解析，应拒绝并指认重复章节。\n"
        "\n"
        "## 完成定义\n"
        "重复出现的章节标题应被拒绝。\n"
        "\n"
        "## 目标\n"
        "重复出现的目标段，应触发重复检查。\n"
    ),
    "wrong-order.md": (
        "## 目标\n"
        "对照基准夹具：顺序检查（来源与目标互换）。\n"
        "\n"
        "## 来源\n"
        "对照基准夹具来源段。\n"
        "\n"
        "## 非目标\n"
        "不验证业务语义。\n"
        "\n"
        "## 约束\n"
        "只做结构检查。\n"
        "\n"
        "## 验收用例\n"
        "给定本文件，执行 spec 解析，应拒绝并给出应为/实际顺序。\n"
        "\n"
        "## 完成定义\n"
        "章节顺序错误应被拒绝。\n"
    ),
    "unknown-heading.md": (
        "## 来源\n"
        "对照基准夹具：未知章节拒绝。\n"
        "\n"
        "## 目标\n"
        "六段齐全的目标段。\n"
        "\n"
        "## 非目标\n"
        "不验证业务语义。\n"
        "\n"
        "## 约束\n"
        "只做结构检查。\n"
        "\n"
        "## 验收用例\n"
        "给定本文件，执行 spec 解析，应拒绝并指认未知章节。\n"
        "\n"
        "## 完成定义\n"
        "未知章节标题应被拒绝。\n"
        "\n"
        "## 附录\n"
        "未知章节，应触发未知检查。\n"
    ),
    "unclosed-fence.md": (
        "## 来源\n"
        "对照基准夹具：未闭合围栏拒绝。\n"
        "\n"
        "## 目标\n"
        "验证未闭合围栏必须报错，而不是吞掉后续正式章节。\n"
        "\n"
        "## 非目标\n"
        "不验证业务语义。\n"
        "\n"
        "## 约束\n"
        "只做结构检查。下面的示例缺少结束标记：\n"
        "\n"
        "```text\n"
        "## 目标\n"
        "围栏一直开到文件结尾，未闭合。\n"
        "\n"
        "## 验收用例\n"
        "给定本文件，执行 spec 解析，应拒绝并指认未闭合围栏。\n"
        "\n"
        "## 完成定义\n"
        "未闭合围栏应被拒绝。\n"
    ),
    "empty-file.md": "",
}

MASK_TO_TS = ("created_at", "recorded_at")


def _run_cli(*argv: str) -> tuple[int, str, str]:
    proc = subprocess.run(
        [sys.executable, "-X", "utf8", "-m", "workbench.cli", *argv],
        cwd=str(REPO_ROOT), capture_output=True,
    )
    return proc.returncode, proc.stdout.decode("utf-8"), proc.stderr.decode("utf-8")


def _mask(node):
    if isinstance(node, dict):
        return {key: ("<TS>" if key in MASK_TO_TS
                      else "<WORKBENCH_ID>" if key == "workbench_id"
                      else _mask(value))
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


def _write_input(name: str, text: str) -> str:
    """写固定输入件，返回仓库相对路径字面量——进 manifest 的 argv 必须可跨机复现。"""
    (INPUTS / name).write_text(text, encoding="utf-8")
    return f".runtime/golden-l03/inputs/{name}"


def main() -> int:
    # 清场：golden 与运行库一律重采，不做增量
    shutil.rmtree(WORK_ROOT, ignore_errors=True)
    shutil.rmtree(GOLDEN_DIR, ignore_errors=True)
    INPUTS.mkdir(parents=True)
    RAW_DIR.mkdir()
    GOLDEN_DIR.mkdir(parents=True)

    records: list[dict] = []

    def capture(scenario_id: str, expected_rc: int, *argv: str, note: str = "") -> None:
        returncode, stdout, stderr = _run_cli(*argv)
        (RAW_DIR / f"{scenario_id}.stdout.txt").write_text(stdout, encoding="utf-8")
        (RAW_DIR / f"{scenario_id}.stderr.txt").write_text(stderr, encoding="utf-8")
        status = "ok" if returncode == expected_rc else "MISMATCH"
        records.append({"id": scenario_id, "argv": list(argv), "exit_code": returncode,
                        "expected_exit_code": expected_rc, "stdout_file": f"{scenario_id}.stdout.txt",
                        **({"note": note} if note else {})})
        print(f"[{status}] {scenario_id} rc={returncode}（预期 {expected_rc}）")
        if status != "ok":
            raise SystemExit(f"{scenario_id} 退出码偏离预期，中止生成（旧文件已保留供排查）")
        (GOLDEN_DIR / f"{scenario_id}.stdout.txt").write_text(_normalize(stdout), encoding="utf-8")

    # ---- 输入固定件 -------------------------------------------------------
    inputs_rel: dict[str, str] = {}
    inputs_text: dict[str, str] = {}
    for name, source in FROZEN_INPUTS.items():
        inputs_text[name] = source.read_text(encoding="utf-8")
        inputs_rel[name] = _write_input(name, inputs_text[name])
    for name, text in SYNTHETIC_INPUTS.items():
        inputs_text[name] = text
        inputs_rel[name] = _write_input(name, text)

    # ---- 场景（t01–t11，全部为 spec 命令结构闸门；无账本场景）--------------
    capture("t01_spec_confirmed_ok", 0, "spec", inputs_rel["confirmed-spec.md"],
            note="冻结确认版：六字段原文 + ok:true（C3 结构半边）")
    capture("t02_spec_missing_section_rejected", 1, "spec", inputs_rel["missing-section.md"],
            note="冻结缺章版：缺章拒绝词面 + C11 eval spec_contract_rejects_ambiguity 承载")
    capture("t03_spec_wrong_formula_passes", 0, "spec", inputs_rel["wrong-formula.md"],
            note="冻结错误公式版：结构通过 ≠ 业务正确（C10 错误公式态）")
    capture("t04_spec_heading_inside_fence_ok", 0, "spec", inputs_rel["heading-in-fence.md"],
            note="围栏屏蔽：围栏内标题不误认（检查点 0002 教学节）")
    capture("t05_spec_procurement_draft_ok", 0, "spec", inputs_rel["procurement-draft.md"],
            note="冻结采购稿：C6 结构半边重验")
    capture("t06_spec_empty_section_rejected", 1, "spec", inputs_rel["empty-section.md"])
    capture("t07_spec_duplicate_section_rejected", 1, "spec", inputs_rel["duplicate-section.md"])
    capture("t08_spec_wrong_order_rejected", 1, "spec", inputs_rel["wrong-order.md"])
    capture("t09_spec_unknown_heading_rejected", 1, "spec", inputs_rel["unknown-heading.md"])
    capture("t10_spec_unclosed_fence_rejected", 1, "spec", inputs_rel["unclosed-fence.md"])
    capture("t11_spec_empty_file_rejected", 1, "spec", inputs_rel["empty-file.md"],
            note="空文件：缺章全列表词面（六段 \", \" 连接格式）")

    # ---- manifest（确定性内容，不含墙钟）-----------------------------------
    manifest = {
        "generated_from": "frozen Python workbench @ master（见 evidence/L03-java.md §G 记录的 commit）",
        "generator": "tools/generate_golden_l03.py",
        "normalization": {
            "mask_fields": {"created_at": "<TS>", "recorded_at": "<TS>",
                            "workbench_id": "<WORKBENCH_ID>"},
            "mask_note": "掩码块为 l01/l02/l03 三套 golden 统一形状（重放测试断言其在场）；"
                         "spec 输出本身无账本时间戳字段",
            "canonical_json": "json.dumps(sort_keys=True, ensure_ascii=False, indent=2) + 换行",
            "non_json": "逐字保留",
        },
        "inputs": {f"golden-l03/inputs/{name}": inputs_text[name]
                   for name in inputs_text},
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
