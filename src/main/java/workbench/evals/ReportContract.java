package workbench.evals;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Eval 报告合同（Java 重走件，对照冻结 {@code evals/report_contract.py}）：按调用方请求的
 * 用例身份复核报告，不信报告自己的绿色汇总。review 缝（L06 合同 acceptance[1]）：阻断失败、
 * 报告 decision 和进程退出码必须一致；汇总与逐项结果矛盾、报告结论与退出码矛盾、用例身份
 * 被替换——一律 {@link IllegalStateException}（Python RuntimeError 同义面，词面逐字）。
 * 可信红报告同样通过复核（复核只验「报告如实」，不验「业务通过」）。报告 schema 与客户
 * flowERP eval/harness 报告同形（schema_version 1.0），对两边的报告均可复核——客户报告
 * 交叉验证（D3 对齐实证）见 EvalHarnessContractTest。
 */
public final class ReportContract {

    private static final Set<String> LEVELS = Set.of("blocking", "observing");
    private static final List<String> SUMMARY_COUNT_KEYS =
            List.of("total", "passed", "blocking_failed", "observing_failed");

    private ReportContract() {}

    public static void validateReport(JsonNode report, List<String> caseNames, int returncode, String suite) {
        if (report == null || !report.isObject()
                || !report.path("schema_version").isTextual()
                || !"1.0".equals(report.path("schema_version").asText())) {
            throw new IllegalStateException("Eval 报告 Schema 无效");
        }
        if (!report.path("suite").isTextual() || !suite.equals(report.path("suite").asText())) {
            throw new IllegalStateException("Eval 报告 suite 与请求不一致");
        }

        JsonNode requested = report.path("requested_cases");
        if (!isStringSetEquals(requested, caseNames)) {
            throw new IllegalStateException("Eval 请求用例与本次任务不一致");
        }

        JsonNode results = report.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("Eval 结果必须包含实际用例");
        }
        for (JsonNode item : results) {
            if (!item.isObject()) {
                throw new IllegalStateException("Eval 结果必须包含实际用例");
            }
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : results) {
            if (!item.path("name").isTextual()) {
                break;
            }
            names.add(item.path("name").asText());
        }
        if (names.size() != results.size() || names.size() != stringSet(names).size()
                || !stringSet(names).equals(Set.copyOf(caseNames))) {
            throw new IllegalStateException("Eval 实际用例缺失、重复或被替换");
        }

        for (JsonNode item : results) {
            if (!item.path("passed").isBoolean()) {
                throw new IllegalStateException("Eval passed 必须为布尔值");
            }
            JsonNode level = item.path("level");
            if (!level.isTextual() || !LEVELS.contains(level.asText())) {
                throw new IllegalStateException("Eval 用例等级无效");
            }
            if (LEVELS.contains(suite) && !level.asText().equals(suite)) {
                throw new IllegalStateException("Eval 用例等级与 suite 不一致");
            }
        }

        int blockingFailed = 0;
        int observingFailed = 0;
        int passed = 0;
        for (JsonNode item : results) {
            if (item.path("passed").asBoolean()) {
                passed++;
            } else if (item.path("level").asText().equals("blocking")) {
                blockingFailed++;
            } else {
                observingFailed++;
            }
        }
        JsonNode summary = report.path("summary");
        if (!summary.isObject()) {
            throw new IllegalStateException("Eval 汇总与逐项结果不一致");
        }
        expectSummary(summary, "total", results.size());
        expectSummary(summary, "passed", passed);
        expectSummary(summary, "blocking_failed", blockingFailed);
        expectSummary(summary, "observing_failed", observingFailed);
        expectSummary(summary, "decision", blockingFailed > 0 ? "block" : "pass");
        for (String key : SUMMARY_COUNT_KEYS) {
            if (!summary.path(key).isInt()) {
                throw new IllegalStateException("Eval 汇总计数必须为整数");
            }
        }
        if (returncode != (blockingFailed > 0 ? 1 : 0)) {
            throw new IllegalStateException("Eval 进程退出码与报告结论不一致");
        }
    }

    /** requested_cases 面：字符串数组、无重复、集合与请求一致（Python 同一拒绝词面）。 */
    private static boolean isStringSetEquals(JsonNode node, List<String> caseNames) {
        if (!node.isArray()) {
            return false;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return false;
            }
            values.add(item.asText());
        }
        return values.size() == stringSet(values).size()
                && stringSet(values).equals(Set.copyOf(caseNames));
    }

    private static Set<String> stringSet(List<String> values) {
        return new HashSet<>(values);
    }

    private static void expectSummary(JsonNode summary, String key, Object expected) {
        JsonNode node = summary.path(key);
        boolean equal = expected instanceof String text
                ? node.isTextual() && text.equals(node.asText())
                : node.isNumber() && node.asInt() == (Integer) expected;
        if (!equal) {
            throw new IllegalStateException("Eval 汇总与逐项结果不一致");
        }
    }
}
