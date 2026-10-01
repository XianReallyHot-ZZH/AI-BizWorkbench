package workbench.evals.l07;

import com.fasterxml.jackson.databind.JsonNode;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.evals.EvalHarness;
import workbench.evals.l06.StockConsistencyCheck;

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
 * L07 统一入口收口（新讲机制——本讲无 Python 先例，映射源 = 讲义 §2 登记项构成 + 上游
 * {@code order_contract_checks.py} 三函数与 L06 检查接入手册的「七项指定检查」口径；
 * 差异如实记录：本仓库 l05/l06 件同仓在位，无需接入脚本）。gate（{@link QualityGate}）
 * 与人工显式复验调用同一本入口——同一套判定，两处触发。
 *
 * <p>六登记项（全 blocking，对上游七项指定检查）：
 * <ul>
 * <li>{@code l07_draft_amount}         两行草稿金额（独立预期 2×3000+3×2000=12000 分，
 *     明细 [6000,6000]；状态 draft；零库存可建草稿且三表不变——合同 acceptance[0]）
 * <li>{@code l07_rejected_order}       非法三态拒绝无残留（数量零/负 → ValueError、
 *     第二行商品不存在 → NotFound；订单头/明细/库存三表前后相等——acceptance[1]）
 * <li>{@code l07_amount_transfer}      迁移输入 3×2500+2×1800=11100 分（排除写死
 *     12000——acceptance[2] 的迁移面）
 * <li>{@code l07_order_total_regression} 客户 blocking eval {@code order_total_matches_lines}
 *     子进程照跑（合同绑定 eval，业务权威——ADR-0005）
 * <li>{@code l06_stock_regression}     L06 口径检查进程内复用（旧能力在 gate 面在场，
 *     默认 8/3——L06 检查接入手册「旧检查照跑不删弱」的映射）
 * <li>{@code l07_frozen_checks}        客户 eval 文件双树指纹复核（目标树 vs
 *     vendors/flowERP，l06 同款机制）
 * </ul>
 *
 * <p>argv 面：{@code --target} 必填（客户树根，缺省无——与 l06 Checks 的 env 注入不同：
 * 本讲无冻结 argv 面包袱，显式参数优先）；{@code --python}（缺省 .venv/bin/python）、
 * {@code --no-report}、{@code --report-path} 同 l06。报告经 {@link EvalHarness}
 * （schema 1.0、x 模式、退出码 = blocking 失败决定）；stdout 打印全量报告
 * （PyJson.dumps，indent=2 插入序）。客户用例子进程超时 300s。
 */
public final class OrderChecks {

