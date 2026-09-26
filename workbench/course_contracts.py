"""冻结合同 fixture（ADR-0001）。

数据逐字取自 vendors/CodexFDE/workbench/course_mainline.py，基线 CodexFDE@58f4612
（2026-09-25 现查 origin/main 即此提交）。上游合同变更不自动生效，须走 ADR-0003
检查点显式采纳后更新本文件并同步修订讲义。

口径注（docs/lessons/L01-工作台自举.md §2）：冻结数据中的 write_scope 为上游路径语义
（上游学习证据落 docs/courses/L01/ 等）；本复刻对应物为顶层 `workbench/`、`tests/`、
`docs/`（证据落 docs/replication/evidence/）。映射仅记录于此，冻结数据本身不篡改。

本文件只保留合同数据与访问器；上游的 Spec 渲染、任务创建、git 标签校验等运行时
函数不属于冻结范围，由后续讲次按需重建。
"""

from __future__ import annotations

from dataclasses import asdict, dataclass


COURSE_BUILD_THESIS = "用 Codex 搭建个人工作台；通过个人工作台组织人与 AI 协同开发 FlowERP"
COURSE_WORKSPACE_BOUNDARY = (
    "CodexFDE 是控制仓库，FlowERP 是独立客户项目。"
    "L04+ 的 write_scope 是相对于本次隔离教学候选的路径上限，不是 CodexFDE 根目录权限。"
    "flowerp/、web/ 的业务源码归属独立 FlowERP；workbench/、agent/ 归属工作台。"
    "eval/、tests/ 须按具体文件和来源记录区分工程检查与客户业务检查。"
    "当前课程候选仍组合两仓库源码，须记录来源与哈希；候选修改不自动写回任何源仓库。"
)


def _construction_stage(number: int) -> str:
    if number <= 3:
        return "用 Codex 搭建个人工作台"
    if number == 4:
        return "完成 Workbench V0，并切换到通过工作台协同开发 FlowERP"
    if number <= 15:
        return "通过个人工作台组织人与 Codex 协同开发 FlowERP"
    return "在陌生环境终验整条建设链能否继续运行"


