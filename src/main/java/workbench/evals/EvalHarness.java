package workbench.evals;

import workbench.bootstrap.PyJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 统一 Eval Harness（Java 重走件，对照冻结 {@code evals/harness.py}）：按登记清单分级执行
 * 检查，产出报告与一致的进程退出码。L06 合同 workbench_increment「统一 Harness、判决与
 * 退出码」的 Java 载体（L07 Hook 入口的同一裁判）。分级语义（CONTEXT.md）：blocking 失败
 * 即整体失败并给出非零退出码；observing 只记录不拦截。报告 schema_version 1.0 与客户
 * flowERP eval/harness 对齐（L08「本地/远端同一 Eval 身份」铺垫）；报告独占写入（x 模式），
 * 一份报告属于一次调用，绝不覆盖旧证据。一致性由 {@link ReportContract} 独立复核
 * （review 缝：可信红报告同样通过复核）。
 *
 * <p>纯库直译（附录 A1 结构翻译点①：无子进程依赖，进程内库语义同形）。已知语言绑定偏差
 * （JD6，如实披露）：选择非法抛 {@link IllegalArgumentException}（Python ValueError 同义面）；
 * 报告写失败以 {@link UncheckedIOException} 承载（Python OSError 原样上抛——其中 x 模式
 * 撞既有文件的 cause 为 FileAlreadyExistsException，FileExistsError 同义面）；
 * {@code generated_at} 为 Java ISO 偏移格式（Python isoformat 微差，掩码内不入对照）。
 * 条目异常捕获面 = {@code RuntimeException | AssertionError}（Python {@code except Exception}
 * 的同义面——Python AssertionError 是 Exception 子类，Java 是 Error，故显式并列；教学
 * 观察项的 error.type 因此记 {@code AssertionError}，与冻结面逐字）。
 */
public final class EvalHarness {

    public static final String SCHEMA_VERSION = "1.0";
    private static final Set<String> LEVELS = Set.of("blocking", "observing");
    private static final Set<String> SUITES = Set.of("all", "blocking", "observing");

    /** 登记项：(名, 等级, 检查可调用)——检查返回 evidence 字符串，失败以异常承载。 */
    public record Entry(String name, String level, Supplier<String> check) {}

    /** run 结果：(报告, 进程退出码)——存在 blocking 失败返回 1，否则 0。 */
    public record Outcome(Map<String, Object> report, int exitCode) {}

    private EvalHarness() {}

    /** 全默认面：suite=all、全量登记、不落盘（Python {@code run(entries)} 同形）。 */
    public static Outcome run(List<Entry> entries) {
        return run(entries, "all", null, null);
    }

    /**
     * 运行登记项。suite/names 选择子集（显式记录在 requested_cases；空选择、未知项、与
     * suite 不匹配、重复登记一律 {@link IllegalArgumentException}）；reportPath 非 null 时
     * 独占写入 JSON 报告（x 模式：撞既有文件即失败，绝不覆盖旧证据）。
     */
    public static Outcome run(List<Entry> entries, String suite, List<String> names, Path reportPath) {
        if (!SUITES.contains(suite)) {
            throw new IllegalArgumentException("未知 suite：" + suite);
        }
        List<String> registry = entries.stream().map(Entry::name).toList();
        if (registry.size() != Set.copyOf(registry).size()) {
            throw new IllegalArgumentException("登记项重名");
        }
        if (entries.stream().anyMatch(entry -> !LEVELS.contains(entry.level()))) {
            throw new IllegalArgumentException("登记项等级无效");
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>(names == null ? registry : names);
        List<Entry> selected = entries.stream()
                .filter(entry -> requested.contains(entry.name())
                        && ("all".equals(suite) || entry.level().equals(suite)))
                .toList();
        if (selected.isEmpty()
                || !requested.equals(new LinkedHashSet<>(selected.stream().map(Entry::name).toList()))) {
            throw new IllegalArgumentException("选择为空、含未知项或与 suite 不匹配");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Entry entry : selected) {
            long start = System.nanoTime();
            String evidence;
            boolean passed = true;
            Map<String, Object> error = null;
            try {
                evidence = entry.check().get();
            } catch (RuntimeException | AssertionError exc) {  // 检查崩溃不吞掉后续项，原因如实入报告
                evidence = String.valueOf(exc.getMessage());
                passed = false;
                error = new LinkedHashMap<>();
                error.put("type", exc.getClass().getSimpleName());
                error.put("message", String.valueOf(exc.getMessage()));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.name());
            item.put("level", entry.level());
            item.put("passed", passed);
            item.put("duration_ms", (int) Math.round((System.nanoTime() - start) / 1_000_000.0));
            item.put("evidence", evidence);
            item.put("error", error);
            results.add(item);
        }

        int blockingFailed = 0;
        int observingFailed = 0;
        int passedCount = 0;
        for (Map<String, Object> item : results) {
            boolean ok = (Boolean) item.get("passed");
            if (ok) {
                passedCount++;
            } else if ("blocking".equals(item.get("level"))) {
                blockingFailed++;
            } else {
                observingFailed++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", results.size());
        summary.put("passed", passedCount);
        summary.put("blocking_failed", blockingFailed);
        summary.put("observing_failed", observingFailed);
        summary.put("decision", blockingFailed > 0 ? "block" : "pass");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", SCHEMA_VERSION);
        report.put("suite", suite);
        report.put("requested_cases", new ArrayList<>(requested));
        report.put("generated_at", OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        report.put("summary", summary);
        report.put("results", results);

        if (reportPath != null) {
            writeReport(reportPath, PyJson.dumps(report));
        }
        return new Outcome(report, blockingFailed > 0 ? 1 : 0);
    }

    private static void writeReport(Path target, String content) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            // 一份报告属于一次调用；旧证据不可覆盖（x 模式，CREATE_NEW 即拒绝）。
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
