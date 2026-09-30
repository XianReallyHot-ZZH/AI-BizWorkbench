package workbench.evals.l06;

import com.fasterxml.jackson.databind.JsonNode;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L06 库存口径交叉检查驱动（Java 重走件，对照冻结 {@code evals/l06/stock_consistency_check.py}）：
 * 查询与 CSV 都必须遵守 可用 = 在手 − 预占。
 *
 * <p>预期由 --opening/--reserved 算术在 Java 侧独立计算（上游 §1：8−3=5 来自业务规则，
 * 不能拿查询返回值当导出预期）；同时覆盖超额预占拒绝且状态不变（FlowERP 边界 #1，合同绑定
 * eval {@code stock_never_negative} 的驱动面）。每次运行自建临时库，天然从干净数据开始。
 * blocking 身份属登记项 {@code l06_stock_consistency}（讲义 §2）。客户实现经
 * {@link StockProbe} 子进程跑场景、只回报原始观察值；四表快照锚点 = 超额草稿单创建之后
 * （R1 终态照抄：建单是合法写入，被拒绝的是预占）。
 *
 * <p>stdout 单行 JSON（pass/fail/input 三形）与冻结件逐字同形（fail 的 expected/actual
 * 逐 step 同载荷）；rc：0 pass / 1 fail（含 input 窗口不成立）/ 2 用法错误。
 * {@link #runScenario} 为 Checks 的进程内复用面（Python 经子进程再跑 driver——方向与
 * L05 相反的结构翻译，附录 A1/JD2：返回 (rc, 末行)，Checks 以 {@code "rc=1: "+行} 构造
 * 与子进程路径逐字节同形的 evidence）。
 */
public final class StockConsistencyCheck {

    private static final String REQUIREMENT = "可用库存按在手减预占计算：查询与 CSV 同口径，且永不为负";
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    /** 场景结局：(退出码, 输出行)——行即 pass/fail JSON（main 打印它，Checks 直接复用它）。 */
    public record Outcome(int code, String line) {}

    private StockConsistencyCheck() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--opening", "--reserved", "--python"), Set.of());
            Path target = Path.of(args.require("--target")).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            int opening = intOption(args, "--opening", 8);
            int reserved = intOption(args, "--reserved", 3);
            Path interpreter = StockProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
            Outcome outcome = runScenario(target, interpreter, opening, reserved);
            System.out.println(outcome.line());
            if (outcome.code() != 0) {
                System.exit(outcome.code());
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (StockProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    // L07 起开放为跨讲复用缝（l07 OrderChecks 的 l06_stock_regression 登记项进程内复用本
    // 面开两词可见性，行为零变化——mvn 全量 + golden l06 重放为证；l06 语义与词面不动）。
    public static Outcome runScenario(Path target, Path interpreter, int opening, int reserved) {
        if (!(0 < reserved && reserved < opening)) {
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("opening", opening);
            actual.put("reserved", reserved);
            return fail("input", "0 < reserved < opening", actual);
        }

        JsonNode observed;
        try (StockProbe.TempDb temp = StockProbe.tempDb("l06-stock-")) {
            observed = StockProbe.scenario(interpreter, target, temp.db, opening, reserved);
        }

        int expectedAvailable = opening - reserved;
        List<Integer> expectedTriple = List.of(opening, reserved, expectedAvailable);
        int csvRows = observed.path("csv_rows").asInt();
        if (csvRows != 1) {
            return fail("AC-CSV-ROW", 1, csvRows);
        }
        List<Integer> query = intTriple(observed.path("query"));
        List<Integer> csv = intTriple(observed.path("csv"));
        if (!query.equals(expectedTriple) || !csv.equals(expectedTriple)) {
            Map<String, Object> seen = new LinkedHashMap<>();
            seen.put("query", query);
            seen.put("csv", csv);
            Map<String, Object> wanted = new LinkedHashMap<>();
            wanted.put("wanted", expectedTriple);
            return fail("AC-AVAILABLE", wanted, seen);
        }
        if (!observed.path("rejected").asBoolean()) {
            return fail("AC-REJECT", "预占 " + (expectedAvailable + 1) + " 被拒绝", "被接受");
        }
        if (!observed.path("before").equals(observed.path("after"))) {
            return fail("AC-UNCHANGED", "拒绝后库存/流水/订单/明细不变", "拒绝后改变了账面");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("case", "stock_consistency");
        out.put("status", "pass");
        out.put("opening", opening);
        out.put("reserved", reserved);
        out.put("available", expectedAvailable);
        out.put("query", expectedTriple);
        out.put("csv", expectedTriple);
        out.put("rejected_reserve", expectedAvailable + 1);
        out.put("state_unchanged", true);
        String line = PyJson.dumpsCompact(out);
        return new Outcome(0, line);
    }

    /** 与冻结驱动同形的失败行：step/status/expected/actual/requirement（键序 = Python 插入序）。 */
    private static Outcome fail(String step, Object expected, Object actual) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step", step);
        out.put("status", "fail");
        out.put("expected", expected);
        out.put("actual", actual);
        out.put("requirement", REQUIREMENT);
        return new Outcome(1, PyJson.dumpsCompact(out));
    }

    private static List<Integer> intTriple(JsonNode node) {
        return List.of(node.path(0).asInt(), node.path(1).asInt(), node.path(2).asInt());
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