LESSON_STORY: dict[int, dict[str, str]] = {
    1: {"codex_role": "需求访谈者与共同建造者：追问工作台边界、生成候选实现，范围取舍仍由学生签署", "fde_loop": "现场与能力循环：从真实研发痛点形成首份工作台合同、红灯和自举记录", "causal_link": "学生亲手造出 Workbench V0.1；FlowERP 尚未接入，不能用参考终态冒充学生成果"},
    2: {"codex_role": "规则对抗者：在新会话验证拒绝危险请求与放行合法请求", "fde_loop": "能力循环：从现场高风险请求提炼长期不变量", "causal_link": "先把边界写入仓库，后续 Codex 执行才有跨会话约束"},
    3: {"codex_role": "工作台共同建造者：协助实现 Spec Schema 与解析器", "fde_loop": "现场循环：把库存导出争议压成具名合同", "causal_link": "签字 Spec 成为 L04 唯一输入，工作台不允许执行者换题"},
    4: {"codex_role": "协同换挡伙伴：先与学生补齐 Workbench V0，再在工作台中协作交付库存导出", "fde_loop": "交付循环：Spec—授权—前红—Diff—后绿—人审", "causal_link": "完成从直接使用 Codex 搭台到通过工作台组织协同开发的自举换挡"},
    5: {"codex_role": "Eval 共同建造者与幂等入库修复者", "fde_loop": "能力循环：重复收货事故先沉淀为反例，再修产品", "causal_link": "冻结失败优先 Eval 后，工作台才授权 Codex 修复 ERP"},
    6: {"codex_role": "Harness 共同建造者", "fde_loop": "能力循环：把冲突质量信号收口为唯一判决", "causal_link": "统一等级、报告与退出码成为后续 Codex 交付的共同裁判"},
    7: {"codex_role": "Hook 共同建造者与销售订单执行者", "fde_loop": "能力循环：从漏跑检查升级为生命周期护栏", "causal_link": "Codex 结束工作前由 Hook 自动复用同一 Harness"},
    8: {"codex_role": "CI 证据链共同建造者与原子预占修复者", "fde_loop": "交付循环：用 A 假绿、B 可信红、C 可信绿建立远端证据", "causal_link": "候选提交、命令、报告与制品绑定后才能签收 Codex 变更"},
    9: {"codex_role": "Repair 映射器共同建造者与取消缺陷修复者", "fde_loop": "能力循环：把现场失败压缩成最小授权任务", "causal_link": "Codex 只能消费可追溯 Repair Task，不直接吞长日志猜根因"},
    10: {"codex_role": "Loop 控制器共同建造者与订单状态修复者", "fde_loop": "交付循环：在轮次、时间、Token 和进展条件内返工", "causal_link": "未收敛必须安全停止，不能由 Codex 把预算耗尽写成成功"},
    11: {"codex_role": "Subagents 调度能力共同建造者与采购申请执行者", "fde_loop": "能力循环：从跨模块交付识别并行收益与冲突税", "causal_link": "只读任务可并行，共享写集拒绝并行，主 Agent 串行集成"},
    12: {"codex_role": "Graph/HITL 共同建造者与采购闭环执行者", "fde_loop": "交付循环：把暂停、回退、恢复和责任变成持久状态", "causal_link": "质量全绿仍不能替代采购具名审批，模型不得自批"},
    13: {"codex_role": "Task API 共同建造者与补货任务执行者", "fde_loop": "能力循环：把本地流程变成外部可提交、可查询资源", "causal_link": "Codex 执行被封装为有稳定身份、事件和合法状态的 Task"},
    14: {"codex_role": "工作台与 FlowERP Web 共同建造者", "fde_loop": "现场循环：让业务用户看见真实状态、失败和下一步", "causal_link": "页面只投影工作台 API 与 ERP 权威状态，不生成假进度"},
    15: {"codex_role": "Feedback/Evolution 共同建造者与受控改进执行者", "fde_loop": "现场与能力循环：真实反馈经分诊后升级合同和能力", "causal_link": "反馈具名晋级后，由独立 Task 约束 Codex 交付 ERP 改进"},
    16: {"codex_role": "完整工作台中的现场开发伙伴", "fde_loop": "三循环终验：未知现场问题、受控交付、能力迁移", "causal_link": "陌生团队与 Codex 协同让随机需求走完 Spec—实现—Eval—人审—发布证据全链"},
}


@dataclass(frozen=True)
class LessonContract:
    number: int
    title: str
    phase: str
    workbench_increment: str
    erp_increment: str
    request: str
    business_refs: tuple[str, ...]
    write_scope: tuple[str, ...]
    acceptance: tuple[str, ...]
    eval_cases: tuple[str, ...]
    prerequisites: tuple[int, ...]
    baseline_ref: str
    live_request: bool = False
    dynamic_eval_required: bool = False

    @property
    def requirement_id(self) -> str:
        return f"REQ-COURSE-L{self.number:02d}"

    def as_dict(self) -> dict:
        result = asdict(self)
        result["requirement_id"] = self.requirement_id
        result["course_build_thesis"] = COURSE_BUILD_THESIS
        result["construction_stage"] = _construction_stage(self.number)
        result["workspace_boundary"] = COURSE_WORKSPACE_BOUNDARY
        result["write_scope_base"] = "isolated_course_candidate" if self.number >= 4 else "learner_workbench"
        result.update(LESSON_STORY[self.number])
        return result


