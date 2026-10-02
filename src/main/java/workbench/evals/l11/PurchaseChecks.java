package workbench.evals.l11;

import com.fasterxml.jackson.databind.JsonNode;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.evals.EvalHarness;
import workbench.schedule.Schedule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L11 统一入口收口（新讲机制——本讲无 Python 先例，映射源 = 讲义 §2 登记项构成 +
 * 上游 {@code purchase_request_lab.py} 七模式与合同绑定两 eval）。本地与显式复验
 * 调用同一本入口——「同一 Eval 身份」（{@link EvalHarness} schema 1.0，L06 已对齐
 * 客户）。
 *
 * <p>默认门九登记项（全 blocking，讲义 D2）：
 * <ul>
 * <li>{@code l11_purchase_normal} 正常申请：五字段（PR-TARGET / 规范化 A / 7 / 去空白
 *     理由 / proposed）+ approved_by 空 + 可再查 + 库存三量 10/2/8 + 除
 *     purchase_requests 恰 +1（PR-OTHER 不变）外四表不变（合同 acceptance[0]；
 *     预期由 Java 字面算出，不抄探针返回；注入树红点——过度副作用使库存拍失败）
 * <li>{@code l11_purchase_zero} / {@code _negative} / {@code _blank_reason} /
 *     {@code _unknown_sku} / {@code _duplicate_id} 五拒绝拍：预期异常类型 + 前后
 *     <b>五表快照相等</b>（合同 acceptance[2]「非法数量不产生采购申请」——部分写入
 *     风险面；duplicate-id 语义如实 = IntegrityError ≠ 幂等成功，上游钉死；注入树
 *     绿——提前入库副作用不破坏拒绝拍，隔离护栏与注入形态互证）
 * <li>{@code purchase_request_preserves_reason} 客户绑定 eval 子进程照跑（登记项名 =
 *     客户名原样，业务权威——ADR-0005；注入树绿——字段面不受库存副作用影响恰是
 *     「同版不同覆盖」教学点，红点构成如实入账）
 * <li>{@code write_sets_reject_conflict} 直测本讲 {@link Schedule} 核（登记项名对齐
 *     上游 eval 名；四组断言逐项对齐上游 {@code eval/cases.py:141}——冲突对内容 /
 *     共享写集拒绝 / 只读允许 / 目录覆盖与路径别名拒绝。合同 acceptance[1]「写集
 *     冲突的子任务不得并行」的工作台面默认门承载）
 * <li>{@code l11_frozen_checks} 客户 eval 双树 + 采购面源件（service.py）指纹复核
 *     （讲义 C8；注入树红点之二——注入行的指纹观察面）
 * </ul>
 *
 * <p>选跑项 {@code l11_purchase_premature_stock}（blocking，<b>不进默认门</b>——必红
 * 项：探针模式内人为提前入库，「申请字段绿 + 库存不变红」即上游 premature-stock
 * 教学失败报告的同款重现；L09 leak / L10 refuse-all 先例：检查发现注入缺陷是护栏
 * 不是把门卡死的污染）。
 *
 * <p>注入树红点构成（两项一因，讲义 §3 步骤 4）：normal + frozen_checks——同一注入
 * 行（marker 前插提前入库）的两个观察面；五拒绝拍与客户 eval 不受影响（隔离护栏）。
 *
 * <p>argv 面（l08–l10 Checks 同形）：{@code --target} 必填（客户树根，须含
 * flowerp/）；{@code --case}（可多，选跑子集——含选跑项）；{@code --python}
 * （缺省 .venv/bin/python，按进程 cwd 绝对化不查 PATH）、{@code --no-report}、
 * {@code --report-path}。stdout 打印全量报告；退出码 = blocking 失败决定。
 */
public final class PurchaseChecks {

