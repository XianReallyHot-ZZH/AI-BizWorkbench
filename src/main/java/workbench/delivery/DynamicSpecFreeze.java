package workbench.delivery;

import workbench.spec.SpecParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 动态 Eval 冻结关联纯核（L15，合同 {@code dynamic_eval_required=true} 首见的承载；
 * 上游 {@code workbench/course_requirement.py} freeze_requirement_spec +
 * {@code course_workspace.py:170-187} dynamic_start_evidence +
 * {@code course_delivery.py:42-44} 重名拒绝的断言面子集）。
 *
 * <p><b>freeze</b>：本次需求必须提供六段式 Spec（仅 L04/L15/L16，词面「自定义课程
 * Spec 仅用于 L04、L15/L16」），非空且 ≤40000 字符；验收段须**词边界正则**显式关联
 * 每个动态用例（词面上游逐字「本次 Spec 的验收用例须明确关联：…」）；约束段注入
 * 课程执行写集（「本次需求不得扩大课程写集；先确认新增验收失败，范围内实现后独立
 * 复验。」）、完成定义段注入课程通过标准（「检查通过仍须具名人审，不自动接受或
 * 合并。」）——冻结文本再解析并返回 {text, sha256, goal, acceptance, non_goals}。
 *
 * <p><b>dynamicNameCheck</b>：动态用例必须是本次需求新增，与静态合同 Eval 重名即拒
 * （词面上游逐字）。<b>startEvidence</b>：起始红形式化——报告结构有效（results
 * 逐项含 name/passed）+ **既有合同全绿 + 至少一个新增用例真实失败**（词面上游逐字
 * 「既有合同通过，至少一个本次新增用例真实失败」），accepted 三条件合取。
 *
 * <p>纯函数件无 CLI 面（上游 freeze_requirement_spec 同形经 submit_course 调用；
 * L11 Schedule 纯核先例——断言面经 l15 登记项与合同测试双承载）。
 */
public final class DynamicSpecFreeze {

    /** 允许自定义课程需求 Spec 的讲次（上游 dynamic_eval_required ∪ L04）。 */
    public static final LinkedHashSet<Integer> FREEZE_LESSONS =
            new LinkedHashSet<>(List.of(4, 15, 16));

    private DynamicSpecFreeze() {}

    /** 冻结本次课程需求 Spec（上游 freeze_requirement_spec 断言面）。 */
    public static Map<String, Object> freeze(int lessonNumber, String text,
            List<String> dynamicCases, List<String> writeScope, List<String> acceptance,
            boolean required) {
        if (!FREEZE_LESSONS.contains(lessonNumber)) {
            throw new IllegalArgumentException("自定义课程需求 Spec 仅用于 L04、L15/L16");
        }
        if (text == null) {
            if (required) {
                throw new IllegalArgumentException("L15/L16 代码执行必须提供本次六段式需求 Spec");
            }
            return null;
        }
        if (text.isBlank() || text.length() > 40000) {
            throw new IllegalArgumentException("本次需求 Spec 必须是非空文本，最多 40000 字符");
        }
        SpecParser.ParsedSpec spec = SpecParser.parse(text);
        for (String dynamicCase : dynamicCases) {
            Pattern boundary = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + Pattern.quote(dynamicCase) + "(?![A-Za-z0-9_])");
            if (!boundary.matcher(spec.acceptance()).find()) {
                throw new IllegalArgumentException("本次 Spec 的验收用例须明确关联：" + dynamicCase);
            }
        }
        List<String> lines = new ArrayList<>();
        for (String item : acceptance) {
            lines.add("- " + item);
        }
        String frozen = "# 本次课程需求\n\n"
                + "## 来源\n\n" + spec.source() + "\n\n"
                + "## 目标\n\n" + spec.goal() + "\n\n"
                + "## 非目标\n\n" + spec.nonGoals() + "\n\n"
                + "## 约束\n\n" + spec.constraints()
                + "\n\n课程执行写集：" + String.join("、", writeScope)
                + "\n本次需求不得扩大课程写集；先确认新增验收失败，范围内实现后独立复验。\n\n"
                + "## 验收用例\n\n" + spec.acceptance() + "\n\n"
                + "## 完成定义\n\n" + spec.done() + "\n\n课程通过标准：\n"
                + String.join("\n", lines)
                + "\n检查通过仍须具名人审，不自动接受或合并。\n";
        SpecParser.ParsedSpec parsed = SpecParser.parse(frozen);
        return Map.of("text", frozen, "sha256", sha256(frozen),
                "goal", parsed.goal(), "acceptance", parsed.acceptance(),
                "non_goals", parsed.nonGoals());
    }

    /** 动态用例重名拒绝（上游 course_delivery.py:42-44 词面逐字）。 */
    public static void dynamicNameCheck(List<String> dynamicCases, List<String> staticCases) {
        List<String> distinct = distinct(dynamicCases);
        LinkedHashSet<String> staticSet = new LinkedHashSet<>(staticCases);
        List<String> reused = new ArrayList<>();
        for (String item : distinct) {
            if (staticSet.contains(item)) {
                reused.add(item);
            }
        }
        if (!reused.isEmpty()) {
            throw new IllegalArgumentException(
                    "动态 Eval 必须是本次需求新增用例，不能重复静态合同 Eval："
                            + String.join(", ", reused));
        }
    }

    /**
     * 起始证据核对（上游 dynamic_start_evidence 断言面）：既有合同通过，至少一个
     * 本次新增用例真实失败——accepted 三条件合取（报告结构有效 && dynamic_failed
     * 非空 && static_failed 空）。
     */
    public static Map<String, Object> startEvidence(Map<String, Object> report,
            List<String> staticCases, List<String> dynamicCases) {
        String error = null;
        Object results = report == null ? null : report.get("results");
        if (!(results instanceof List<?> rows) || rows.isEmpty()) {
            error = "报告缺少逐项 results";
        } else {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> item)
                        || !(item.get("name") instanceof String)
                        || !(item.get("passed") instanceof Boolean)) {
                    error = "报告 results 逐项须含 name 与 passed";
                    break;
                }
            }
        }
        LinkedHashSet<String> failures = new LinkedHashSet<>();
        if (error == null) {
            for (Object row : (List<?>) results) {
                Map<?, ?> item = (Map<?, ?>) row;
                if (!(Boolean) item.get("passed")) {
                    failures.add((String) item.get("name"));
                }
            }
        }
        List<String> staticFailed = new ArrayList<>();
        for (String item : failures) {
            if (staticCases.contains(item)) {
                staticFailed.add(item);
            }
        }
        List<String> dynamicFailed = new ArrayList<>();
        for (String item : failures) {
            if (dynamicCases.contains(item)) {
                dynamicFailed.add(item);
            }
        }
        boolean accepted = error == null && !dynamicFailed.isEmpty() && staticFailed.isEmpty();
        return Map.of("accepted", accepted,
                "static_failed", staticFailed,
                "dynamic_failed", dynamicFailed,
                "validation_error", error == null ? "" : error,
                "requirement", "既有合同通过，至少一个本次新增用例真实失败");
    }

    private static List<String> distinct(List<String> items) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                out.add(item.strip());
            }
        }
        return new ArrayList<>(out);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 计算失败", error);
        }
    }
}
