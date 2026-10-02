package workbench.delivery;

import workbench.coursecontracts.CourseContracts;
import workbench.spec.SpecParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务级 Spec 生成（上游 {@code workbench/spec.py} 的 build_delivery_spec /
 * normalize_* 与 {@code course_mainline.py} 的 render_lesson_spec 对应物，
 * L13 讲义 D5 新缝②）：六段模板逐字翻译 + 信号词路由（业务边界写进验收用例的
 * 机制本体）+ REQ- 前缀校验与自动编号；写盘走 parse-before-publish——不完整的
 * 生成合同永不成为任务的激活 Spec（上游同语义）。生成物可被 L03 SpecParser 解析。
 */
public final class SpecFactory {

    /** 上游 BUSINESS_REF_PATTERN 逐字同形（前缀白名单 + 值段）。 */
    static final Pattern BUSINESS_REF_PATTERN = Pattern.compile(
            "\\b(SKU|CHANNEL|CHANNEL_ORDER|SALES_ORDER|ORDER|PURCHASE|PRODUCT|CUSTOMER|SUPPLIER|REQUIREMENT|PROJECT|SESSION|INITIATIVE):"
                    + "([A-Za-z0-9][A-Za-z0-9._/-]{1,127})\\b");

    private static final Pattern REQUIREMENT_ID_PATTERN =
            Pattern.compile("REQ-[A-Z0-9][A-Z0-9._-]{2,63}");

    private SpecFactory() {}

    /** 需求编号规范化：空 → 自动 REQ-AUTO-{日期}-{hex6}；非空 → REQ- 前缀强校验。 */
    public static String normalizeRequirementId(String value) {
        String candidate = value == null ? "" : value.strip().toUpperCase();
        if (!candidate.isEmpty()) {
            if (!REQUIREMENT_ID_PATTERN.matcher(candidate).matches()) {
                throw new IllegalArgumentException(
                        "需求编号必须使用 REQ- 前缀，且只包含字母、数字、点、下划线或连字符");
            }
            return candidate;
        }
        String day = DateTimeFormatter.ofPattern("yyyyMMdd")
                .withZone(ZoneOffset.UTC).format(ZonedDateTime.now(ZoneOffset.UTC));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "REQ-AUTO-" + day + "-" + suffix;
    }

    /** 业务对象引用规范化：显式值逐个全匹配校验，再从需求文本提取，去重保序。 */
    public static List<String> normalizeBusinessRefs(String request, List<String> values) {
        List<String> refs = new ArrayList<>();
        for (String raw : values == null ? List.<String>of() : values) {
            String value = raw == null ? "" : raw.strip();
            Matcher match = BUSINESS_REF_PATTERN.matcher(value);
            if (!match.matches()) {
                throw new IllegalArgumentException("业务对象引用格式无效：" + value);
            }
            refs.add(match.group(1).toUpperCase() + ":" + match.group(2));
        }
        Matcher found = BUSINESS_REF_PATTERN.matcher(request == null ? "" : request);
        while (found.find()) {
            refs.add(found.group(1).toUpperCase() + ":" + found.group(2));
        }
        return new ArrayList<>(new LinkedHashSet<>(refs));
    }

    // ---- 任务级交付 Spec（automation.submit 面） ----------------------------------------------

    /** 需求 → 六段 Spec 文本（上游 build_delivery_spec 模板逐字翻译）。 */
    public static String buildDeliverySpec(String request, String requirementId, List<String> businessRefs) {
        String need = request.strip();
        if (need.isEmpty()) {
            throw new IllegalArgumentException("需求不能为空");
        }
        String refs = businessRefs == null || businessRefs.isEmpty()
                ? "REQUIREMENT:" + requirementId
                : String.join("、", businessRefs);
        List<String> acceptanceLines = new ArrayList<>(businessAcceptance(need, businessRefs));
        acceptanceLines.add("给定本需求和关联业务对象，当平台接收请求时，那么生成唯一 Task ID 和当前 Spec 文件。");
        acceptanceLines.add("给定已校验 Spec，当平台执行任务时，那么状态只能按 `queued → spec_ready → executing → evaluating` 迁移。");
        acceptanceLines.add("给定 Blocking Eval 全绿，当评测结束时，那么任务进入 `review`，不得自动进入 `completed`。");
        acceptanceLines.add("给定任一 Blocking Eval 失败，当评测结束时，那么任务进入 `rework`，保存失败项和报告摘要。");
        acceptanceLines.add("给定任务处于 `review`，当具名审核人批准或驳回时，那么分别进入 `completed` 或 `rework`，并保存审核理由。");
        StringBuilder acceptance = new StringBuilder();
        for (int index = 0; index < acceptanceLines.size(); index++) {
            acceptance.append(index + 1).append(". ").append(acceptanceLines.get(index)).append("\n");
        }
        return """
                # %s｜自动生成的交付 Spec

                > 该文件由 FlowERP 交付工作台从需求入口生成。它是本次任务合同草案，不替代领域服务权限、数据库状态或具名人工审核。

                ## 来源

                - 需求编号：`%s`
                - 业务对象：%s
                - 原始需求：%s

                ## 目标

                %s

                交付结果必须能够通过需求编号、任务编号、业务对象引用和 Eval 报告相互追溯。

                ## 非目标

                - 不直接修改运行数据库、库存余额、审批记录或审计历史。
                - 不绕过订单、采购、库存和财务状态机。
                - 不通过删除 Eval、降低阻断等级或伪造页面状态获得绿色结果。
                - 不修改与本需求无关的模块、依赖和部署配置。

                ## 约束

                - 可用库存不得为负，预占必须原子化。
                - 同一个入库幂等键只能生效一次。
                - 订单只能按合法状态迁移，取消必须释放预占。
                - 采购补货必须经过具名人工审批才能入库。
                - 任务、Eval 报告、工具结果和审核决定必须保留可追溯证据。

                ## 验收用例

                %s## 完成定义

                - 当前 Spec 可被解析且六个必要章节完整。
                - Blocking Harness 的 decision、失败数和退出语义一致。
                - 状态事件包含 actor、时间、迁移原因和关键证据。
                - 全绿后仍完成具名人工验收；失败和剩余风险没有被隐藏。
                """.formatted(requirementId, requirementId, refs, need, need, acceptance);
    }

