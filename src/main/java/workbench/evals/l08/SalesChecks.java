package workbench.evals.l08;

import com.fasterxml.jackson.databind.JsonNode;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.evals.EvalHarness;
import workbench.evals.l07.OrderChecks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * L08 统一入口收口（新讲机制——本讲无 Python 先例，映射源 = 讲义 §2 登记项构成 +
 * 上游 {@code atomic_reservation_lab.py} 三模式与合同绑定 eval 三件）。本地、CI
 * 工作流与显式复验调用同一本入口——「本地与 CI 使用同一 Eval 身份」（合同
 * acceptance[2]）：同一命令、同一组登记项、同一报告 schema（{@link EvalHarness}
 * schema 1.0，L06 已对齐客户）。
 *
 * <p>八登记项（全 blocking）：
 * <ul>
 * <li>{@code l08_atomic_reservation_pass}        库存充足整单预占（独立预期
 *     reserved [2,3]、available [3,2]、status reserved、预占记录 2 条——合同
 *     acceptance[0]；预期由 Java 字面算出，不抄探针返回）
 * <li>{@code l08_atomic_reservation_shortage}    任一行缺货整单拒绝（B 仅 2 件：
 *     规划期即抛 InsufficientStock、四表 before==after、status confirmed、
 *     reserved [0,0]——acceptance[1] 缺货面；普通缺货不足以证明原子性，如实
 *     分开登记）
 * <li>{@code l08_atomic_reservation_write_error} 写入中途故障整单回滚（临时库
 *     标注触发器令第二条预占插入失败——四表不变、错误含注入标记；缺陷树
 *     （{@code InjectDefect} 提前提交教学缺陷）下本项红＝唯一红点，原子性区分器）
 * <li>{@code stock_never_negative} / {@code sales_credit_and_atomic_reservation}
 *     客户绑定 eval 子进程照跑（登记项名 = 客户名原样，业务权威——ADR-0005）
 * <li>{@code ci_evidence_envelope_is_honest}     信封六边界机检（合同名原样；
 *     上游 envelope_boundary_lab 映射，进程内驱动 {@link CiEvidence#buildEnvelope}）
 * <li>{@code l07_order_regression}               L07 面进程内复用（旧能力在场，
 *     「旧检查照跑不删弱」；l07 → l08 的 StockConsistencyCheck 可见性开放同款先例）
 * <li>{@code l08_frozen_checks}                  客户 eval 文件双树指纹复核
 *     （目标树 vs vendors/flowERP——注入只动 flowerp/sales.py 不动 eval，缺陷树
 *     下本项仍绿，红点唯一性保持）
 * </ul>
 *
 * <p>argv 面：{@code --target} 必填（客户树根，须含 flowerp/）；{@code --python}
 * （缺省 .venv/bin/python，**按进程 cwd 绝对化不查 PATH——CI 须传绝对路径**）、
 * {@code --no-report}、{@code --report-path}（x 模式独占写）。stdout 打印全量报告；
 * 退出码 = blocking 失败决定。客户用例子进程超时 300s（l06/l07 同值）。
 */
public final class SalesChecks {

    private static final String[] FROZEN_EVAL_FILES = {"eval/harness.py", "eval/cases.py"};
    private static final long TIMEOUT_SECONDS = 300;
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private SalesChecks() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--python", "--report-path"), Set.of("--no-report"));
            Path target = Path.of(args.require("--target")).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            Path interpreter = AtomicProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
            Path reportPath = (args.flag("--no-report") || !args.has("--report-path"))
                    ? null : Path.of(args.require("--report-path"));
            EvalHarness.Outcome outcome = EvalHarness.run(
                    entries(target, interpreter), "all", null, reportPath);
            System.out.println(PyJson.dumps(outcome.report()));
            if (outcome.exitCode() != 0) {
                System.exit(outcome.exitCode());
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (AtomicProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l08_atomic_reservation_pass", "blocking",
                        () -> atomicScenario(interpreter, target, "pass")),
                new EvalHarness.Entry("l08_atomic_reservation_shortage", "blocking",
                        () -> atomicScenario(interpreter, target, "shortage")),
                new EvalHarness.Entry("l08_atomic_reservation_write_error", "blocking",
                        () -> atomicScenario(interpreter, target, "write-error")),
                new EvalHarness.Entry("stock_never_negative", "blocking",
                        () -> customerCase(interpreter, target, "stock_never_negative")),
                new EvalHarness.Entry("sales_credit_and_atomic_reservation", "blocking",
                        () -> customerCase(interpreter, target, "sales_credit_and_atomic_reservation")),
                new EvalHarness.Entry("ci_evidence_envelope_is_honest", "blocking",
                        SalesChecks::envelopeHonesty),
                new EvalHarness.Entry("l07_order_regression", "blocking",
                        () -> l07Regression(target, interpreter)),
                new EvalHarness.Entry("l08_frozen_checks", "blocking",
                        () -> frozenChecks(target)));
    }

    /**
     * 原子预占场景登记项：探针回报原始观察值，独立预期与全部判断在本方法完成
     * （不抄子进程返回——排除「检查与实现共用错误公式」；四表相等性同样 Java 断言）。
     * 失败以 AssertionError 承载（EvalHarness 条目异常捕获面一致）。
     */
    private static String atomicScenario(Path interpreter, Path target, String mode) {
        try (AtomicProbe.TempDb tempDb = AtomicProbe.tempDb("l08-atomic-")) {
            JsonNode observed = AtomicProbe.atomic(interpreter, target, tempDb.db, mode);
            JsonNode error = observed.path("error");
            boolean rejected = !error.isMissingNode() && !error.isNull();
            if ("pass".equals(mode)) {
                if (rejected) {
                    throw new AssertionError("预期整单预占成功，实际异常：" + error);
                }
                if (!"reserved".equals(observed.path("status").asText())) {
                    throw new AssertionError("expected status=reserved actual="
                            + observed.path("status").asText());
                }
                assertIntList(observed.path("reserved"), List.of(2, 3), "reserved");
                assertIntList(observed.path("available"), List.of(3, 2), "available");
                if (observed.path("reservation_count").asInt(-1) != 2) {
                    throw new AssertionError("expected 预占记录 2 条 actual="
                            + observed.path("reservation_count").asInt(-1));
                }
                return "库存充足整单预占：reserved [2,3]、available [3,2]、status reserved、预占记录 2 条";
            }
            // shortage / write-error：整单失败且无部分预占——四表完整对照，不只看错误消息
            if (!rejected) {
                throw new AssertionError("expected 整单被拒绝，实际无异常（mode=" + mode + "）");
            }
            if (!observed.path("before").equals(observed.path("after"))) {
                throw new AssertionError("失败后四表状态发生变化（部分预占残留）：error=" + error);
            }
            if (!"confirmed".equals(observed.path("status").asText())) {
                throw new AssertionError("先确认再预占的入口合同：失败后 expected status=confirmed actual="
                        + observed.path("status").asText());
            }
            assertIntList(observed.path("reserved"), List.of(0, 0), "reserved");
            if ("write-error".equals(mode)) {
                if (!error.path("message").asText("").contains("L08 injected second-write failure")) {
                    throw new AssertionError("write-error 期望注入触发器标记，实际：" + error);
                }
                return "写入中途故障整单回滚：四表 before==after、status confirmed、无残留预占（触发器标记在场）";
            }
            if (!"InsufficientStock".equals(error.path("type").asText())) {
                throw new AssertionError("shortage expected InsufficientStock actual="
                        + error.path("type").asText());
            }
            return "第二行缺货整单拒绝：InsufficientStock、四表 before==after、status confirmed、无部分预占";
        }
    }

    /** 客户 blocking eval 子进程照跑（l07 OrderChecks.customerCase 同形：末行入 evidence，rc≠0 → AssertionError）。 */
    private static String customerCase(Path interpreter, Path target, String caseName) {
        List<String> argv = List.of(interpreter.toString(), "-X", "utf8",
                "-m", "eval.harness", "--case", caseName, "--no-report");
        Process process;
        try {
            process = new ProcessBuilder(argv).directory(target.toFile()).start();
        } catch (IOException error) {
            throw new AtomicProbe.ToolFailure("python 启动失败（" + interpreter + "）", error);
        }
        String stdout;
        String stderr;
        int code;
        try {
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AtomicProbe.ToolFailure(
                        "python 子进程超时（" + TIMEOUT_SECONDS + "s）：" + caseName);
            }
            code = process.exitValue();
        } catch (IOException error) {
            throw new AtomicProbe.ToolFailure("python 输出读取失败（" + caseName + "）", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AtomicProbe.ToolFailure("python 等待被中断");
        }
        String output = (stdout + stderr).strip();
        String tail = output.isEmpty() ? "" : output.substring(output.lastIndexOf('\n') + 1);
        if (code != 0) {
            throw new AssertionError("rc=" + code + ": " + truncate(tail));
        }
        return tail;
    }

    /**
     * 信封六边界机检（上游 envelope_boundary_lab 六模式映射）：缺报告/缺身份/畸形
     * JSON 拒绝、空报告决策如实为空、字节篡改哈希失配可检——五面进程内驱动
     * {@link CiEvidence#buildEnvelope}；stale-output（旧输出不冒充本次）是 CLI 层
     * 行为，由合同测试 {@code ciEvidenceLeavesStaleOutputUntouchedOnFailure} 锁定
     * （mvn 面），此处如实注明出处。
     */
    private static String envelopeHonesty() {
        try (AtomicProbe.TempDb tempDb = AtomicProbe.tempDb("l08-envelope-")) {
            Path dir = tempDb.db.getParent();
            Path report = dir.resolve("report.json");
            String body = "{\"schema_version\":\"1.0\",\"suite\":\"blocking\","
                    + "\"summary\":{\"decision\":\"pass\",\"total\":1,\"passed\":1,"
                    + "\"blocking_failed\":0,\"observing_failed\":0},\"results\":[]}";
            java.nio.file.Files.writeString(report, body, StandardCharsets.UTF_8);
            Map<String, String> identity = Map.of("GITHUB_SHA", "HONESTY-SHA", "GITHUB_RUN_ID", "HONESTY-RUN");

            // 边界一：报告不存在 → 拒绝
            expectEvidenceFailure(() -> CiEvidence.buildEnvelope(dir.resolve("absent.json"), identity),
                    "Harness 报告不存在");
            // 边界二：缺运行身份 → 拒绝（词面逐字）
            expectEvidenceFailure(() -> CiEvidence.buildEnvelope(report, Map.of()),
                    "Evidence Envelope 缺少 GITHUB_SHA 或 GITHUB_RUN_ID");
            // 边界三：畸形 JSON → 拒绝
            Path malformed = dir.resolve("malformed.json");
            Files.writeString(malformed, "{bad", StandardCharsets.UTF_8);
            expectEvidenceFailure(() -> CiEvidence.buildEnvelope(malformed, identity), "报告不是合法 JSON");
            // 边界四：空报告 → 信封仍生成，决策与 suite 如实为空（信封不做报告语义校验）
            Path empty = dir.resolve("empty.json");
            Files.writeString(empty, "{}", StandardCharsets.UTF_8);
            Map<String, Object> emptyEnvelope = CiEvidence.buildEnvelope(empty, identity);
            if (emptyEnvelope.get("report_decision") != null || !"".equals(emptyEnvelope.get("suite"))) {
                throw new AssertionError("空报告信封应如实为空：" + PyJson.dumpsCompact(emptyEnvelope));
            }
            // 边界五：报告字节篡改 → 生成时哈希与当前字节失配（篡改可检）
            Map<String, Object> envelope = CiEvidence.buildEnvelope(report, identity);
            Files.writeString(report, body.replace("\"pass\"", "\"block\""), StandardCharsets.UTF_8);
            if (String.valueOf(envelope.get("report_sha256")).equals(sha256(Files.readAllBytes(report)))) {
                throw new AssertionError("报告被篡改后哈希仍然匹配——篡改不可检");
            }
            return "信封六边界诚实：缺报告/缺身份/畸形 JSON 拒绝、空报告决策如实为空、字节篡改哈希失配可检"
                    + "；stale-output 由 CLI 面合同测试锁定（信封是关联证据，非业务裁判）";
        } catch (IOException error) {
            throw new AtomicProbe.ToolFailure("信封机检临时文件失败", error);
        }
    }

    /** L07 面进程内复用（{@link OrderChecks#entries} 可见性开放——旧能力在场，行为零变化）。 */
    private static String l07Regression(Path target, Path interpreter) {
        EvalHarness.Outcome outcome = EvalHarness.run(
                OrderChecks.entries(target, interpreter), "all", null, null);
        if (outcome.exitCode() != 0) {
            throw new AssertionError("l07 回归红：" + PyJson.dumpsCompact(outcome.report().get("summary")));
        }
        return "l07 六登记项进程内复用全绿（旧检查照跑不删弱）";
    }

    /** 客户 eval 文件双树指纹复核（l07 OrderChecks.frozenChecks 同形：目标树 vs vendors/flowERP）。 */
    private static String frozenChecks(Path target) {
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

    // ---- 断言小面 ------------------------------------------------------------

    private static void assertIntList(JsonNode node, List<Integer> expected, String label) {
        if (node.size() != expected.size()) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + node);
        }
        for (int i = 0; i < expected.size(); i++) {
            if (node.get(i).asInt(-1) != expected.get(i)) {
                throw new AssertionError(label + " expected=" + expected + " actual=" + node);
            }
        }
    }

    private static void expectEvidenceFailure(Runnable call, String messagePart) {
        try {
            call.run();
        } catch (CiEvidence.EvidenceException error) {
            if (!String.valueOf(error.getMessage()).contains(messagePart)) {
                throw new AssertionError("拒绝词面不符：expected 含「" + messagePart
                        + "」actual「" + error.getMessage() + "」");
            }
            return;
        }
        throw new AssertionError("expected 拒绝（" + messagePart + "），实际生成了信封");
    }

    private static String truncate(String text) {
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private static String sha256(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new AtomicProbe.ToolFailure("冻结检查文件读取失败：" + file, error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new AtomicProbe.ToolFailure("SHA-256 不可用", error);
        }
    }

    /** l06/l07 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根），不随 cwd 漂移。 */
    private static Path repoRoot() {
        try {
            return Path.of(SalesChecks.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new AtomicProbe.ToolFailure("仓库根定位失败（class 装载位置）", error);
        }
    }
}
