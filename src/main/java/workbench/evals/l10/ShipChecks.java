package workbench.evals.l10;

import com.fasterxml.jackson.databind.JsonNode;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.evals.EvalHarness;

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
 * L10 统一入口收口（新讲机制——本讲无 Python 先例，映射源 = 讲义 §2 登记项构成 +
 * 上游 {@code order_transition_lab.py} 五模式与合同绑定 eval）。本地与显式复验调用同一
 * 本入口——「同一 Eval 身份」（{@link EvalHarness} schema 1.0，L06 已对齐客户）。
 *
 * <p>六登记项（全 blocking，讲义 D3）：
 * <ul>
 * <li>{@code l10_ship_legal}   合法发货：A 在库 10 / OTHER 预占 2 / TARGET 预占 4 →
 *     发货后 (on_hand, reserved, available)=(6,2,4)、OTHER 不变、恰一条 ship 流水
 *     (-4,-4)（合同 acceptance[0]；预期由 Java 字面算出，不抄探针返回；注入树红点
 *     ——过度拒绝使合法拍失败）
 * <li>{@code l10_ship_draft} / {@code l10_ship_cancelled} / {@code l10_ship_repeat}
 *     三种非法迁移拒绝：InvalidTransition + 前后四表快照相等（合同 acceptance[1]
 *     「非法迁移被阻断且订单状态不变」；注入树绿——`if True:` 全拒绝恰好不破坏
 *     拒绝拍，隔离护栏与注入形态互证，红点构成如实入账）
 * <li>{@code illegal_transition_is_blocked} 客户绑定 eval 子进程照跑（登记项名 =
 *     客户名原样，业务权威——ADR-0005；注入树绿——同因隔离）
 * <li>{@code l10_frozen_checks} 客户 eval 双树 + 发货面源件（service.py / sales.py）
 *     指纹复核（讲义 C8：发货面源件全冻，注入 diff 即对账件；注入树红点之二——
 *     同一注入行的指纹观察面）
 * </ul>
 *
 * <p>注入树红点构成（两项一因，讲义 §3 步骤 4）：legal + frozen_checks——同一注入
 * 行（守卫替换为全拒绝）的两个观察面；三拒绝拍与客户 eval 不受影响（隔离护栏）。
 * refuse-all 教学反证面留在探针模式，不进默认登记项（必红项不进默认门——L09 leak
 * 先例）。
 *
 * <p>argv 面（l08/l09 Checks 同形）：{@code --target} 必填（客户树根，须含
 * flowerp/）；{@code --case}（可多，选跑子集）；{@code --python}（缺省
 * .venv/bin/python，按进程 cwd 绝对化不查 PATH）、{@code --no-report}、
 * {@code --report-path}。stdout 打印全量报告；退出码 = blocking 失败决定。
 */
public final class ShipChecks {