    private static final String[] FROZEN_EVAL_FILES = {"eval/harness.py", "eval/cases.py"};
    private static final long TIMEOUT_SECONDS = 300;
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private OrderChecks() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--python", "--report-path"), Set.of("--no-report"));
            Path target = Path.of(args.require("--target")).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            Path interpreter = OrderProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
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
        } catch (OrderProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    /**
     * 六登记项清单。L08 起可见性开放（public，行为零变化）——l08 SalesChecks 的
     * {@code l07_order_regression} 登记项进程内复用本面（旧能力在场，旧检查照跑
     * 不删弱）；l06 {@code StockConsistencyCheck.runScenario} 开放给 l07 是同款先例。
     */
    public static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l07_draft_amount", "blocking",
                        () -> orderScenario(interpreter, target, "draft", 2, 3000, 3, 2000)),
                new EvalHarness.Entry("l07_rejected_order", "blocking",
                        () -> orderScenario(interpreter, target, "reject", 2, 3000, 3, 2000)),
                new EvalHarness.Entry("l07_amount_transfer", "blocking",
                        () -> orderScenario(interpreter, target, "transfer", 3, 2500, 2, 1800)),
                new EvalHarness.Entry("l07_order_total_regression", "blocking",
                        () -> customerCase(interpreter, target, "order_total_matches_lines")),
                new EvalHarness.Entry("l06_stock_regression", "blocking",
                        () -> l06StockRegression(target, interpreter)),
                new EvalHarness.Entry("l07_frozen_checks", "blocking",
                        () -> frozenChecks(target)));
    }

    /**
     * 订单场景登记项：探针回报原始观察值，预期与全部判断在本方法独立完成（不抄子进程
     * 返回——排除「检查与实现共用错误公式」的上游教学点；快照相等性同样在 Java 断言，
     * 复查轮 T-2 对齐探针纪律）；失败以 AssertionError 承载（evidence=异常消息，
     * 与 EvalHarness 条目异常捕获面一致）。
     */
    private static String orderScenario(Path interpreter, Path target, String scenario,
            int q1, int p1, int q2, int p2) {
        try (OrderProbe.TempDb tempDb = OrderProbe.tempDb("l07-order-")) {
            JsonNode observed = OrderProbe.order(
                    interpreter, target, tempDb.db, scenario, q1, p1, q2, p2);
            if ("reject".equals(scenario)) {
                for (JsonNode item : observed.path("cases")) {
                    String id = item.path("id").asText();
                    String expected = item.path("expected").asText();
                    String actual = item.path("exception").asText("");
                    if (!expected.equals(actual)) {
                        throw new AssertionError("拒绝用例 " + id + "：预期 " + expected
                                + "，实际 " + (actual.isEmpty() ? "未抛异常" : actual));
                    }
                    if (!item.path("after").equals(item.path("before"))) {
                        throw new AssertionError("拒绝用例 " + id + "：被拒绝但三表状态发生变化");
                    }
                }
                return "数量为零或负数、第二行商品不存在均拒绝；订单头、明细、库存三表保持原样";
            }
            // 显式订单号即稳定身份（合同 acceptance[0]，复查轮 T-1 补断言）
            String expectedId = "draft".equals(scenario) ? "L07-AMOUNT" : "L07-TRANSFER";
            if (!expectedId.equals(observed.path("order_id").asText())) {
                throw new AssertionError("expected=" + expectedId
                        + " actual=" + observed.path("order_id").asText());
            }
            int expectedTotal = q1 * p1 + q2 * p2;
            List<Integer> expectedLines = List.of(q1 * p1, q2 * p2);
            String status = observed.path("status").asText();
            int total = observed.path("total_cents").asInt(-1);
            List<Integer> lines = new ArrayList<>();
            observed.path("line_totals").forEach(node -> lines.add(node.asInt()));
            if (!"draft".equals(status)) {
                throw new AssertionError("expected=draft actual=" + status);
            }
            if (total != expectedTotal) {
                throw new AssertionError("expected=" + expectedTotal + " actual=" + total);
            }
            if (!lines.equals(expectedLines)) {
                throw new AssertionError("expected=" + expectedLines + " actual=" + lines);
            }
            if (!observed.path("stock_after").equals(observed.path("stock_before"))) {
                throw new AssertionError("创建草稿后库存表发生变化（草稿不预占，L08 才做原子预占）");
            }
            if ("draft".equals(scenario)) {
                return "两行金额 %d+%d=%d 分；状态 draft；显式订单号 %s；零库存可建草稿且库存表不变"
                        .formatted(q1 * p1, q2 * p2, expectedTotal, expectedId);
            }
            return "迁移输入：%d×%d+%d×%d=%d 分；排除写死 %d"
                    .formatted(q1, p1, q2, p2, expectedTotal, 2 * 3000 + 3 * 2000);
        }
    }

    /** 客户 blocking eval 子进程照跑（l06 Checks.customerCase 同形：末行入 evidence，rc≠0 → AssertionError）。 */
    private static String customerCase(Path interpreter, Path target, String caseName) {
        List<String> argv = List.of(interpreter.toString(), "-X", "utf8",
                "-m", "eval.harness", "--case", caseName, "--no-report");
        Process process;
        try {
            process = new ProcessBuilder(argv).directory(target.toFile()).start();
        } catch (IOException error) {
            throw new OrderProbe.ToolFailure("python 启动失败（" + interpreter + "）", error);
        }
        String stdout;
        String stderr;
        int code;
        try {
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new OrderProbe.ToolFailure(
                        "python 子进程超时（" + TIMEOUT_SECONDS + "s）：" + caseName);
            }
            code = process.exitValue();
        } catch (IOException error) {
            throw new OrderProbe.ToolFailure("python 输出读取失败（" + caseName + "）", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new OrderProbe.ToolFailure("python 等待被中断");
        }
        String output = (stdout + stderr).strip();
        String tail = output.isEmpty() ? "" : output.substring(output.lastIndexOf('\n') + 1);
        if (code != 0) {
            throw new AssertionError("rc=" + code + ": " + truncate(tail));
        }
        return tail;
    }

    /** L06 口径检查进程内复用（默认 8/3；l06 Checks.runDriver 同形）。 */
    private static String l06StockRegression(Path target, Path interpreter) {
        StockConsistencyCheck.Outcome outcome =
                StockConsistencyCheck.runScenario(target, interpreter, 8, 3);
        if (outcome.code() != 0) {
            throw new AssertionError("rc=" + outcome.code() + ": " + truncate(outcome.line()));
        }
        return outcome.line();
    }

    /** 客户 eval 文件双树指纹复核（l06 Checks.frozenChecks 同形：目标树 vs vendors/flowERP）。 */
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

    private static String truncate(String text) {
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new OrderProbe.ToolFailure("冻结检查文件读取失败：" + file, error);
        }
    }

    /** l06 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根），不随 cwd 漂移。 */
    private static Path repoRoot() {
        try {
            return Path.of(OrderChecks.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new OrderProbe.ToolFailure("仓库根定位失败（class 装载位置）", error);
        }
    }
}