def _lesson(number: int, title: str, phase: str, workbench: str, erp: str, request: str,
            *, refs: tuple[str, ...] = (), scope: tuple[str, ...] = (),
            acceptance: tuple[str, ...], evals: tuple[str, ...] = (),
            live_request: bool = False, dynamic_eval_required: bool = False) -> LessonContract:
    return LessonContract(
        number=number,
        title=title,
        phase=phase,
        workbench_increment=workbench,
        erp_increment=erp,
        request=request,
        business_refs=refs,
        write_scope=scope,
        acceptance=acceptance,
        eval_cases=evals,
        prerequisites=() if number == 1 else (number - 1,),
        baseline_ref=f"course/l{number:02d}-start",
        live_request=live_request,
        dynamic_eval_required=dynamic_eval_required,
    )


LESSONS: tuple[LessonContract, ...] = (
    _lesson(1, "以终为始：一次可验证的 AI 交付怎样完成？", "bootstrap", "首份工作台 Spec、红灯测试、项目/任务/命令证据账与自举记录", "FlowERP 尚未接入，只冻结为后续验证场",
            "从一句模糊诉求出发，让 Codex 完成需求访谈，由学生决定范围并签署 WORKBENCH_SPEC.md，再从验收项生成红灯、实现 Workbench V0.1 并让工作台记录自身建设证据。",
            scope=("workbench/", "tests/", "docs/courses/L01/"),
            acceptance=("关键要求能回指原始痛点、Codex 建议和学生决定。", "测试能回指验收项，且保留执行前红灯、范围内 Diff 和执行后绿灯。", "学生能使用自己开发的命令完成第一次自举记录。", "本讲不接入或开发 FlowERP。"),
            evals=("bootstrap_evidence_is_honest",)),
    _lesson(2, "把仓库规则写进 `AGENTS.md`", "design", "仓库规则、写入边界与完成定义", "固化库存、订单、采购和追溯边界",
            "把 FlowERP 不可破坏规则和工作台完成定义写成可审查的仓库约束。",
            scope=("AGENTS.md", "tests/"),
            acceptance=("越界请求能够被规则明确判为拒绝。", "规则同时给出正常路径和失败后不变状态。")),
    _lesson(3, "把模糊需求变成可验收 Spec", "design", "六段式 Spec Schema 与解析器", "签字确认库存导出合同",
            "在外循环直接监督 Codex 完成最小 Spec 解析器，并为 SKU 库存导出编写可解析、可验收且不提前扩展订单或采购功能的 Spec。",
            refs=("SKU:COURSE-DEMO",), scope=("FDE_SPEC.md", "lesson-03-submission/", "workbench/templates/SPEC_TEMPLATE.md", "workbench/spec.py", "workbench/cli.py", "tests/"),
            acceptance=("保存 Codex 建造解析器的任务合同、范围内 Diff 和独立红绿证据。", "同一六部分模板用于库存合同与采购草稿，业务口径分别确认。", "Spec 的六个必要章节可被解析。", "工作台 spec 入口实际调用个人解析器；完整输入保留六字段原文，缺项输入明确拒绝，输入文件不变。", "库存导出的列、排序、空结果与错误输入均有明确预期。"),
            evals=("spec_contract_rejects_ambiguity",)),
    _lesson(4, "委托 Codex 执行一次最小变更", "build", "受控执行、Diff 摘要与最小 Eval", "交付库存导出",
            "先直接监督 Codex 补齐并独立验收 Workbench V0，再让 V0 在限定写集内调用 Codex 实现 SKU 库存导出，并保存两张 Ticket 的 Diff、命令和正反路径证据。",
            refs=("SKU:COURSE-DEMO",), scope=("flowerp/", "workbench/", "tests/"),
            acceptance=("Workbench V0 的执行模式、写集检查、最小 Eval 和摘要由非执行者独立验收。", "库存导出结果字段与排序满足 L03 合同。", "导出值与 ERP 权威库存一致。", "执行结果列出实际写集与复验命令。"),
            evals=("inventory_export_is_stable",)),
    _lesson(5, "先设计失败，再编写 Eval", "build", "失败优先的单例 Eval", "交付幂等入库",
            "先构造重复入库会失败的 Eval，再实现同一个幂等键只生效一次。",
            refs=("SKU:COURSE-DEMO",), scope=("flowerp/", "eval/", "tests/"),
            acceptance=("首次入库增加库存。", "同一幂等键重放不再次增加库存。", "保留修复前红灯和修复后绿灯。"),
            evals=("receiving_is_idempotent",)),
    _lesson(6, "用 Harness 汇总证据和等级", "build", "统一 Harness、判决与退出码", "交付可用库存口径",
            "用统一 Harness 验证 available = on_hand - reserved 且永不为负。",
            refs=("SKU:COURSE-DEMO",), scope=("flowerp/", "eval/", "tests/"),
            acceptance=("可用库存按在手减预占计算。", "阻断失败、报告 decision 和进程退出码一致。"),
            evals=("stock_never_negative",)),
    _lesson(7, "用 Codex Hooks 建立本地护栏", "build", "提交前本地护栏", "交付销售订单创建",
            "通过工作台交付销售订单创建，并让本地护栏复验订单金额和明细一致。",
            refs=("ORDER:COURSE-DEMO",), scope=("flowerp/", "eval/", "tests/", "hook_staging/"),
            acceptance=("合法明细生成草稿订单和稳定身份。", "非法数量被拒绝且不留下订单。", "订单总额等于明细合计。",
                        "待审查的 Hook 配置和处理器保存在 hook_staging/，调用统一 Harness。",
                        "人工审查后安装到实际候选，保存真实事件的违规反馈与恢复复验；仅业务 Eval 通过不代表 Hook 验收完成。"),
            evals=("order_total_matches_lines",)),
    _lesson(8, "把同一套 Eval 接入 CI", "build", "远程复验与证据信封", "交付原子预占",
            "在同一套 Eval 的本地与 CI 复验下实现销售订单原子预占，缺货时整单回滚。",
            refs=("ORDER:COURSE-DEMO", "SKU:COURSE-DEMO"), scope=("flowerp/", "eval/", "tests/", ".github/workflows/", "workbench/ci_evidence.py", "CI_GATE_SPEC.md"),
            acceptance=("库存充足时订单预占成功。", "任一行缺货时整单失败且无部分预占。", "本地与 CI 使用同一 Eval 身份。"),
            evals=("stock_never_negative", "sales_credit_and_atomic_reservation", "ci_evidence_envelope_is_honest")),
    _lesson(9, "把失败报告翻译成修复任务", "repair", "报告到 Repair Task 的确定性映射", "交付取消释放预占",
            "把取消订单未释放预占的失败报告转成有界修复任务并完成修复。",
            refs=("ORDER:COURSE-DEMO",), scope=("flowerp/", "agent/", "eval/", "tests/"),
            acceptance=("取消已预占订单会完整释放预占。", "修复任务保存失败项、允许写集和复验命令。"),
            evals=("cancellation_releases_reservation",)),
    _lesson(10, "建立有停止条件的修复 Loop", "repair", "带预算与停止条件的修复 Loop", "交付合法订单状态迁移",
            "用最多三轮的修复 Loop 阻断订单跳过前置状态直接发货。",
            refs=("ORDER:COURSE-DEMO",), scope=("flowerp/", "agent/", "eval/", "tests/"),
            acceptance=("合法状态迁移成功。", "非法迁移被阻断且订单状态不变。", "预算耗尽被记录为未收敛而非成功。"),
            evals=("illegal_transition_is_blocked",)),
    _lesson(11, "用 Codex 原生 Subagents 并行处理独立任务", "build", "独立写集调度与串行集成", "交付采购申请",
            "将采购申请拆成互不冲突的实现与反证任务，串行集成后统一复验。",
            refs=("PURCHASE:COURSE-DEMO",), scope=("flowerp/", "agent/", "eval/", "tests/"),
            acceptance=("采购申请保存 SKU、数量、原因和稳定身份。", "写集冲突的子任务不得并行。", "非法数量不产生采购申请。"),
            evals=("purchase_request_preserves_reason", "write_sets_reject_conflict")),
    _lesson(12, "用 Graph 显式表达状态、回退和人工审核", "build", "显式状态图、回退边和具名人审", "交付审批后入库",
            "用显式 Graph 交付采购审批与入库，未经具名审批不得改变库存。",
            refs=("PURCHASE:COURSE-DEMO", "SKU:COURSE-DEMO"), scope=("flowerp/", "agent/", "eval/", "tests/"),
            acceptance=("未审批采购入库被阻断且库存不变。", "具名审批后允许一次幂等入库。", "Graph 状态与 ERP 权威状态可对账。"),
            evals=("purchase_requires_approval", "receiving_is_idempotent")),
    _lesson(13, "把执行链路封装成任务 API", "operate", "可追溯 Task API 与异步状态", "通过 API 交付补货需求",
            "从 Task API 提交补货需求，生成 Spec、执行 Eval，并停在具名人工审核。",
            refs=("PURCHASE:COURSE-DEMO",), scope=("workbench/", "flowerp/", "eval/", "tests/"),
            acceptance=("API 接受请求后返回 Task ID 而非伪称完成。", "任务事件可追溯到需求、业务对象和 Eval。", "全绿后仍停在人工审核。"),
            evals=("delivery_evidence_and_review_controls", "purchase_requires_approval")),
    _lesson(14, "让交付状态在 Web 面板可见", "operate", "可查询任务与工作台面板", "交付 ERP 操作页",
            "在无密钥 Web 面板展示 ERP 权威状态和交付证据，并能下钻到任务事件。",
            refs=("REQUIREMENT:COURSE-L14",), scope=("web/", "workbench/", "workbench_web/", "tests/"),
            acceptance=("页面数据来自 API 而非静态假数据。", "DOM、API、SQLite 与 ERP 状态可对账。", "页面不包含凭据。"),
            evals=("delivery_evidence_and_review_controls", "web_api_and_persistence_projection_agree", "no_committed_secrets")),
    _lesson(15, "生成交付摘要并采集真实反馈", "operate", "交付摘要、反馈审核、Memory/RAG 与演进记录", "交付反馈驱动的 ERP 小改进",
            "将一条真实采用反馈审核为改进任务，把已审核经验登记为可治理记忆，在另一事项中检索为带引用上下文，再通过工作台交付并保留升级前后证据。",
            refs=("REQUIREMENT:COURSE-L15",), scope=("workbench/", "flowerp/", "eval/", "tests/"),
            acceptance=("原始反馈先审核再进入记忆候选与执行合同。", "记忆来源、版本、状态、检索结果、采用快照和后续验证可追溯。", "撤回或跨项目记忆不进入默认上下文，反馈与检索结果均不能直接改写阻断裁判。"),
            evals=("delivery_evidence_and_review_controls", "raw_feedback_cannot_become_blocking"), dynamic_eval_required=True),
    _lesson(16, "在新环境接手，并完成现场新需求", "transfer", "冷启动、发布证据索引与迁移答辩", "现场交付此前未实现的小需求",
            "现场抽取一个此前未实现的 FlowERP 小需求，使用工作台完成 Spec、受控执行、Eval、人审和发布证据。",
            refs=("REQUIREMENT:LIVE-DRAW",), scope=("flowerp/", "workbench/", "eval/", "web/", "tests/"),
            acceptance=("需求在答辩现场抽取且仓库基线中尚未实现。", "正常、失败和失败后不变状态均有新证据。", "发布索引能追溯需求、Diff、Eval、人审与剩余风险。"),
            evals=("delivery_evidence_and_review_controls",), live_request=True, dynamic_eval_required=True),
)


def lesson_contract(number: int) -> LessonContract:
    if not 1 <= number <= len(LESSONS):
        raise ValueError("课次必须在 1 到 16 之间")
    return LESSONS[number - 1]
