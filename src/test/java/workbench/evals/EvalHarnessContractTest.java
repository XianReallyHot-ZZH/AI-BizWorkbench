package workbench.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.bootstrap.PyJson;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一运行器合同测试（验收项→测试映射见下表）。上游九项运行器合同映射
 * （vendors/CodexFDE/docs/courses/L06/examples/test_runner_contract.py，语义对齐、实现自写
 * ——ADR-0001；对照冻结 tests/test_l06_eval_harness.py，Python 头同源映射）：
 * <pre>
 * 1  pass_and_report                              → C3/C4  passAndReport（含报告文件 == 打印内容逐字节）
 * 2  blocking_is_not_majority_vote                → C4     blockingIsNotMajorityVote
 * 3  observing_keeps_warning                      → C3     observingKeepsWarning
 * 4  exception_keeps_reason_and_continues         → C3     exceptionKeepsReasonAndContinues
 * 5  observing_exception_is_not_silently_promoted → C3     observingExceptionIsNotSilentlyPromoted
 * 6  invalid_selection                            → C3     invalidSelection（六形全拒）
 * 7  subset_is_explicit                           → C3     subsetIsExplicit
 * 8  write_failure_cannot_reuse_old_report        → C4     writeFailureCannotReuseOldReport
 * 9  consumer_rejects_summary_or_exit_contradiction → C4   consumerRejectsSummaryOrExitContradiction
 * +  customerReportPassesOurValidation            → C4/D3  客户 flowERP 报告过 ReportContract
 *    （schema 1.0 对齐的机检实证——Python 时代 record 69 的合同测试化）
 * </pre>
 *
 * <p>分级语义（CONTEXT.md）：blocking 失败即整体失败并给出非零退出码；observing 只记录不拦截。
 * 被测缝：{@code EvalHarness.run(entries) -> (report, exit_code)}——进程内直调（Python 时代
 * 复查轮 S6 口径承袭：合同面限 eval.harness 缝，非 workbench.cli 缝）；本文件因 Java 编译
 * 绑定随实现同落 commit 2（对应面的 commit 1 起始红由 golden s07–s09 工具级承载，Python
 * 同位面为 collection error 红，§R 披露）。
 */
class EvalHarnessContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String ok() {
        return "verified";
    }

    private static String fail() {
        throw new AssertionError("expected=5, actual=8");
    }

    private static String crash() {
        throw new RuntimeException("dependency unavailable; business result unknown");
    }

    @TempDir
    Path temp;

    @Test
    void passAndReport() throws Exception {
        Path reportPath = temp.resolve("report.json");
        EvalHarness.Outcome outcome = EvalHarness.run(
                List.of(new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::ok)),
                "all", null, reportPath);
        assertThat(outcome.exitCode()).isZero();
        assertThat(Files.readString(reportPath, StandardCharsets.UTF_8))
                .as("报告文件与打印内容逐字节一致（stdout 即报告全文，x 模式落盘同字节）")
                .isEqualTo(PyJson.dumps(outcome.report()));
        OffsetDateTime generatedAt = OffsetDateTime.parse((String) outcome.report().get("generated_at"));
        assertThat(generatedAt.getOffset()).as("generated_at 带时区").isNotNull();
        Map<?, ?> first = results(outcome).get(0);
        assertThat(first.get("duration_ms")).isInstanceOf(Integer.class);
        assertThat((Integer) first.get("duration_ms")).isGreaterThanOrEqualTo(0);
        ReportContract.validateReport(MAPPER.valueToTree(outcome.report()),
                List.of("a"), outcome.exitCode(), "all");
    }

    @Test
    void blockingIsNotMajorityVote() {
        EvalHarness.Outcome outcome = EvalHarness.run(List.of(
                new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::ok),
                new EvalHarness.Entry("b", "blocking", EvalHarnessContractTest::fail),
                new EvalHarness.Entry("c", "blocking", EvalHarnessContractTest::ok)));
        assertThat(outcome.exitCode())
                .as("HARNESS-BLOCK: 一项 blocking 失败必须阻断交付").isEqualTo(1);
        assertThat(summary(outcome).get("blocking_failed")).isEqualTo(1);
        assertThat((String) errorOf(results(outcome).get(1)).get("message")).contains("actual=8");
        ReportContract.validateReport(MAPPER.valueToTree(outcome.report()),
                List.of("a", "b", "c"), outcome.exitCode(), "all");
    }

    @Test
    void observingKeepsWarning() {
        EvalHarness.Outcome outcome = EvalHarness.run(List.of(
                new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::ok),
                new EvalHarness.Entry("b", "observing", EvalHarnessContractTest::fail)));
        assertThat(outcome.exitCode()).isZero();
        assertThat(summary(outcome).get("observing_failed")).isEqualTo(1);
        ReportContract.validateReport(MAPPER.valueToTree(outcome.report()),
                List.of("a", "b"), outcome.exitCode(), "all");
    }

    @Test
    void exceptionKeepsReasonAndContinues() {
        List<String> called = new ArrayList<>();
        EvalHarness.Outcome outcome = EvalHarness.run(List.of(
                new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::crash),
                new EvalHarness.Entry("b", "blocking", () -> {
                    called.add("b");
                    return "verified";
                })));
        assertThat(called).as("前项崩溃不得吞掉后续检查").containsExactly("b");
        assertThat(outcome.exitCode()).as("HARNESS-ERROR").isEqualTo(1);
        Map<?, ?> error = errorOf(results(outcome).get(0));
        assertThat(error.get("type")).isEqualTo("RuntimeException");
        assertThat((String) error.get("message")).contains("unknown");
    }

    @Test
    void observingExceptionIsNotSilentlyPromoted() {
        EvalHarness.Outcome outcome = EvalHarness.run(List.of(
                new EvalHarness.Entry("a", "observing", EvalHarnessContractTest::crash)));
        assertThat(outcome.exitCode()).isZero();
        assertThat(summary(outcome).get("observing_failed")).isEqualTo(1);
    }

    @Test
    void invalidSelection() {
        EvalHarness.Entry a = new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::ok);
        assertRejected(List.of(), null, "空登记");
        assertRejected(List.of(a), List.of(), "空选择");
        assertRejected(List.of(a), List.of("missing"), "未知用例");
        assertRejectedSuite(List.of(new EvalHarness.Entry("a", "observing", EvalHarnessContractTest::ok)),
                "blocking", "suite 过滤后为空");
        assertRejectedSuite(List.of(a), "wrong", "未知 suite");
        assertRejected(List.of(a, a), null, "重复登记");
    }

    @Test
    void subsetIsExplicit() {
        EvalHarness.Outcome outcome = EvalHarness.run(List.of(
                new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::ok),
                new EvalHarness.Entry("b", "observing", EvalHarnessContractTest::fail)),
                "all", List.of("a"), null);
        assertThat(outcome.exitCode()).isZero();
        assertThat(names(outcome)).containsExactly("a");
        assertThat(outcome.report().get("requested_cases")).isEqualTo(List.of("a"));
    }

    @Test
    void writeFailureCannotReuseOldReport() throws Exception {
        Path oldFile = temp.resolve("old.json");
        Files.writeString(oldFile, "old evidence");
        Path blocker = temp.resolve("file");
        Files.writeString(blocker, "ordinary file");
        assertThatThrownBy(() -> EvalHarness.run(
                List.of(new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::ok)),
                "all", null, blocker.resolve("new.json")))
                .as("写失败（父路径是普通文件）")
                .isInstanceOf(UncheckedIOException.class);
        assertThat(Files.readString(oldFile)).as("旧证据不得被波及").isEqualTo("old evidence");
        assertThatThrownBy(() -> EvalHarness.run(
                List.of(new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::ok)),
                "all", null, oldFile))
                .as("x 模式：撞既有报告文件即拒绝")
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void consumerRejectsSummaryOrExitContradiction() {
        EvalHarness.Outcome outcome = EvalHarness.run(List.of(
                new EvalHarness.Entry("a", "blocking", EvalHarnessContractTest::ok)));
        ObjectNode tampered = (ObjectNode) MAPPER.valueToTree(outcome.report());
        ((ObjectNode) tampered.get("summary")).put("passed", 0);
        assertThatThrownBy(() -> ReportContract.validateReport(
                tampered, List.of("a"), outcome.exitCode(), "all"))
                .as("汇总与逐项矛盾必须被拒绝")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReportContract.validateReport(
                MAPPER.valueToTree(outcome.report()), List.of("a"), 1, "all"))
                .as("报告结论与退出码矛盾必须被拒绝")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customerReportPassesOurValidation() throws Exception {
        Path cloneDir = temp.resolve("flowerp");
        git(temp, "clone", "--quiet", "--no-hardlinks",
                Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize()
                        .resolve("vendors/flowERP").toString(), "flowerp");
        Path reportFile = temp.resolve("customer-report.json");
        List<String> argv = List.of(
                Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize()
                        .resolve(".venv/bin/python").toString(),
                "-X", "utf8", "-m", "eval.harness",
                "--case", "receiving_is_idempotent", "--report-path", reportFile.toString());
        Process process = new ProcessBuilder(argv).directory(cloneDir.toFile()).start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("客户 harness 产真报告：%s", stderr).isZero();
        JsonNode report = MAPPER.readTree(reportFile.toFile());
        // D3 schema 1.0 对齐实证：客户报告（其自身 run_suite 产出）过本仓库 ReportContract
        ReportContract.validateReport(report, List.of("receiving_is_idempotent"), 0, "all");
    }

    // ---- 断言小面 ------------------------------------------------------------

    private static void assertRejected(List<EvalHarness.Entry> entries, List<String> names, String label) {
        assertThatThrownBy(() -> EvalHarness.run(entries, "all", names, null))
                .as(label).isInstanceOf(IllegalArgumentException.class);
    }

    /** 独立方法名而非重载——{@code null} 实参在 (List, String) 两可（J2 教训同源）。 */
    private static void assertRejectedSuite(List<EvalHarness.Entry> entries, String suite, String label) {
        assertThatThrownBy(() -> EvalHarness.run(entries, suite, null, null))
                .as(label).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(EvalHarness.Outcome outcome) {
        return (List<Map<String, Object>>) outcome.report().get("results");
    }

    private static Map<String, Object> summary(EvalHarness.Outcome outcome) {
        return (Map<String, Object>) outcome.report().get("summary");
    }

    private static List<String> names(EvalHarness.Outcome outcome) {
        return results(outcome).stream().map(item -> (String) item.get("name")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> item) {
        return (Map<String, Object>) item.get("error");
    }

    private static void git(Path dir, String... argv) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(argv));
        Process process = new ProcessBuilder(command).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git 夹具失败：" + String.join(" ", command));
        }
    }
}