    /** 冻结面（讲义 C8）：客户 eval 双树 + 采购面源件（注入 diff 即对账件）。 */
    private static final String[] FROZEN_FILES = {
            "eval/harness.py", "eval/cases.py", "flowerp/service.py"};
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private PurchaseChecks() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--python", "--report-path"), Set.of("--no-report"),
                    List.of(), Set.of("--case"));
            Path target = Path.of(args.require("--target")).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            Path interpreter = PurchaseProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
            Path reportPath = (args.flag("--no-report") || !args.has("--report-path"))
                    ? null : Path.of(args.require("--report-path"));
            List<String> selected = args.repeatingList("--case");
            List<EvalHarness.Entry> all = entries(target, interpreter);
            List<EvalHarness.Entry> run = selected.isEmpty() ? defaultEntries(all) : all.stream()
                    .filter(entry -> selected.contains(entry.name())).toList();
            if (!selected.isEmpty() && run.size() != selected.size()) {
                List<String> known = run.stream().map(EvalHarness.Entry::name).toList();
                List<String> unknown = selected.stream().filter(name -> !known.contains(name)).toList();
                if (!unknown.isEmpty()) {
                    throw new Args.UsageException("未知登记项 --case：" + unknown);
                }
                List<String> duplicated = selected.stream().distinct()
                        .filter(name -> selected.stream().filter(name::equals).count() > 1).toList();
                throw new Args.UsageException("重复登记项 --case：" + duplicated);
            }
            EvalHarness.Outcome outcome = EvalHarness.run(run, "all", null, reportPath);
            System.out.println(PyJson.dumps(outcome.report()));
            if (outcome.exitCode() != 0) {
                System.exit(outcome.exitCode());
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (PurchaseProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    /** 默认门 = 九件（premature-stock 选跑项排除——必红项不进默认门）。 */
    private static List<EvalHarness.Entry> defaultEntries(List<EvalHarness.Entry> all) {
        return all.stream()
                .filter(entry -> !"l11_purchase_premature_stock".equals(entry.name())).toList();
    }

    static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l11_purchase_normal", "blocking",
                        () -> purchaseScenario(interpreter, target, "normal")),
                new EvalHarness.Entry("l11_purchase_zero", "blocking",
                        () -> purchaseScenario(interpreter, target, "zero")),
                new EvalHarness.Entry("l11_purchase_negative", "blocking",
                        () -> purchaseScenario(interpreter, target, "negative")),
                new EvalHarness.Entry("l11_purchase_blank_reason", "blocking",
                        () -> purchaseScenario(interpreter, target, "blank-reason")),
                new EvalHarness.Entry("l11_purchase_unknown_sku", "blocking",
                        () -> purchaseScenario(interpreter, target, "unknown-sku")),
                new EvalHarness.Entry("l11_purchase_duplicate_id", "blocking",
                        () -> purchaseScenario(interpreter, target, "duplicate-id")),
                new EvalHarness.Entry("purchase_request_preserves_reason", "blocking",
                        () -> customerCase(interpreter, target, "purchase_request_preserves_reason")),
                new EvalHarness.Entry("write_sets_reject_conflict", "blocking",
                        () -> writeSetsRejectConflict()),
                new EvalHarness.Entry("l11_purchase_premature_stock", "blocking",
                        () -> purchaseScenario(interpreter, target, "premature-stock")),
                new EvalHarness.Entry("l11_frozen_checks", "blocking",
                        () -> frozenChecks(target)));
    }

    // ---- 场景断言（独立预期，全部留 Java） ------------------------------------------

    /**
     * 采购面场景：探针回报原始观察值，独立预期与全部判断在本方法完成（不抄子进程
     * 返回）。五表相等性、PR-OTHER 保护同样 Java 断言。premature-stock 模式断言
     * 「库存不变」——净树上探针自足提前入库使其必然失败（必红项语义 = 上游教学
     * 失败报告的同款重现；注入树同红——两径一义，红点构成如实入账）。
     */
    private static String purchaseScenario(Path interpreter, Path target, String mode) {
        try (PurchaseProbe.TempDb tempDb = PurchaseProbe.tempDb("l11-purchase-")) {
            JsonNode observed = PurchaseProbe.purchase(interpreter, target, tempDb.db, mode);
            JsonNode error = observed.path("error");
            JsonNode result = observed.path("result");
            switch (mode) {
                case "normal", "premature-stock" -> {
                    if (!error.isMissingNode() && !error.isNull()) {
                        throw new AssertionError("预期申请成功，实际被拒：" + error);
                    }
                    // 五字段独立预期（合同 acceptance[0]）：稳定身份 / 规范化 SKU /
                    // 数量 / 去空白理由 / proposed；无审批人
                    for (String[] field : new String[][]{
                            {"id", "PR-TARGET"}, {"sku", "A"}, {"reason", "低于补货点"},
                            {"status", "proposed"}}) {
                        if (!field[1].equals(result.path(field[0]).asText())) {
                            throw new AssertionError("expected " + field[0] + "=" + field[1]
                                    + " actual=" + result.path(field[0]).asText());
                        }
                    }
                    if (result.path("quantity").asInt(-1) != 7) {
                        throw new AssertionError("expected quantity=7 actual="
                                + result.path("quantity").asInt(-1));
                    }
                    if (!result.path("approved_by").isNull()
                            && !result.path("approved_by").isMissingNode()) {
                        throw new AssertionError("expected approved_by 空 actual="
                                + result.path("approved_by"));
                    }
                    if (!observed.path("repurchase").equals(result)) {
                        throw new AssertionError("稳定身份失效：purchase() 再查应等于原返回："
                                + observed.path("repurchase"));
                    }
                    // 独立预期：申请 ≠ 收货——三量仍 10/2/8（走 product() 计算列）
                    assertQuantities(observed.path("product_after"), 10, 2, 8);
                    // 五表：除 purchase_requests 恰 +1（且非 PR-TARGET 行 == before，
                    // PR-OTHER 保护）外全部不变
                    assertUnchangedExceptRequests(observed, "PR-TARGET");
                    return mode.equals("normal")
                            ? "正常申请：五字段保存、可再查、10/2/8 不变、PR-OTHER 保护、五表只多一行"
                            : "提前入库教学失败报告：字段绿、库存不变红（必红项——上游同款重现）";
                }
                case "zero", "negative", "blank-reason" -> {
                    assertRejected(error, "ValueError", mode, observed);
                    return "数量/原因非法拒绝：ValueError、五表不变";
                }
                case "unknown-sku" -> {
                    assertRejected(error, "NotFound", mode, observed);
                    return "未知 SKU 拒绝：NotFound、五表不变";
                }
                case "duplicate-id" -> {
                    // 语义如实（上游钉死）：唯一性冲突 = IntegrityError，不是幂等返回原成功
                    assertRejected(error, "IntegrityError", mode, observed);
                    return "重复编号拒绝：IntegrityError（非幂等成功）、五表不变";
                }
                default -> throw new AssertionError("未知模式：" + mode);
            }
        }
    }

    /** 拒绝拍共通：预期异常类型 + 前后五表快照相等（部分写入面）。 */
    private static void assertRejected(JsonNode error, String expectedType,
                                       String mode, JsonNode observed) {
        if (!error.isObject() || !expectedType.equals(error.path("type").asText())) {
            throw new AssertionError("mode=" + mode + " expected " + expectedType
                    + " 实际：" + error);
        }
        if (!observed.path("before").equals(observed.path("after"))) {
            throw new AssertionError("mode=" + mode + " 拒绝后五表状态发生变化（不应有半成品）");
        }
    }

    /** 三量断言（product() 计算列：available = on_hand - reserved 不在 stock 表）。 */
    private static void assertQuantities(JsonNode product, int onHand, int reserved, int available) {
        if (product.path("on_hand").asInt(-1) != onHand
                || product.path("reserved").asInt(-1) != reserved
                || product.path("available").asInt(-1) != available) {
            throw new AssertionError("expected (" + onHand + "," + reserved + "," + available
                    + ") actual=(" + product.path("on_hand").asInt(-1) + ","
                    + product.path("reserved").asInt(-1) + "," + product.path("available").asInt(-1) + ")");
        }
    }

    /** 五表对照：除 purchase_requests 恰 +1 且非目标行不变外，四表完全相等。 */
    private static void assertUnchangedExceptRequests(JsonNode observed, String targetId) {
        for (String table : new String[]{"stock", "inventory_events", "sales_orders",
                "sales_order_lines"}) {
            if (!observed.path("before").path(table).equals(observed.path("after").path(table))) {
                throw new AssertionError("申请改变了 " + table + "（申请 ≠ 收货，库存不得动）");
            }
        }
        JsonNode beforeRequests = observed.path("before").path("purchase_requests");
        JsonNode afterRequests = observed.path("after").path("purchase_requests");
        if (afterRequests.size() != beforeRequests.size() + 1) {
            throw new AssertionError("expected purchase_requests 恰 +1：before="
                    + beforeRequests.size() + " after=" + afterRequests.size());
        }
        int nonTarget = 0;
        for (JsonNode row : afterRequests) {
            if (!targetId.equals(row.path("id").asText())) {
                nonTarget++;
            }
        }
        if (nonTarget != beforeRequests.size()) {
            throw new AssertionError("非目标申请行发生变化（PR-OTHER 保护失效）");
        }
    }

    // ---- write_sets_reject_conflict（工作台面，直测本核） ------------------------

    /**
     * 绑定 eval（上游 CodexFDE {@code eval/cases.py:141} 四组断言逐项对齐）：
     * 冲突对内容 / 共享写集拒绝 / 只读允许 / 目录覆盖与路径别名拒绝。返回词面上游
     * 逐字。纯核直调（schedule 无 CLI——讲义 D1；本登记项即其公开调用面）。
     */
    private static String writeSetsRejectConflict() {
        List<Schedule.Conflict> conflicts = Schedule.conflictPairs(List.of(
                new Schedule.Subtask("impl", List.of("flowerp/purchasing.py")),
                new Schedule.Subtask("tests",
                        List.of("flowerp/purchasing.py", "tests/test_flowerp.py"))));
        if (conflicts.size() != 1
                || !"impl".equals(conflicts.get(0).left()) || !"tests".equals(conflicts.get(0).right())
                || !conflicts.get(0).shared().equals(List.of("flowerp/purchasing.py"))) {
            throw new AssertionError("冲突对内容不符：" + conflicts);
        }
        expectScheduleViolation(() -> Schedule.assertParallelSafe(List.of(
                        new Schedule.Subtask("impl", List.of("flowerp/service.py")),
                        new Schedule.Subtask("eval", List.of("flowerp/service.py")))),
                "共享写集仍被判为可并行");
        Map<String, Object> ok = Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("spec", List.of(), List.of("FDE_SPEC.md")),
                new Schedule.Subtask("risk", List.of(), List.of("AGENTS.md"))));
        if (!Boolean.TRUE.equals(ok.get("parallel"))) {
            throw new AssertionError("只读子任务应可并行：" + ok);
        }
        // 目录覆盖 + 路径别名（上游 for 循环两组同形）
        for (List<Schedule.Subtask> tasks : List.of(
                List.of(new Schedule.Subtask("impl", List.of("flowerp/")),
                        new Schedule.Subtask("review", List.of(), List.of("flowerp/service.py"))),
                List.of(new Schedule.Subtask("impl", List.of("flowerp\\service.py")),
                        new Schedule.Subtask("other", List.of("./flowerp/service.py"))))) {
            expectScheduleViolation(() -> Schedule.assertParallelSafe(tasks),
                    "目录写集、路径别名或读写依赖被错误判为独立");
        }
        return "共享写集、目录覆盖与读写依赖不得并行；只读子任务可以并行";
    }

    private static void expectScheduleViolation(Runnable probe, String message) {
        try {
            probe.run();
        } catch (Schedule.ScheduleViolation expected) {
            return;
        }
        throw new AssertionError(message);
    }

    // ---- 客户 eval 与冻结指纹（l08–l10 同形） ------------------------------------

    /** 客户 blocking eval 子进程照跑（l08 SalesChecks.customerCase 可见性开放复用）。 */
    private static String customerCase(Path interpreter, Path target, String caseName) {
        return workbench.evals.l08.SalesChecks.customerCase(interpreter, target, caseName);
    }

    /** 客户 eval 双树 + 采购面源件指纹复核（l07–l10 frozenChecks 扩面：采购面源件）。 */
    private static String frozenChecks(Path target) {
        Map<String, Object> mismatched = new LinkedHashMap<>();
        for (String rel : FROZEN_FILES) {
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
        for (int i = 0; i < FROZEN_FILES.length; i++) {
            if (i > 0) {
                evidence.append(", ");
            }
            evidence.append(FROZEN_FILES[i]).append('=')
                    .append(sha256(target.resolve(FROZEN_FILES[i])).substring(0, 12));
        }
        return evidence.toString();
    }

    private static String sha256(Path file) {
        try {
            return sha256(java.nio.file.Files.readAllBytes(file));
        } catch (java.io.IOException error) {
            throw new PurchaseProbe.ToolFailure("冻结检查文件读取失败：" + file, error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new PurchaseProbe.ToolFailure("SHA-256 不可用", error);
        }
    }

    /** l06–l10 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根），不随 cwd 漂移。 */
    private static Path repoRoot() {
        try {
            return Path.of(PurchaseChecks.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new PurchaseProbe.ToolFailure("仓库根定位失败（class 装载位置）", error);
        }
    }
}