    /** 冻结面（讲义 C8）：客户 eval 双树 + 发货面源件（注入 diff 即对账件）。 */
    private static final String[] FROZEN_FILES = {
            "eval/harness.py", "eval/cases.py", "flowerp/service.py", "flowerp/sales.py"};
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private ShipChecks() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--python", "--report-path"), Set.of("--no-report"),
                    List.of(), Set.of("--case"));
            Path target = Path.of(args.require("--target")).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            Path interpreter = ShipProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
            Path reportPath = (args.flag("--no-report") || !args.has("--report-path"))
                    ? null : Path.of(args.require("--report-path"));
            List<String> selected = args.repeatingList("--case");
            List<EvalHarness.Entry> all = entries(target, interpreter);
            List<EvalHarness.Entry> run = selected.isEmpty() ? all : all.stream()
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
        } catch (ShipProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l10_ship_legal", "blocking",
                        () -> shipScenario(interpreter, target, "legal")),
                new EvalHarness.Entry("l10_ship_draft", "blocking",
                        () -> shipScenario(interpreter, target, "draft-ship")),
                new EvalHarness.Entry("l10_ship_cancelled", "blocking",
                        () -> shipScenario(interpreter, target, "cancelled-ship")),
                new EvalHarness.Entry("l10_ship_repeat", "blocking",
                        () -> shipScenario(interpreter, target, "double-ship")),
                new EvalHarness.Entry("illegal_transition_is_blocked", "blocking",
                        () -> customerCase(interpreter, target, "illegal_transition_is_blocked")),
                new EvalHarness.Entry("l10_frozen_checks", "blocking",
                        () -> frozenChecks(target)));
    }

    // ---- 场景断言（独立预期，全部留 Java） ------------------------------------------

    /**
     * 发货面场景：探针回报原始观察值，独立预期与全部判断在本方法完成（不抄子进程
     * 返回）。四表相等性、OTHER 前后相等同样 Java 断言。
     */
    private static String shipScenario(Path interpreter, Path target, String mode) {
        try (ShipProbe.TempDb tempDb = ShipProbe.tempDb("l10-ship-")) {
            JsonNode observed = ShipProbe.ship(interpreter, target, tempDb.db, mode);
            JsonNode error = observed.path("error");
            switch (mode) {
                case "legal" -> {
                    if (error.isObject()) {
                        throw new AssertionError("预期合法发货成功，实际被拒：" + error);
                    }
                    if (!"shipped".equals(observed.path("status").asText())) {
                        throw new AssertionError("expected status=shipped actual="
                                + observed.path("status").asText());
                    }
                    // 独立预期：在库 10、发货 4 → 6；预占 6-4 → 2；可用 10-2 → 4
                    assertStock(observed.path("stock"), List.of(List.of("A", 6, 2, 4)));
                    assertShipEvents(observed.path("ship_events"),
                            List.of(List.of("A", -4, -4)));
                    if (!observed.path("other_before").equals(observed.path("other_after"))) {
                        throw new AssertionError("OTHER 订单在 TARGET 发货前后发生变化");
                    }
                }
                case "draft-ship", "cancelled-ship", "double-ship" -> {
                    if (!error.isObject()
                            || !"InvalidTransition".equals(error.path("type").asText())) {
                        throw new AssertionError("非法迁移应被 InvalidTransition 拒绝，实际：" + error);
                    }
                    if (!observed.path("before").equals(observed.path("after"))) {
                        throw new AssertionError("拒绝路径四表状态发生变化（不应有半成品）");
                    }
                    if (!observed.path("other_before").equals(observed.path("other_after"))) {
                        throw new AssertionError("拒绝路径波及 OTHER 订单（应保持隔离）");
                    }
                }
                default -> throw new AssertionError("未知模式：" + mode);
            }
            return switch (mode) {
                case "legal" -> "合法发货：shipped、(6,2,4)、OTHER 不变、一条 ship 流水 (-4,-4)";
                case "draft-ship" -> "draft 订单发货拒绝：InvalidTransition、四表不变";
                case "cancelled-ship" -> "cancelled 订单发货拒绝：InvalidTransition、四表不变";
                case "double-ship" -> "重复发货拒绝：InvalidTransition、四表不变（首单保留）";
                default -> throw new AssertionError(mode);
            };
        }
    }

    /** 客户 blocking eval 子进程照跑（l08 SalesChecks.customerCase 可见性开放复用）。 */
    private static String customerCase(Path interpreter, Path target, String caseName) {
        return workbench.evals.l08.SalesChecks.customerCase(interpreter, target, caseName);
    }

    /** 客户 eval 双树 + 发货面源件指纹复核（l07–l09 frozenChecks 扩面：发货面源件全冻）。 */
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

    // ---- 断言小面 ------------------------------------------------------------

    /** stock 断言：[[sku, on_hand, reserved, available], ...] 独立预期逐项比。 */
    private static void assertStock(JsonNode stock, List<List<Object>> expected) {
        if (stock.size() != expected.size()) {
            throw new AssertionError("stock expected=" + expected + " actual=" + stock);
        }
        for (int i = 0; i < expected.size(); i++) {
            JsonNode row = stock.get(i);
            List<Object> want = expected.get(i);
            if (!want.get(0).equals(row.path(0).asText()) || row.path(1).asInt(-1) != (Integer) want.get(1)
                    || row.path(2).asInt(-1) != (Integer) want.get(2)
                    || row.path(3).asInt(-1) != (Integer) want.get(3)) {
                throw new AssertionError("stock expected=" + expected + " actual=" + stock);
            }
        }
    }

    /** ship 流水断言：[[sku, quantity, reserved_delta], ...] 归属 TARGET，恰一条。 */
    private static void assertShipEvents(JsonNode events, List<List<Object>> expected) {
        if (events.size() != expected.size()) {
            throw new AssertionError("ship_events expected=" + expected + " actual=" + events);
        }
        for (int i = 0; i < expected.size(); i++) {
            JsonNode row = events.get(i);
            List<Object> want = expected.get(i);
            if (!want.get(0).equals(row.path(0).asText())
                    || row.path(1).asInt(-999) != (Integer) want.get(1)
                    || row.path(2).asInt(-999) != (Integer) want.get(2)) {
                throw new AssertionError("ship_events expected=" + expected + " actual=" + events);
            }
        }
    }

    private static String sha256(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (java.io.IOException error) {
            throw new ShipProbe.ToolFailure("冻结检查文件读取失败：" + file, error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new ShipProbe.ToolFailure("SHA-256 不可用", error);
        }
    }

    /** l06–l09 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根），不随 cwd 漂移。 */
    private static Path repoRoot() {
        try {
            return Path.of(ShipChecks.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new ShipProbe.ToolFailure("仓库根定位失败（class 装载位置）", error);
        }
    }
}
