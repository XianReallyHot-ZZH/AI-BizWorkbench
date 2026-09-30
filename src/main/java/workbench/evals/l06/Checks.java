package workbench.evals.l06;

import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.evals.EvalHarness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * L06 登记项收口（Java 重走件，对照冻结 {@code evals/l06/checks.py}；讲义 §2 登记项构成，D4）。
 *
 * <p>五项（四 blocking + 一 observing，对上游 ENTRIES 四项；差异如实记录：上游
 * l05_personal_* 是其 L05 私有文件，本仓库对应物为指纹复核 + 客户用例回归）：
 * <ul>
 * <li>{@code l06_stock_consistency}     blocking  口径交叉检查（进程内调用
 *     {@link StockConsistencyCheck#runScenario}——Python 为子进程再跑 driver，方向与 L05
 *     相反的结构翻译，附录 A1/JD2；evidence/error 与子进程路径逐字节同形）；
 * <li>{@code l05_receiving_regression}  blocking  客户 blocking eval {@code receiving_is_idempotent}
 *     照跑（L05 能力不回归）；
 * <li>{@code l06_customer_stock_case}   blocking  客户 blocking eval {@code stock_never_negative}
 *     照跑（合同绑定 eval）；
 * <li>{@code l06_frozen_checks}         blocking  客户 eval 文件指纹复核（Java MessageDigest
 *     直算；词面逐字，12 位前缀）；
 * <li>{@code teaching_observation}      observing  如实标注的教学告警（演示非阻断语义，
 *     不是真实缺陷）。
 * </ul>
 *
 * <p>目标树经环境变量 {@code L06_EVAL_TARGET} 注入（冻结 Python 语义，argv 面不含 --target），
 * <b>解析在登记项内</b>（冻结 checks 的 _target() 逐项调用同形）：缺失/非客户树时各登记项以
 * {@link RuntimeError} 失败、被 harness 逐项捕获入报告（decision block、rc 1、stdout 即报告
 * 全文——golden s09 锁定的正是这一面），而非入口拒绝。报告经 {@link EvalHarness}（schema 1.0、
 * x 模式、退出码 = blocking 失败决定）；stdout 打印全量报告（indent=2 插入序，
 * json.dumps(indent=2) 同形）。客户用例子进程超时 300s（SUBPROCESS_TIMEOUT 同值）；尾部行
 * 原样入 evidence（客户汇总行无时间字段，字节稳定）。
 */
public final class Checks {

    private static final String[] FROZEN_EVAL_FILES = {"eval/harness.py", "eval/cases.py"};
    private static final long TIMEOUT_SECONDS = 300;
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    /**
     * Python 内建 RuntimeError 的同名面：报告 error.type 记 {@code "RuntimeError"}——
     * 目标树缺失/非客户树失败经 harness 入报告的字节保真载体（golden s09 对照）。
     */
    public static final class RuntimeError extends RuntimeException {
        public RuntimeError(String message) {
            super(message);
        }
    }

    private Checks() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--report-path", "--opening", "--reserved", "--python"),
                    Set.of("--no-report"));
            int opening = intOption(args, "--opening", 8);
            int reserved = intOption(args, "--reserved", 3);
            Path interpreter = StockProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
            Path reportPath = (args.flag("--no-report") || !args.has("--report-path"))
                    ? null : Path.of(args.require("--report-path"));
            EvalHarness.Outcome outcome = EvalHarness.run(
                    entries(interpreter, opening, reserved), "all", null, reportPath);
            System.out.println(PyJson.dumps(outcome.report()));
            if (outcome.exitCode() != 0) {
                System.exit(outcome.exitCode());
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (StockProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    /** 冻结 checks 的 _target() 同形：在登记项内逐项解析（缺 env/非客户树 → RuntimeError 入报告）。 */
    private static Path target() {
        String value = System.getenv("L06_EVAL_TARGET");
        if (value == null || value.isBlank()) {
            throw new RuntimeError("L06_EVAL_TARGET 未设置：本收口必须显式指定被测 flowERP 树");
        }
        Path target = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(target.resolve("flowerp"))) {
            throw new RuntimeError("L06_EVAL_TARGET 下没有 flowerp/：" + target);
        }
        return target;
    }

    private static List<EvalHarness.Entry> entries(Path interpreter, int opening, int reserved) {
        return List.of(
                new EvalHarness.Entry("l06_stock_consistency", "blocking",
                        () -> runDriver(interpreter, opening, reserved)),
                new EvalHarness.Entry("l05_receiving_regression", "blocking",
                        () -> customerCase(interpreter, "receiving_is_idempotent")),
                new EvalHarness.Entry("l06_customer_stock_case", "blocking",
                        () -> customerCase(interpreter, "stock_never_negative")),
                new EvalHarness.Entry("l06_frozen_checks", "blocking", Checks::frozenChecks),
                new EvalHarness.Entry("teaching_observation", "observing", () -> {
                    throw new AssertionError("教学观察项：模拟非阻断提示，不代表实际缺陷");
                }));
    }

    /** 登记项 evidence/失败格式与冻结 _subprocess_evidence 逐字节同形（"rc=N: "+末行，[:2000]）。 */
    private static String runDriver(Path interpreter, int opening, int reserved) {
        Path target = target();
        StockConsistencyCheck.Outcome outcome =
                StockConsistencyCheck.runScenario(target, interpreter, opening, reserved);
        if (outcome.code() != 0) {
            throw new AssertionError("rc=" + outcome.code() + ": " + truncate(outcome.line()));
        }
        return outcome.line();
    }

    private static String customerCase(Path interpreter, String caseName) {
        Path target = target();
        List<String> argv = List.of(interpreter.toString(), "-X", "utf8",
                "-m", "eval.harness", "--case", caseName, "--no-report");
        Process process;
        try {
            process = new ProcessBuilder(argv).directory(target.toFile()).start();
        } catch (IOException error) {
            throw new StockProbe.ToolFailure("python 启动失败（" + interpreter + "）", error);
        }
        String stdout;
        String stderr;
        int code;
        try {
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new StockProbe.ToolFailure(
                        "python 子进程超时（" + TIMEOUT_SECONDS + "s）：" + caseName);
            }
            code = process.exitValue();
        } catch (IOException error) {
            throw new StockProbe.ToolFailure("python 输出读取失败（" + caseName + "）", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new StockProbe.ToolFailure("python 等待被中断", error);
        }
        String output = (stdout + stderr).strip();
        String tail = output.isEmpty() ? "" : output.substring(output.lastIndexOf('\n') + 1);
        if (code != 0) {
            throw new AssertionError("rc=" + code + ": " + truncate(tail));
        }
        return tail;
    }

    private static String frozenChecks() {
        Path target = target();
        Map<String, Object> mismatched = new LinkedHashMap<>();
        for (String rel : FROZEN_EVAL_FILES) {
            String targetHash = sha256(target.resolve(rel));
            String sourceHash = sha256(repoRoot().resolve("vendors").resolve("flowERP").resolve(rel));
            if (!targetHash.equals(sourceHash)) {
                mismatched.put(rel, Map.of(
                        "target", targetHash.substring(0, 12),
                        "vendors", sourceHash.substring(0, 12)));
            }
        }
        if (!mismatched.isEmpty()) {
            throw new AssertionError("冻结检查被改动: " + PyJson.dumpsCompact(mismatched));
        }
        StringBuilder evidence = new StringBuilder("冻结检查指纹一致: ");
        for (int i = 0; i < FROZEN_EVAL_FILES.length; i++) {
            if (i > 0) {
                evidence.append(", ");
            }
            evidence.append(FROZEN_EVAL_FILES[i]).append('=')
                    .append(sha256(target.resolve(FROZEN_EVAL_FILES[i])).substring(0, 12));
        }
        return evidence.toString();
    }

    private static String truncate(String text) {
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new StockProbe.ToolFailure("冻结检查文件读取失败：" + file, error);
        }
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    }

    private static int intOption(Args args, String name, int defaultValue) {
        String raw = args.optional(name, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new Args.UsageException("argument " + name + ": invalid int value: '" + raw + "'");
        }
    }
}