    /**
     * 信号词路由（上游 _business_acceptance 逐字翻译，保序去重）：库存/采购/渠道/
     * 订单/回调五组业务边界用例 + 兜底组——FlowERP 边界按需求信号写进每份交付合同。
     */
    static List<String> businessAcceptance(String request, List<String> businessRefs) {
        String signal = (request + " " + String.join(" ",
                businessRefs == null ? List.<String>of() : businessRefs)).toUpperCase();
        List<String> cases = new ArrayList<>();
        if (signal.contains("回传") || signal.contains("回调") || signal.contains("CALLBACK")
                || signal.contains("租约") || signal.contains("LEASE")) {
            cases.add("给定同一渠道回传任务被两个 Worker 竞争，当任务被领取时，那么只有一个具名 Worker 获得有时限租约。");
            cases.add("给定回传执行失败或租约过期，当任务重新入队时，那么保留失败证据并按有界退避重试，非租约持有者不得确认完成。");
        }
        if (signal.contains("库存") || signal.contains("缺货") || signal.contains("预占") || signal.contains("SKU:")) {
            cases.add("给定需求量大于可用库存，当执行订单预占时，那么整单失败，`reserved` 与库存流水均不产生部分写入。");
            cases.add("给定并发请求竞争同一 SKU，当事务提交时，那么 `available = on_hand - reserved` 始终不小于 0。");
        }
        if (signal.contains("采购") || signal.contains("补货") || signal.contains("PURCHASE:")) {
            cases.add("给定补货建议尚未由具名人员批准，当尝试收货时，那么请求被阻断且库存不变。");
            cases.add("给定已批准采购，当相同入库幂等键重放时，那么库存和流水只增加一次。");
        }
        if (signal.contains("渠道") || signal.contains("平台") || signal.contains("CHANNEL:")
                || signal.contains("CHANNEL_ORDER:")) {
            cases.add("给定同一平台订单重复推送，当接入渠道订单时，那么只保留一个业务身份，变化重放必须显式冲突。");
            cases.add("给定 SKU 未映射、地址无效或库存不足，当统一审单时，那么异常保持可见且不得进入错误履约。");
        }
        if (signal.contains("订单") || signal.contains("履约") || signal.contains("发货")
                || signal.contains("SALES_ORDER:") || signal.contains("ORDER:")) {
            cases.add("给定订单未完成合法前置状态，当尝试发货时，那么状态迁移被拒绝。");
            cases.add("给定已预占订单被取消，当取消完成时，那么全部预占被释放并留下可追溯事件。");
        }
        if (cases.isEmpty()) {
            cases.add("给定需求中声明的业务对象，当执行正常路径时，那么对象状态产生可查询、可追溯的预期变化。");
            cases.add("给定任一前置条件不满足，当执行失败路径时，那么返回明确原因且不留下部分副作用。");
        }
        return new ArrayList<>(new LinkedHashSet<>(cases));
    }

    /** 写任务级 Spec（parse-before-publish：先解析校验再原子替换）。 */
    public static Path writeDeliverySpec(Path path, String request, String requirementId,
            List<String> businessRefs) {
        String text = buildDeliverySpec(request, requirementId, businessRefs);
        SpecParser.parse(text);
        return publish(path, text);
    }

    // ---- 讲次合同 Spec（create_course_task 面） ----------------------------------------------

    /** 讲次合同 → 六段 Spec 文本（上游 render_lesson_spec 模板逐字翻译）。 */
    public static String renderLessonSpec(int number, List<String> additionalEvalCases) {
        CourseContracts.LessonContract lesson = CourseContracts.lessonContract(number);
        String refs = lesson.businessRefs().isEmpty()
                ? "`REQUIREMENT:" + lesson.requirementId() + "`"
                : lesson.businessRefs().stream().map(item -> "`" + item + "`")
                        .reduce((left, right) -> left + "、" + right).orElseThrow();
        List<String> selectedEvals = new ArrayList<>(lesson.evalCases());
        selectedEvals.addAll(additionalEvalCases == null ? List.of() : additionalEvalCases);
        String evals = selectedEvals.isEmpty()
                ? "本讲合同中的正反路径"
                : selectedEvals.stream().map(item -> "`" + item + "`")
                        .reduce((left, right) -> left + "、" + right).orElseThrow();
        StringBuilder acceptance = new StringBuilder();
        for (int index = 0; index < lesson.acceptance().size(); index++) {
            acceptance.append(index + 1).append(". ").append(lesson.acceptance().get(index)).append("\n");
        }
        String prerequisites = lesson.prerequisites().isEmpty()
                ? "课程起点"
                : lesson.prerequisites().stream().map(item -> "L%02d".formatted(item))
                        .reduce((left, right) -> left + ", " + right).orElseThrow();
        String scope = lesson.writeScope().stream().map(item -> "`" + item + "`")
                .reduce((left, right) -> left + "、" + right).orElseThrow();
        CourseContracts.LessonStory story = CourseContracts.LESSON_STORY.get(number);
        // L7 特例三行（上游 hook_constraints 逐字——前导换行是模板分隔的一部分，保留）
        String hookConstraints = number == 7
                ? "\n- L07 先生成 `hook_staging/hooks.json` 与 `hook_staging/quality_gate.py`，"
                        + "人工审查后安装到实际候选的 `.codex/hooks.json` 与 `.codex/hooks/quality_gate.py`。"
                        + "待审查文件不会自动启用；Codex 执行不得写入受保护的 `.codex`。"
                        + "\n- 保留安装前内容与来源、审查者及安装后指纹；已有 Hook 配置须审查合并，不能覆盖其他项目规则。"
                        + "\n- 安装与真实触发属于独立人工复验步骤，必须与同一任务候选关联；"
                        + "暂存文件或模拟事件不能替代真实触发证据。"
                : "";
        return """
                # %s｜L%02d %s

                ## 来源

                - 课程主线合同：L%02d
                - 前置课次：%s
                - 业务对象：%s
                - 起始基线：`%s`

                ## 目标

                %s

                工作台增量：%s。%s
                ERP 产品增量：%s。

                课程建设主线：%s。
                本讲所在阶段：%s。
                Codex 当讲角色：%s。
                FDE 循环：%s。
                因果交接：%s。

                ## 非目标

                - 不提前实现后续课次的 ERP 产品增量。
                - 不修改本讲允许写集之外的文件。
                - 不删除失败证据、降低 Eval 等级或绕过具名人工审核。

                ## 约束

                - 允许写集：%s
                - 项目与路径归属：%s
                - 本讲复用 Eval：%s
                - 库存、订单、采购和任务状态必须遵守 `AGENTS.md` 的不可破坏规则。
                - 执行结果必须保存需求、Diff、命令、Eval 和人工决定之间的稳定引用。%s

                ## 验收用例

                %s## 完成定义

                - ERP 产品增量与工作台增量均有可复现证据，且能够说明二者因果。
                - 正常路径、失败路径和失败后不变状态均已验证。
                - 学生保存首次判断、失败、修订和复验结果；参考仓库终态不计作学生成果。
                - 若 `course-status --require-baselines` 未通过，不得声称完成了渐进式课程复现。
                """.formatted(
                lesson.requirementId(), number, lesson.title(),
                number, prerequisites, refs, lesson.baselineRef(),
                lesson.request(),
                lesson.workbenchIncrement(), "  ",
                lesson.erpIncrement(),
                CourseContracts.COURSE_BUILD_THESIS,
                CourseContracts.constructionStage(number),
                story.codexRole(), story.fdeLoop(), story.causalLink(),
                scope,
                CourseContracts.COURSE_WORKSPACE_BOUNDARY,
                evals,
                hookConstraints.isEmpty() ? "" : hookConstraints,
                acceptance);
    }

    /** 写讲次 Spec（同 parse-before-publish）。 */
    public static Path writeLessonSpec(int number, Path path, List<String> additionalEvalCases) {
        String text = renderLessonSpec(number, additionalEvalCases);
        SpecParser.parse(text);
        return publish(path, text);
    }

    // ---- 内部 ------------------------------------------------------------------

    private static Path publish(Path path, String text) {
        Path target = path.toAbsolutePath().normalize();
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new UncheckedIOException("Spec 写盘失败：" + target, error);
        }
        return target;
    }
}
